package orchestrator.flow;

import orchestrator.flow.FlowModel.Flow;
import orchestrator.flow.FlowModel.Intent;
import orchestrator.flow.FlowModel.Plan;
import orchestrator.llm.SuggestService;
import orchestrator.product.ProductModel;
import orchestrator.product.Rule;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage 3: decide WHICH scenarios to write, before writing any of them.
 *
 * <p>This is the cheap checkpoint the pipeline is built around. Choosing
 * twenty-five scenarios out of a flow is judgement, which is the model's
 * part — but doing it inside twenty-five expensive generation calls means
 * discovering the wrong choices one scenario at a time. One text call
 * produces a list of one-line intents, the analyst deletes and adds lines,
 * and only then is generation spent. It is {@code VariationPlanner}'s job
 * done for a multi-screen flow, where the combinations are paths rather than
 * field values and so cannot be computed in Java.
 *
 * <p>The plan is written to disk, so a restart does not lose it and the
 * analyst can come back to it.
 */
@Service
public class FlowPlanner {

    /** More paths than this and nobody reads the plan, let alone the scenarios. */
    private static final int MAX_PATHS = 40;

    private final SuggestService suggest;
    private final FlowStore store;
    private final ObjectMapper mapper;

    public FlowPlanner(SuggestService suggest, FlowStore store, ObjectMapper mapper) {
        this.suggest = suggest;
        this.store = store;
        this.mapper = mapper;
    }

    /**
     * Asks for {@code count} intents and keeps what came back, repaired where
     * it can be and dropped where it cannot.
     *
     * @param rules the rules the analyst ticked, or all of them
     */
    public Plan plan(String app, String product, Flow flow, ProductModel model,
                     List<Rule> rules, int count) {
        Map<String, String> reads = store.reads(app, product, flow);
        List<List<String>> paths = flow.paths(MAX_PATHS);

        String answer = suggest.ask(FlowPrompts.PLAN_SYSTEM,
                FlowPrompts.plan(app, flow, reads, rules, paths, count));

        List<String> notes = new ArrayList<>();
        List<Intent> scenarios = parse(answer, flow, model, notes, count);

        if (scenarios.isEmpty()) {
            notes.add("Model hiç kullanılabilir niyet döndürmedi. Cevabın başı: " + head(answer));
        } else if (scenarios.size() < count) {
            notes.add(count + " istendi, " + scenarios.size() + " kullanılabilir niyet geldi."
                    + " Ekranları okutmak ya da daha fazla kural seçmek sayıyı artırır.");
        }
        notes.addAll(edgeProblems(flow, reads));
        if (reads.isEmpty()) {
            notes.add("Hiçbir ekran okunamadı: model yalnızca kutu adlarını ve ok etiketlerini"
                    + " gördü, alan adlarını görmedi. Kutulara ekran görüntüsü ekleyin"
                    + " (kutuyu seçip Ctrl+V) — planlama okunmamış olanları kendisi okur.");
        }

        Plan plan = new Plan(scenarios, notes);
        store.writePlan(app, product, plan);
        return plan;
    }

