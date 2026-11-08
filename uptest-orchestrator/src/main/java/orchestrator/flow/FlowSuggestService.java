package orchestrator.flow;

import orchestrator.catalog.CatalogService;
import orchestrator.flow.FlowModel.Flow;
import orchestrator.flow.FlowModel.Intent;
import orchestrator.flow.FlowModel.Node;
import orchestrator.llm.SuggestService;
import orchestrator.product.ProductModel;
import orchestrator.product.Rule;
import orchestrator.catalog.CatalogModel;
import orchestrator.vocabulary.Vocabulary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Stage 5: one planned intent in, one .feature out — in parallel, one model
 * call each.
 *
 * <p><b>One document per scenario, never one file with N.</b>
 * {@code checkAssertionsAreEarned} pools every step of a file and walks it
 * with one set of written labels, so in a five-scenario file the fifth
 * scenario's {@code verify} is "earned" by the first's {@code enter}. Each
 * scenario therefore gets its own call, its own validation and its own
 * repair turn — the same reasoning {@code suggestMany} records.
 *
 * <p><b>The catalog is passed only when it is honestly the right one.</b> A
 * scenario that stays on one screen, where that screen has a saved catalog,
 * gets it and with it the six catalog checks. A journey crosses screens, so
 * no single catalog describes it and passing one would make the label check
 * refuse a control the drawing proved exists — the mistake
 * {@code suggestFromWalk} documents. Those go with {@code catalog = null}
 * and lean on {@link FlowProvenance} instead.
 */
@Service
public class FlowSuggestService {

    private final SuggestService suggest;
    private final FlowStore store;
    private final CatalogService catalogs;
    private final int concurrency;

    public FlowSuggestService(SuggestService suggest, FlowStore store, CatalogService catalogs,
                              @Value("${llm.batch-concurrency:4}") int concurrency) {
        this.suggest = suggest;
        this.store = store;
        this.catalogs = catalogs;
        this.concurrency = Math.max(1, concurrency);
    }

    /** One produced scenario, in the shape the UI's batch review already reads. */
    public record Draft(int index, String title, String intent, List<String> path,
                        List<String> ruleIds, String feature, boolean valid,
                        orchestrator.validation.ValidationResult validation,
                        int attempts, List<String> log) {
    }

    public record Batch(List<Draft> scenarios, List<String> notes, long tokens) {
    }

    /**
     * Writes every scenario in the plan.
     *
     * @param loginStep the login line to force in, or null; a batch here is
     *                  the same asymmetry {@code suggest-many} has — N
     *                  separate calls with a blank cif invent N different
     *                  customer numbers
     */
    public Batch write(String app, String product, Flow flow, List<Intent> plan,
                       Vocabulary vocabulary, ProductModel model, List<Rule> rules,
                       String loginStep, String testData) {
        if (plan.isEmpty()) {
            return new Batch(List.of(), List.of("Planda senaryo yok."), 0);
        }
        Map<String, String> reads = store.reads(app, product, flow);
        SuggestService.ProductContext context = new SuggestService.ProductContext(
                model, rules == null ? List.of() : rules);
        long before = orchestrator.llm.LlmClient.spent();

        Draft[] drafts = new Draft[plan.size()];
        List<String> notes = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(concurrency, plan.size()));
        try {
            List<Future<Draft>> futures = new ArrayList<>();
            for (int i = 0; i < plan.size(); i++) {
                int index = i;
                Intent intent = plan.get(i);
                futures.add(pool.submit(() -> one(index, app, product, flow, reads, intent,
                        vocabulary, context, rules, loginStep, testData)));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    drafts[i] = futures.get(i).get();
                } catch (Exception failed) {
                    // One scenario that blew up is a note, not a dead batch.
                    Throwable cause = failed.getCause() == null ? failed : failed.getCause();
                    notes.add("\"" + plan.get(i).title() + "\" üretilemedi: "
                            + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                }
            }
        } finally {
            pool.shutdown();
        }

        List<Draft> out = new ArrayList<>();
        for (Draft draft : drafts) {
            if (draft != null) {
                out.add(draft);
            }
        }
        long invalid = out.stream().filter(draft -> !draft.valid()).count();
        if (invalid > 0) {
            notes.add(invalid + " senaryo doğrulamadan geçemedi; kartlarında bulguları yazıyor."
                    + " Geçersiz bir taslak da açılıp düzeltilebilir.");
        }
        return new Batch(out, notes, orchestrator.llm.LlmClient.spent() - before);
    }

    private Draft one(int index, String app, String product, Flow flow, Map<String, String> reads,
                      Intent intent, Vocabulary vocabulary, SuggestService.ProductContext context,
                      List<Rule> rules, String loginStep, String testData) {
        String catalogYaml = singleScreenCatalog(app, flow, intent);
        CatalogModel catalog = catalogYaml == null
                ? null
                : CatalogModel.parse(catalogYaml).orElse(null);

        String user = FlowPrompts.scenario(app, flow, reads, intent, rules, catalogYaml, testData);

        // What a quoted word may legitimately come from. The edge labels are
        // in here because an analyst typing "Fatura Oluştur" on an arrow is
        // asserting that control exists — the drawing is evidence too.
        String screens = String.join("\n", reads.values());
        String edges = String.join("\n", flow.edges().stream().map(FlowModel.Edge::label).toList());
        String titles = String.join("\n", flow.nodes().stream().map(Node::title).toList());
        String notes = String.join("\n", flow.nodes().stream().map(Node::note).toList());
        String ruleText = rules == null ? "" : String.join("\n",
                rules.stream().map(rule -> rule.title() + " " + rule.description() + " "
                        + String.join(" ", rule.messages()) + " "
                        + String.join(" ", rule.allValues())).toList());

        SuggestService.Suggestion made = suggest.suggestFromDrawing(
                user, intent.intent(), vocabulary, catalog, context, loginStep,
                feature -> FlowProvenance.check(feature, screens, edges, titles, notes,
                        catalogYaml, ruleText, testData, intent.intent(), intent.title()));

        return new Draft(index, intent.title(), intent.intent(), intent.path(), intent.ruleIds(),
                made.feature(), made.valid(), made.validation(), made.attempts(), made.log());
    }

    /**
     * The catalog of a one-screen scenario, when that screen has one saved.
     *
     * Null for a journey, and null for a screen whose catalog was never
     * written — in both cases the catalog checks stay off rather than run
     * against the wrong screen.
     */
    private String singleScreenCatalog(String app, Flow flow, Intent intent) {
        if (intent.path().size() != 1) {
            return null;
        }
        Node node = flow.node(intent.path().get(0));
        if (node == null || node.flow() == null || node.flow().isBlank()) {
            return null;
        }
        return catalogs.read(app, node.flow()).orElse(null);
    }

    /** The rules the analyst ticked, or every rule the product has. */
    public static List<Rule> rulesFor(ProductModel model, List<String> ruleIds) {
        if (model == null) {
            return List.of();
        }
        if (ruleIds == null || ruleIds.isEmpty()) {
            return model.rules();
        }
        Map<String, Rule> byId = new LinkedHashMap<>();
        model.rules().forEach(rule -> byId.put(rule.id(), rule));
        List<Rule> out = new ArrayList<>();
        ruleIds.forEach(id -> {
            Rule rule = byId.get(id);
            if (rule != null) {
                out.add(rule);
            }
        });
        return out;
    }
}
