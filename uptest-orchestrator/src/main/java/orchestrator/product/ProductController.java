package orchestrator.product;

import orchestrator.catalog.CatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads product context out to the UI. Read-only on purpose.
 *
 * Rules live in the repository, where git gives review, history and a diff for
 * free, and where a reviewer can refuse a change. A browser-side editor would
 * make them free text arriving over HTTP — the uncontrolled channel
 * {@code TestRunEntity} already documents and {@code ChatSessionStore} already
 * declines to persist. That is a later decision with its own answer, not a
 * thing to get for free by adding a POST here.
 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductService products;
    private final CatalogService catalogs;

    public ProductController(ProductService products, CatalogService catalogs) {
        this.products = products;
        this.catalogs = catalogs;
    }

    /**
     * Every product, with enough shape for a picker and for the gap report.
     *
     * Rules themselves are NOT included — a product can carry fifty and three
     * pickers load this list. {@link #one} serves them when a product is
     * actually chosen.
     */
    @GetMapping
    public ResponseEntity<?> all() {
        List<String> known = catalogs.list();
        List<Map<String, Object>> rows = products.list().stream()
                .map(product -> summary(product, known))
                .toList();
        return ResponseEntity.ok(Map.of(
                "root", products.root().toString(),
                "products", rows));
    }

    /** One product in full: its rules, its free text, and what it is missing. */
    @GetMapping("/{app}/{product}")
    public ResponseEntity<?> one(@PathVariable String app, @PathVariable String product) {
        return products.load(app, product)
                .<ResponseEntity<?>>map(model -> {
                    Map<String, Object> body = new LinkedHashMap<>(summary(model, catalogs.list()));
                    body.put("rules", model.rules().stream().map(ProductController::rule).toList());
                    body.put("stateMachine", model.stateMachine());
                    body.put("roles", model.roles());
                    body.put("openQuestions", model.openQuestions());
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
                        "error", "Ürün bulunamadı: " + app + "/" + product,
                        "detail", products.diagnose(app, product))));
    }

    /**
     * One product's registered scenarios, with everything the Ürünler table
     * shows in its five columns.
     *
     * Nothing here is stored: the rows are the .feature files on disk, the
     * rule chips are their {@code @R-...} tags, the precondition is their
     * {@code @data:...} tag resolved against test-data.yaml, and the last run
     * is a join against the history. A registry file listing the same
     * scenarios would be a second source of truth, and this codebase has
     * already paid for one of those.
     */
    @GetMapping("/{app}/{product}/scenarios")
    public ResponseEntity<?> scenarios(@PathVariable String app, @PathVariable String product) {
        ProductModel model = products.load(app, product).orElse(null);
        if (model == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "Ürün bulunamadı: " + app + "/" + product,
                    "detail", products.diagnose(app, product)));
        }

        List<ScenarioCard> cards = products.scenarios(app, product);
        List<String> knownCatalogs = catalogs.list();

        Map<String, Object> body = new LinkedHashMap<>(summary(model, knownCatalogs));
        body.put("screens", screens(model, cards, knownCatalogs));
        body.put("scenarios", cards.stream().map(card -> row(card, model)).toList());
        return ResponseEntity.ok(body);
    }

    /**
     * One scenario's Gherkin, for the detail view behind a table row.
     *
     * The path travels as a query parameter rather than as path segments:
     * it contains slashes, and a wildcard mapping would also have to defend
     * against the encoded ones. {@code ScenarioLibrary.text} still checks
     * containment, because a path from a browser is never trusted.
     */
    @GetMapping("/{app}/{product}/scenario")
    public ResponseEntity<?> scenario(@PathVariable String app, @PathVariable String product,
                                      @RequestParam String path) {
        ProductModel model = products.load(app, product).orElse(null);
        if (model == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "Ürün bulunamadı: " + app + "/" + product));
        }
        String text = products.scenarioText(app, product, path).orElse(null);
        if (text == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "Senaryo bulunamadı: " + path));
        }
        ScenarioCard card = products.scenarios(app, product).stream()
                .filter(candidate -> candidate.path().equals(path))
                .findFirst().orElse(null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", path);
        body.put("feature", text);
        if (card != null) {
            body.putAll(row(card, model));
        }
        return ResponseEntity.ok(body);
    }

    /**
     * The screens this product owns, from two sources at once: the flows it
     * DECLARES and the folders its scenarios actually sit in.
     *
     * Both, because the two disagree in opposite and equally useful ways. A
     * declared flow with no scenarios is work not started; a scenario folder
     * nobody declared is a screen somebody forgot to list. Showing only the
     * declaration would hide the second entirely.
     */
    private static List<Map<String, Object>> screens(ProductModel product, List<ScenarioCard> cards,
                                                     List<String> knownCatalogs) {
        LinkedHashSet<String> flows = new LinkedHashSet<>(product.flows());
        cards.stream().map(ScenarioCard::flow).filter(flow -> !flow.isBlank()).forEach(flows::add);

        String prefix = product.app().toLowerCase(Locale.ROOT) + "/";
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String flow : flows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("flow", flow);
            row.put("catalog", knownCatalogs.contains(prefix + flow) ? prefix + flow : null);
            row.put("declared", product.flows().contains(flow));
            row.put("scenarioCount", cards.stream().filter(c -> flow.equals(c.flow())).count());
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, Object> row(ScenarioCard card, ProductModel product) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("path", card.path());
        row.put("flow", card.flow());
        row.put("title", card.title());
        row.put("description", card.description());
        row.put("stepCount", card.stepCount());
        row.put("problem", card.problem());
        row.put("tags", card.otherTags());

        // A tag naming a rule the product no longer has is reported, not
        // dropped. Rules get renamed and deleted; a scenario still pointing at
        // the old id looks bound and is not, and that is exactly the state
        // worth seeing.
        row.put("rules", card.ruleIds().stream().map(id -> {
            Rule rule = product.rules().stream()
                    .filter(candidate -> candidate.id().equals(id)).findFirst().orElse(null);
            Map<String, Object> chip = new LinkedHashMap<>();
            chip.put("id", id);
            chip.put("known", rule != null);
            chip.put("title", rule == null ? "" : rule.title());
            chip.put("confirmed", rule != null && rule.confirmed());
            return chip;
        }).toList());

        List<Map<String, Object>> needs = new ArrayList<>();
        DataAlias.Readiness worst = DataAlias.Readiness.READY;
        for (String id : card.needs()) {
            DataAlias alias = product.alias(id);
            // An undefined alias is MISSING, never READY. The scenario asked
            // for something by name and nobody wrote down where it comes
            // from; assuming the best would put it in a run queue it cannot
            // survive.
            DataAlias.Readiness readiness =
                    alias == null ? DataAlias.Readiness.MISSING : alias.readiness();
            worst = worse(worst, readiness);

            Map<String, Object> need = new LinkedHashMap<>();
            need.put("id", id);
            need.put("known", alias != null);
            need.put("label", alias == null ? id : alias.shown());
            need.put("readiness", readiness.name());
            need.put("how", alias == null ? "" : alias.how());
            need.put("description", alias == null ? "" : alias.description());
            need.put("blockedBy", alias == null ? "" : alias.blockedBy());
            needs.add(need);
        }
        row.put("needs", needs);
        row.put("needsNone", card.needsNone());
        // UNDECLARED is not a data problem, it is an unfinished scenario: the
        // preconditions were never written down, so nobody can say whether it
        // runs. @needs:none is how an author says "checked, there are none".
        row.put("readiness", card.needsUndeclared() ? "UNDECLARED" : worst.name());
        return row;
    }

    /** A row is as ready as its worst need. BLOCKED > MISSING > SEED > READY. */
    private static DataAlias.Readiness worse(DataAlias.Readiness a, DataAlias.Readiness b) {
        if (a == DataAlias.Readiness.BLOCKED || b == DataAlias.Readiness.BLOCKED) {
            return DataAlias.Readiness.BLOCKED;
        }
        if (a == DataAlias.Readiness.MISSING || b == DataAlias.Readiness.MISSING) {
            return DataAlias.Readiness.MISSING;
        }
        if (a == DataAlias.Readiness.SEED || b == DataAlias.Readiness.SEED) {
            return DataAlias.Readiness.SEED;
        }
        return DataAlias.Readiness.READY;
    }

    private Map<String, Object> summary(ProductModel product, List<String> knownCatalogs) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("app", product.app());
        row.put("product", product.key());
        row.put("name", product.name());
        row.put("category", product.category());
        row.put("description", product.description());
        row.put("flows", product.flows());
        // The flows this product claims that nobody has catalogued yet. Not an
        // error: a product is specified before its screens are walked, so this
        // is the work queue, and it is only visible because the product names
        // its flows rather than each catalog naming its product.
        row.put("missingCatalogs", missing(product, knownCatalogs));
        row.put("ruleCount", product.rules().size());
        row.put("assumptionCount", product.rules().stream().filter(r -> !r.confirmed()).count());
        row.put("messageCount", product.messages().size());
        row.put("hasOpenQuestions", !product.openQuestions().isBlank());
        // Reported rather than silently resolved: a lenient parse keeps the
        // first rule of a repeated id, and without this the others would
        // simply not exist.
        row.put("duplicateIds", product.duplicateIds());
        row.put("scenarioCount", products.scenarioCount(product.app(), product.key()));
        return row;
    }

    private static List<String> missing(ProductModel product, List<String> knownCatalogs) {
        String prefix = product.app().toLowerCase(Locale.ROOT) + "/";
        return product.flows().stream()
                .filter(flow -> !knownCatalogs.contains(prefix + flow))
                .toList();
    }

    private static Map<String, Object> rule(Rule rule) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rule.id());
        row.put("title", rule.title());
        row.put("status", rule.status());
        row.put("confirmed", rule.confirmed());
        row.put("description", rule.description());
        row.put("fields", rule.fields());
        row.put("messages", rule.messages());
        row.put("source", rule.source());
        return row;
    }
}