    /**
     * Arrow labels no screen actually prints.
     *
     * <p>An arrow's label is not a description of a transition, it is the
     * TEXT ON THE CONTROL that makes it — the generator turns it straight
     * into {@code click "<label>"}. An analyst who writes "faturayı oluşturma
     * adımı" has written something {@code getByRole(BUTTON|LINK)} can never
     * find, and the cost lands minutes into a browser run as a thirty-second
     * timeout on a line that looks perfectly reasonable.
     *
     * <p>Checked here rather than asked for in the prompt, because the prompt
     * already asks and this project has measured what that is worth: a rule
     * only holds once it is code. Reported, never rewritten — the right label
     * is on a screen only the analyst has seen, and guessing one would turn a
     * visible mistake into an invisible one.
     *
     * <p>Only screens that were READ can be checked: with no draft there is
     * nothing to compare against, and silence is the honest answer. Matching
     * is {@link FlowProvenance#fold}'s, plus whitespace stripped on both
     * sides — a label the page wraps over two lines reads run-together, which
     * is exactly the bug that cost a debugging round on this application.
     */
    private static List<String> edgeProblems(Flow flow, Map<String, String> reads) {
        List<String> out = new ArrayList<>();
        for (FlowModel.Edge edge : flow.edges()) {
            String label = edge.label() == null ? "" : edge.label().trim();
            FlowModel.Node from = flow.node(edge.from());
            FlowModel.Node to = flow.node(edge.to());
            String fromName = from == null ? edge.from() : from.title();
            String toName = to == null ? edge.to() : to.title();
            if (label.isEmpty()) {
                out.add("\"" + fromName + "\" → \"" + toName + "\" okunun etiketi boş."
                        + " Etiket, o geçişi yapan butonun ÜSTÜNDEKİ yazı olmalı.");
                continue;
            }
            String read = from == null ? null : reads.get(from.id());
            if (read == null || read.isBlank()) {
                continue; // not read yet; nothing to check against
            }
            String known = FlowProvenance.fold(read);
            String folded = FlowProvenance.fold(label);
            if (known.contains(folded) || known.replace(" ", "").contains(folded.replace(" ", ""))) {
                continue;
            }
            out.add("\"" + fromName + "\" ekranında \"" + label + "\" diye bir şey okunmadı,"
                    + " ama \"" + toName + "\" ekranına giden ok böyle etiketlenmiş."
                    + " Senaryo bunu click \"" + label + "\" olarak yazacak ve tarayıcıda"
                    + " bulamayacak — etiketi ekranda yazdığı gibi düzeltin.");
        }
        return out;
    }

    /**
     * What the model returned, with every line checked against the drawing.
     *
     * A plan line naming a screen that is not on the canvas, or a rule the
     * product does not have, is dropped rather than carried into generation:
     * the scenario written from it would quote a label from nowhere and spend
     * a repair turn failing.
     */
    private List<Intent> parse(String answer, Flow flow, ProductModel model,
                               List<String> notes, int count) {
        JsonNode root = json(answer);
        JsonNode array = root == null ? null : root.get("scenarios");
        if (array == null || !array.isArray()) {
            return List.of();
        }
        Set<String> nodeIds = new LinkedHashSet<>();
        flow.nodes().forEach(node -> nodeIds.add(node.id()));
        Set<String> ruleIds = new LinkedHashSet<>();
        if (model != null) {
            model.rules().forEach(rule -> ruleIds.add(rule.id()));
        }

        List<Intent> out = new ArrayList<>();
        Set<String> seenTitles = new LinkedHashSet<>();
        for (JsonNode entry : array) {
            if (out.size() >= count) {
                break;
            }
            String title = text(entry, "title");
            String intent = text(entry, "intent");
            if (title.isBlank() || intent.isBlank()) {
                continue;
            }
            if (!seenTitles.add(FlowProvenance.fold(title))) {
                continue; // the model asked for the same thing twice
            }
            List<String> path = strings(entry.get("path")).stream().filter(nodeIds::contains).toList();
            if (path.isEmpty()) {
                notes.add("\"" + title + "\" atlandı: çizimde olmayan ekranlara işaret ediyor.");
                continue;
            }
            List<String> rules = strings(entry.get("ruleIds")).stream()
                    .filter(ruleIds::contains).toList();
            out.add(new Intent("s" + (out.size() + 1), title, intent, path, rules,
                    entry.path("negative").asBoolean(false)));
        }
        return out;
    }

    private JsonNode json(String answer) {
        if (answer == null || answer.isBlank()) {
            return null;
        }
        int open = answer.indexOf('{');
        int close = answer.lastIndexOf('}');
        if (open < 0 || close <= open) {
            return null;
        }
        try {
            return mapper.readTree(answer.substring(open, close + 1));
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                String value = item.asString("").trim();
                if (!value.isEmpty()) {
                    out.add(value);
                }
            });
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asString("").trim();
    }

    private static String head(String text) {
        String clean = text == null ? "" : text.strip().replace("\n", " ");
        return clean.length() > 200 ? clean.substring(0, 200) + "…" : clean;
    }
}
