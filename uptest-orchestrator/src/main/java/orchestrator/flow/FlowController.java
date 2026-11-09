package orchestrator.flow;

import orchestrator.flow.FlowModel.Flow;
import orchestrator.flow.FlowModel.Intent;
import orchestrator.flow.FlowModel.Plan;
import orchestrator.llm.LoginStep;
import orchestrator.product.ProductModel;
import orchestrator.product.ProductService;
import orchestrator.product.Rule;
import orchestrator.vocabulary.Vocabulary;
import orchestrator.vocabulary.VocabularyService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The drawn flow: read it, save it, attach screenshots, have the screens read.
 *
 * <p>The vision model is optional. {@link ScreenReader} only exists when
 * {@code uptest.vision.enabled} is true, so it is injected through an
 * {@link ObjectProvider} and only the read endpoint refuses — drawing the
 * tree, storing screenshots and everything downstream of a hand-written
 * catalog keep working on an install with no vision endpoint.
 */
@RestController
@RequestMapping("/api/v1/products/{app}/{product}/flow")
public class FlowController {

    private final FlowStore store;
    private final ObjectProvider<ScreenReader> readers;
    private final FlowPlanner planner;
    private final FlowSuggestService suggester;
    private final ProductService products;
    private final VocabularyService vocabularies;

    public FlowController(FlowStore store, ObjectProvider<ScreenReader> readers,
                          FlowPlanner planner, FlowSuggestService suggester,
                          ProductService products, VocabularyService vocabularies) {
        this.store = store;
        this.readers = readers;
        this.planner = planner;
        this.suggester = suggester;
        this.products = products;
        this.vocabularies = vocabularies;
    }

    /** The drawing, which nodes carry a screenshot, what was read, and the plan. */
    @GetMapping
    public Map<String, Object> flow(@PathVariable String app, @PathVariable String product) {
        Flow flow = store.read(app, product);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flow", flow);
        body.put("screenshots", store.screenshotPresence(app, product, flow));
        body.put("reads", store.reads(app, product, flow));
        body.put("plan", store.plan(app, product));
        body.put("visionReady", readers.getIfAvailable() != null);
        return body;
    }

    @PutMapping
    public ResponseEntity<?> save(@PathVariable String app, @PathVariable String product,
                                  @RequestBody Flow flow) {
        String refused = store.write(app, product, flow == null ? Flow.empty() : flow);
        return refused == null
                ? ResponseEntity.ok(Map.of("saved", true))
                : ResponseEntity.badRequest().body(Map.of("error", refused));
    }

    /** {@code dataUrl}: {@code data:image/png;base64,…}, as the clipboard gives it. */
    public record ScreenshotBody(String dataUrl) {
    }

    @PutMapping("/screens/{nodeId}")
    public ResponseEntity<?> putScreenshot(@PathVariable String app, @PathVariable String product,
                                           @PathVariable String nodeId,
                                           @RequestBody ScreenshotBody body) {
        String refused = store.writeScreenshot(app, product, nodeId,
                body == null ? null : body.dataUrl());
        return refused == null
                ? ResponseEntity.ok(Map.of("saved", true))
                : ResponseEntity.badRequest().body(Map.of("error", refused));
    }

