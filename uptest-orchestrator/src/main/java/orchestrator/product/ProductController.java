package orchestrator.product;

import orchestrator.catalog.CatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Reads product context out to the UI.
 *
 * The Kayıtlı Senaryolar tree -- ana menü, screens, scenarios -- lives in the
 * database and is edited here ({@link #saveScenario} and the POSTs). Rules
 * and test data stay read-only files: they live in the repository, where git
 * gives review and history. {@link #export} writes the scenarios back out as
 * .feature files when they should be committed.
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

    public record NewProduct(String app, String name) {
    }

    /** A new ana menü (see ProductService.createProduct); answers with its summary row. */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody NewProduct body) {
        ProductService.Created created = products.createProduct(
                body == null ? null : body.app(), body == null ? null : body.name());
        if (created.refused() != null) {
            return ResponseEntity.badRequest().body(Map.of("error", created.refused()));
        }
        return products.load(body.app(), created.key())
                .<ResponseEntity<?>>map(model -> ResponseEntity.status(201)
                        .body(summary(model, catalogs.list())))
                .orElseGet(() -> ResponseEntity.status(500).body(Map.of(
                        "error", "Ana menü oluşturuldu ama okunamadı",
                        "detail", products.diagnose(body.app(), created.key()))));
    }

    public record NewScreen(String name) {
    }

    public record MoveTarget(String product, String flow) {
    }

    /** {@code heading}: a "Başlık" NAME, optional -- blank means başlıksız. */
    public record NewScenario(String flow, String feature, String heading) {
    }

    public record NewHeading(String flow, String name) {
    }

    /** {@code heading}: a heading id, or null for "Başlıksız". */
    public record HeadingChoice(String heading) {
    }

    /** "+" beside a screen: a new scenario file there (see ProductService.createScenario). */
    @PostMapping("/{app}/{product}/scenarios")
    public ResponseEntity<?> createScenario(@PathVariable String app, @PathVariable String product,
                                            @RequestBody NewScenario body) {
        ProductService.Created created = products.createScenario(app, product,
                body == null ? null : body.flow(), body == null ? null : body.feature(),
                body == null ? null : body.heading());
        if (created.refused() != null) {
            return ResponseEntity.badRequest().body(Map.of("error", created.refused()));
        }
        return ResponseEntity.status(201).body(Map.of("path", created.key()));
    }

    /** "Taşı": moves a scenario to another declared screen of the same app. */
    @PostMapping("/{app}/{product}/scenario/move")
    public ResponseEntity<?> moveScenario(@PathVariable String app, @PathVariable String product,
                                          @RequestParam String path,
                                          @RequestBody MoveTarget body) {
        ProductService.Created moved = products.moveScenario(app, product, path,
                body == null ? null : body.product(), body == null ? null : body.flow());
        if (moved.refused() != null) {
            return ResponseEntity.badRequest().body(Map.of("error", moved.refused()));
        }
        return ResponseEntity.ok(Map.of("path", moved.key()));
    }

    /**
     * "+ Başlık": a heading on one screen. 201 for a new one, 200 when the
     * screen already had a heading of that name -- the body is the heading
     * either way, so the caller need not care which.
     */
    @PostMapping("/{app}/{product}/headings")
    public ResponseEntity<?> addHeading(@PathVariable String app, @PathVariable String product,
                                        @RequestBody NewHeading body) {
        ProductService.HeadingResult result = products.addHeading(app, product,
                body == null ? null : body.flow(), body == null ? null : body.name());
        if (result.refused() != null) {
            return ResponseEntity.badRequest().body(Map.of("error", result.refused()));
        }
        Map<String, Object> heading = heading(result.added().heading());
        heading.put("existed", result.added().existed());
        return ResponseEntity.status(result.added().existed() ? 200 : 201).body(heading);
    }

    /** The ⇄ menu's "Başlık" half: groups a scenario under a heading of its screen, or none. */
    @PostMapping("/{app}/{product}/scenario/heading")
    public ResponseEntity<?> assignHeading(@PathVariable String app, @PathVariable String product,
                                           @RequestParam String path,
                                           @RequestBody HeadingChoice body) {
        ProductService.Created assigned = products.assignHeading(app, product, path,
                body == null ? null : body.heading());
        if (assigned.refused() != null) {
            return ResponseEntity.badRequest().body(Map.of("error", assigned.refused()));
        }
        return ResponseEntity.ok(Map.of("path", assigned.key()));
    }

    private static Map<String, Object> heading(ScenarioStore.Heading heading) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", heading.id().toString());
        row.put("flow", heading.flow());
        row.put("name", heading.name());
        return row;
    }

    /** "+ Ekran ekle": declares a screen on this product (see ProductService.addScreen). */
    @PostMapping("/{app}/{product}/screens")
    public ResponseEntity<?> addScreen(@PathVariable String app, @PathVariable String product,
                                       @RequestBody NewScreen body) {
        ProductService.Created created = products.addScreen(app, product,
                body == null ? null : body.name());
        if (created.refused() != null) {
            return ResponseEntity.badRequest().body(Map.of("error", created.refused()));
        }
        return ResponseEntity.status(201).body(Map.of("flow", created.key()));
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
     * The rows are the scenarios in the database, the rule chips their rules,
     * and the precondition their {@code @needs:...} resolved against
     * test-data.yaml.
     */
    @GetMapping("/{app}/{product}/scenarios")
    public ResponseEntity<?> scenarios(@PathVariable String app, @PathVariable String product) {
        ProductModel model = products.load(app, product).orElse(null);
        if (model == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "Ürün bulunamadı: " + app + "/" + product,
                    "detail", products.diagnose(app, product)));
        }

        List<ProductService.Registered> registered = products.registered(app, product);
        List<ScenarioCard> cards = registered.stream().map(ProductService.Registered::card).toList();
        List<String> knownCatalogs = catalogs.list();

        Map<String, Object> body = new LinkedHashMap<>(summary(model, knownCatalogs));
        body.put("screens", screens(model, cards, knownCatalogs));
        body.put("headings", products.headings(app, product).stream()
                .map(ProductController::heading).toList());
        body.put("scenarios", registered.stream().map(entry -> {
            Map<String, Object> row = row(entry.card(), model,
                    products.changes(entry.stored(), model));
            UUID heading = entry.stored().headingId();
            row.put("heading", heading == null ? null : heading.toString());
            // The Ön koşul column shows these, item by item, as written --
            // so the Açıklama column leaves them out.
            row.put("preconditions", entry.fields().preconditions());
            row.put("description", description(entry.fields()));
            return row;
        }).toList());
        return ResponseEntity.ok(body);
    }

    /**
     * One scenario, for the detail view behind a table row.
     *
     * The path ({@code <screen>/<file name>}) travels as a query parameter
     * rather than as path segments because it contains a slash.
     */
    @GetMapping("/{app}/{product}/scenario")
    public ResponseEntity<?> scenario(@PathVariable String app, @PathVariable String product,
                                      @RequestParam String path) {
        ProductModel model = products.load(app, product).orElse(null);
        if (model == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "Ürün bulunamadı: " + app + "/" + product));
        }
        ScenarioStore.Stored stored = products.scenario(app, product, path).orElse(null);
        if (stored == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "Senaryo bulunamadı: " + path));
        }
        return ResponseEntity.ok(detail(stored, model));
    }

    /**
     * The row, plus the scenario both ways: its fields as stored (the detail
     * view edits these) and composed into one .feature (what a run is given).
     * {@code version} goes back with an edit, so a save over someone else's
     * newer one is refused instead of silently overwriting it.
     */
    private Map<String, Object> detail(ScenarioStore.Stored stored, ProductModel model) {
        List<SourceSnapshot.SourceChange> changes = products.changes(stored, model);
        ScenarioFields fields = stored.fields();
        String feature = FeatureComposer.compose(fields);
        ScenarioCard card = ScenarioLibrary.parse(stored.path(), stored.flow(), feature);

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("title", fields.title());
        doc.put("rules", fields.rules());
        doc.put("preconditions", fields.preconditions());
        doc.put("steps", fields.steps());
        doc.put("expected", fields.expected());

        Map<String, Object> body = new LinkedHashMap<>(row(card, model, changes));
        body.put("preconditions", fields.preconditions());
        body.put("path", stored.path());
        body.put("feature", feature);
        body.put("gherkin", fields.gherkin());
        body.put("doc", doc);
        body.put("version", stored.version());
        body.put("changes", changes.stream().map(change -> change(change, fields)).toList());
        return body;
    }

    /**
     * One source that moved since the scenario was reviewed, with the lines
     * of the scenario worth looking at.
     *
     * Those line numbers are pointed at, never rewritten. The same difference
     * means two opposite things -- the application changed and the rule
     * caught up, or the rule was written down wrong and has been corrected --
     * and only the second one must leave the run RED, over a defect that is
     * still there. A machine cannot tell them apart; it can say where to look.
     */
    private static Map<String, Object> change(SourceSnapshot.SourceChange change,
                                              ScenarioFields fields) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", change.kind());
        row.put("id", change.id());
        row.put("state", change.state());
        row.put("title", change.title());
        row.put("fields", change.fields().stream().map(field -> Map.of(
                "field", field.field(),
                "before", field.before(),
                "after", field.after())).toList());
        row.put("removed", change.removed());
        row.put("added", change.added());
        row.put("mentions", mentions(change.removed(), fields));
        return row;
    }

    /** Lines of the scenario that still carry a value the rule has dropped. */
    private static List<Map<String, Object>> mentions(List<String> removed, ScenarioFields fields) {
        if (removed.isEmpty()) {
            return List.of();
        }
        // LinkedHashMap, not Map.of: these are rendered in order to a person,
        // and Map.of leaves iteration order unspecified.
        Map<String, List<String>> parts = new LinkedHashMap<>();
        parts.put("Ön koşul", fields.preconditions());
        parts.put("Adımlar", fields.steps());
        parts.put("Beklenen", fields.expected());
        parts.put("Gherkin", List.of(fields.gherkin().split("\\R")));

        List<Map<String, Object>> found = new ArrayList<>();
        for (String value : removed) {
            if (value.isBlank()) {
                continue;
            }
            for (Map.Entry<String, List<String>> part : parts.entrySet()) {
                for (String line : part.getValue()) {
                    if (line.contains(value)) {
                        found.add(Map.of("value", value, "where", part.getKey(), "line", line.trim()));
                    }
                }
            }
        }
        return found;
    }

    /**
     * The Açıklama column: the steps and the expectation, in the layout the
     * column always had ("Adımlar: - … Beklenen: - …"). The preconditions are
     * NOT repeated here; they have their own column.
     */
    private static String description(ScenarioFields fields) {
        StringBuilder out = new StringBuilder();
        if (!fields.steps().isEmpty()) {
            out.append("Adımlar:");
            fields.steps().forEach(step -> out.append(" - ").append(step));
        }
        if (!fields.expected().isEmpty()) {
            out.append(out.isEmpty() ? "" : " ").append("Beklenen:");
            fields.expected().forEach(item -> out.append(" - ").append(item));
        }
        return out.toString();
    }

    /**
     * Saves an edit made in the scenario detail view -- to the database, not
     * to a file -- then answers exactly as the GET does.
     *
     * 409 when the scenario changed since the editor opened it: the analyst
     * reloads and redoes the edit, rather than one of two saves vanishing.
     */
    @PutMapping("/{app}/{product}/scenario")
    public ResponseEntity<?> saveScenario(@PathVariable String app, @PathVariable String product,
                                          @RequestParam String path,
                                          @RequestBody ProductService.ScenarioEdit body) {
        ProductService.SaveResult result = products.saveScenario(app, product, path, body);
        if (result.conflict()) {
            return ResponseEntity.status(409).body(Map.of("error", result.refused()));
        }
        if (result.refused() != null) {
            return ResponseEntity.badRequest().body(Map.of("error", result.refused()));
        }
        ProductModel model = products.load(app, product).orElse(null);
        if (model == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "Ürün bulunamadı: " + app + "/" + product));
        }
        return ResponseEntity.ok(detail(result.saved(), model));
    }

    /**
     * Writes every scenario in the database out as .feature files under
     * products/, so they can be committed. Never deletes: a file the database
     * no longer has is listed in {@code notInDatabase} for a person to decide.
     */
    @PostMapping("/export")
    public ResponseEntity<?> export() {
        ProductService.Exported exported = products.export();
        return ResponseEntity.ok(Map.of(
                "written", exported.written(),
                "unchanged", exported.unchanged(),
                "notInDatabase", exported.notInDatabase()));
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
            row.put("name", product.screenName(flow));
            row.put("catalog", knownCatalogs.contains(prefix + flow) ? prefix + flow : null);
            row.put("declared", product.flows().contains(flow));
            row.put("scenarioCount", cards.stream().filter(c -> flow.equals(c.flow())).count());
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, Object> row(ScenarioCard card, ProductModel product,
                                           List<SourceSnapshot.SourceChange> changes) {
        // The ids whose text reads differently now than when this scenario was
        // last reviewed. A rule that was DELETED is not among them on purpose:
        // `known=false` already reports that, and two complaints about one
        // fact is one too many.
        Set<String> staleRules = changed(changes, SourceSnapshot.Source.RULE);
        Set<String> staleNeeds = changed(changes, SourceSnapshot.Source.ALIAS);

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
            chip.put("stale", staleRules.contains(id));
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
            need.put("stale", staleNeeds.contains(id));
            needs.add(need);
        }
        row.put("needs", needs);
        row.put("needsNone", card.needsNone());
        // UNDECLARED is not a data problem, it is an unfinished scenario: the
        // preconditions were never written down, so nobody can say whether it
        // runs. @needs:none is how an author says "checked, there are none".
        row.put("readiness", card.needsUndeclared() ? "UNDECLARED" : worst.name());
        row.put("stale", !changes.isEmpty());
        return row;
    }

    /** Ids of one kind whose text moved since the scenario was reviewed. */
    private static Set<String> changed(List<SourceSnapshot.SourceChange> changes, String kind) {
        return changes.stream()
                .filter(change -> kind.equals(change.kind()))
                .filter(change -> SourceSnapshot.SourceChange.CHANGED.equals(change.state()))
                .map(SourceSnapshot.SourceChange::id)
                .collect(Collectors.toSet());
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