    /**
     * The image itself, as bytes.
     *
     * Served rather than embedded in the flow JSON: four screenshots would be
     * megabytes of base64 on every panel open, re-parsed each time, where an
     * {@code <img src>} is cached by the browser and fetched only for the
     * nodes actually on screen.
     */
    @GetMapping("/screens/{nodeId}")
    public ResponseEntity<byte[]> getScreenshot(@PathVariable String app, @PathVariable String product,
                                                @PathVariable String nodeId) {
        return store.screenshot(app, product, nodeId)
                .map(png -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_PNG)
                        // Short and private: the analyst replaces a screenshot
                        // and expects to see the new one, not yesterday's.
                        .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePrivate())
                        .body(png))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/screens/{nodeId}")
    public ResponseEntity<?> deleteScreenshot(@PathVariable String app, @PathVariable String product,
                                              @PathVariable String nodeId) {
        store.deleteScreenshot(app, product, nodeId);
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    /**
     * Stage 1, on demand: read ONE screen again.
     *
     * There is no "read everything" button any more — planning reads what it
     * is missing, below. This endpoint is what remains for the case the
     * automatic read cannot serve: a screenshot that was replaced, or a node
     * note added after the fact, where the stored draft is stale and only the
     * analyst knows it.
     */
    @PostMapping("/read")
    public ResponseEntity<?> read(@PathVariable String app, @PathVariable String product,
                                  @RequestParam String nodeId) {
        ScreenReader reader = readers.getIfAvailable();
        if (reader == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "Görsel model kapalı",
                    "detail", "uptest.vision.enabled=true ve uptest.vision.* ayarları gerekli."));
        }
        Flow flow = store.read(app, product);
        return reader.readNode(app, product, flow, nodeId)
                .<ResponseEntity<?>>map(read -> ResponseEntity.ok(Map.of("screens", List.of(read))))
                .orElseGet(() -> ResponseEntity.badRequest().body(Map.of(
                        "error", "Çizimde böyle bir ekran yok: " + nodeId)));
    }

    /* ------------------------- plan and generate ------------------------- */

    /** {@code ruleIds} empty = every rule the product has. */
    public record PlanRequest(Integer count, List<String> ruleIds) {
    }

    /** The plan, plus whatever reading the screens produced on the way to it. */
    public record PlanResponse(List<Intent> scenarios, List<String> notes,
                               List<ScreenReader.Read> screens) {
    }

    /**
     * Stage 3: which scenarios to write. One cheap text call, so the analyst
     * can delete and add lines before generation is spent.
     *
     * <p><b>It reads the unread screens first.</b> There used to be an
     * "Ekranları Oku" button and it was a step for nothing: reading is not a
     * decision an analyst makes, it is what planning needs in order to see
     * field names at all, and a plan made without it names no field on any
     * screen. Only the screens with no stored draft are read, so pressing
     * Planla again while editing the plan costs no vision tokens; a screen
     * whose draft is stale is re-read per node, from its own box.
     */
    @PostMapping("/plan")
    public ResponseEntity<?> plan(@PathVariable String app, @PathVariable String product,
                                  @RequestBody(required = false) PlanRequest body) {
        Flow flow = store.read(app, product);
        if (flow.nodes().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Önce akışı çizin."));
        }
        int count = body == null || body.count() == null ? DEFAULT_COUNT : body.count();
        if (count < 1 || count > MAX_COUNT) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Senaryo sayısı 1 ile " + MAX_COUNT + " arasında olmalı."));
        }
        ProductModel model = products.load(app, product).orElse(null);
        List<Rule> rules = FlowSuggestService.rulesFor(model,
                body == null ? List.of() : body.ruleIds());

        ScreenReader reader = readers.getIfAvailable();
        List<ScreenReader.Read> reads = reader == null
                ? List.of()
                : reader.readAll(app, product, flow, true);

        try {
            Plan plan = planner.plan(app, product, flow, model, rules, count);
            List<String> notes = new ArrayList<>(plan.notes());
            // A screen that would not read is named here rather than left to
            // be noticed as a missing field thirty scenarios later.
            reads.stream().filter(read -> read.error() != null).forEach(read ->
                    notes.add("\"" + read.title() + "\" okunamadı: " + read.error()));
            return ResponseEntity.ok(new PlanResponse(plan.scenarios(), notes, reads));
        } catch (RuntimeException failed) {
            return ResponseEntity.status(502).body(Map.of(
                    "error", "Plan üretilemedi", "detail", String.valueOf(failed.getMessage())));
        }
    }

    /** The analyst's edited plan, saved before generation reads it back. */
    @PutMapping("/plan")
    public ResponseEntity<?> savePlan(@PathVariable String app, @PathVariable String product,
                                      @RequestBody Plan plan) {
        store.writePlan(app, product, plan == null ? Plan.empty() : plan);
        return ResponseEntity.ok(Map.of("saved", true));
    }

    /** {@code cif}/{@code userid}: the login every generated scenario runs under. */
    public record SuggestRequest(String cif, String userid, List<String> ruleIds) {
    }

    /**
     * Stage 5: write every scenario in the saved plan.
     *
     * A CIF is required, for the reason the scenario batch already records:
     * these are N separate model calls, and a blank cif makes each invent its
     * own customer number.
     */
    @PostMapping("/suggest")
    public ResponseEntity<?> suggest(@PathVariable String app, @PathVariable String product,
                                     @RequestBody(required = false) SuggestRequest body) {
        Flow flow = store.read(app, product);
        Plan plan = store.plan(app, product);
        if (plan.scenarios().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Plan boş — önce \"Senaryoları Planla\"."));
        }
        Optional<Vocabulary> vocabulary = vocabularies.get();
        if (vocabulary.isEmpty()) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "Executor'a ulaşılamadı, adım listesi bilinmiyor."));
        }
        String loginStep = LoginStep.forApp(app, body == null ? null : body.cif(),
                body == null ? null : body.userid());
        if (loginStep == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Üretim için test kullanıcısı (CIF) zorunlu",
                    "hint", "Ayrı çağrılarla üretilen senaryolar, CIF verilmezse her biri"
                            + " kendi müşteri numarasını uydurur."));
        }
        ProductModel model = products.load(app, product).orElse(null);
        List<Rule> rules = FlowSuggestService.rulesFor(model,
                body == null ? List.of() : body.ruleIds());
        String testData = testData(model);
        try {
            return ResponseEntity.ok(suggester.write(app, product, flow, plan.scenarios(),
                    vocabulary.get(), model, rules, loginStep, testData));
        } catch (RuntimeException failed) {
            return ResponseEntity.status(502).body(Map.of(
                    "error", "Senaryolar üretilemedi", "detail", String.valueOf(failed.getMessage())));
        }
    }

    /**
     * The test data as the model may read it: what exists in the environment,
     * and for anything not yet supplied, that it is NOT available.
     *
     * An alias nobody has provided is written down as missing on purpose. The
     * alternative — leaving it out — reads as "no constraint" and the model
     * invents an invoice number; saying "eksik" makes it write the scenario
     * without that step, or say so in the precondition.
     */
    private static String testData(ProductModel model) {
        if (model == null || model.aliases().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        model.aliases().forEach(alias -> {
            sb.append("- ").append(alias.id());
            if (alias.label() != null && !alias.label().isBlank()) {
                sb.append(" (").append(alias.label()).append(")");
            }
            if (alias.value() != null && !alias.value().isBlank()) {
                sb.append(" = ").append(alias.value());
            }
            if ("eksik".equalsIgnoreCase(alias.how())) {
                sb.append("  [HENÜZ YOK — bu veriye dayanan adım yazma]");
            } else if (alias.blockedBy() != null && !alias.blockedBy().isBlank()) {
                sb.append("  [KULLANILAMAZ: ").append(alias.blockedBy()).append("]");
            }
            if (alias.description() != null && !alias.description().isBlank()) {
                sb.append("\n    ").append(alias.description().replaceAll("\\s+", " ").trim());
            }
            sb.append('\n');
        });
        return sb.toString();
    }

    private static final int DEFAULT_COUNT = 25;

    /**
     * Past this the plan is longer than anyone reads and the generation is
     * tens of minutes. Not {@code llm.max-scenarios}, which caps a
     * single-screen batch where every extra scenario is a near-duplicate; a
     * flow genuinely has more distinct things to test.
     */
    private static final int MAX_COUNT = 40;
}
