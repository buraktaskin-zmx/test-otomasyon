package orchestrator.explore;

import orchestrator.explore.CachedSteps.CachedStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The step cache of "AI ile çalıştır", end to end against a fake browser with
 * the shapes tape 2628c4fd recorded (the same pages {@link StepRunnerTest}
 * uses): a passed walk is written down as named steps, and the next run
 * carries them out in code — with new references, a redrawn panel, and the
 * expectations written under the steps checked where they happen.
 */
class StepCacheTest {

    @TempDir
    Path dir;

    private static String page(String yaml) {
        return "### Page\n- Page URL: x\n### Snapshot\n```yaml\n" + yaml + "\n```";
    }

    private static final String LIST = page("""
            - generic [ref=e1]:
              - heading "Firma Fatura İşlemleri" [level=2] [ref=e2]
              - generic [ref=e3]:
                - generic [ref=e4]: Firma Seçimi
                - combobox [ref=e10] [cursor=pointer]:
                  - textbox "Seçiniz" [ref=e12]
                - combobox [aria-hidden] [ref=e11]""");

    private static final String OPEN = page("""
            - generic [ref=e1]:
              - heading "Firma Fatura İşlemleri" [level=2] [ref=e2]
              - generic [ref=e3]:
                - generic [ref=e4]: Firma Seçimi
                - combobox [expanded] [ref=e10] [cursor=pointer]:
                  - textbox "Seçiniz" [ref=e12]
                  - listbox [expanded] [ref=e20]:
                    - option "Seçiniz" [selected] [ref=e21]
                    - option "APPLE A.Ş." [ref=e22]
                    - option "ASAŞ ALÜMİNYUM SAN." [ref=e23]
                - combobox [aria-hidden] [ref=e11]""");

    private static String grid(String checkedRow, String panelRef) {
        return page("""
                - generic [ref=e1]:
                  - heading "Firma Fatura İşlemleri" [level=2] [ref=e2]
                  - generic [ref=e3]:
                    - generic [ref=e4]: Firma Seçimi
                    - combobox [ref=e30] [cursor=pointer]:
                      - textbox "ASAŞ ALÜMİNYUM SAN." [ref=e31]
                    - combobox [aria-hidden] [ref=e11]
                  - table [ref=e39]:
                    - rowgroup [ref=e38]:
                      - row [ref=e40] [cursor=pointer]:
                        - cell [ref=e41]:
                          - radio %s[ref=e42]
                        - cell "HLLSRC03" [ref=e43]
                        - cell "Ödenecek" [ref=e44]
                      - row [ref=e50] [cursor=pointer]:
                        - cell [ref=e51]:
                          - radio [ref=e52]
                        - cell "HLLSRC12" [ref=e53]
                        - cell "Ödendi" [ref=e54]
                  - generic [ref=e59]:
                    - heading "İŞLEMLER" [level=1] [ref=e58]
                    - link "Fatura İptal Et" [ref=%s] [cursor=pointer]"""
                .formatted("e40".equals(checkedRow) ? "[checked] " : "", panelRef));
    }

    private static final String CANCEL = page("""
            - generic [ref=e1]:
              - heading "Fatura İptal" [level=2] [ref=e80]
              - button "Evet" [ref=e81]
              - button "Geri" [ref=e82]""");

    private static final String SCENARIO = """
            1. "Firma Seçimi" listesinden "ASAŞ ALÜMİNYUM SAN." seç
            2. Ödeme Durumu "Ödenecek" olan ilk satırı seç
            3. İşlemler panelindeki "Fatura İptal Et" bağlantısına tıkla
               Beklenen (bu adımdan hemen sonra kontrol et): "Fatura İptal" ekranı açılır
            4. Açılan ekranda "Geri" düğmesine tıkla""";

    private Map<String, String> next;
    private String current;
    private final List<String> clicked = new ArrayList<>();
    private ActionTape tape;
    private StepJournal journal;

    @BeforeEach
    void browser() {
        current = LIST;
        next = Map.of(
                "e10", OPEN,
                "e23", grid("", "e60"),
                "e40", grid("e40", "e61"),
                "e61", CANCEL,
                "e82", grid("e40", "e62"));
    }

    private static final Pattern TARGET = Pattern.compile("\"target\"\\s*:\\s*\"([^\"]+)\"");

    private ToolCallback fake(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                if ("browser_snapshot".equals(name)) {
                    return current;
                }
                Matcher target = TARGET.matcher(input);
                String ref = target.find() ? target.group(1) : "";
                clicked.add(ref);
                String after = next.get(ref);
                if (after != null) {
                    current = after;
                }
                return "### Ran Playwright code\n```js\nawait page.locator('aria-ref=" + ref + "').click();\n```";
            }
        };
    }

    /** A StepRunner on a fresh tape, wrapped the way ExploreController wraps it for a walk. */
    private StepRunner runner(String scenario) {
        tape = ActionTape.open(dir, "explore", scenario, "", "", List.of());
        tape.record("browser_snapshot", "{}", ActionTape.Outcome.OK, current);
        journal = new StepJournal();
        AiRunPolicy policy = new AiRunPolicy(fake("browser_snapshot"), Duration.ZERO, 0, 220, 12_000, () -> 0L);
        StepRunner runner = new StepRunner().journal(journal).checkpoints(StepExpectations.checkpoints(scenario));
        Object[] wrapped = BudgetedTool.wrap(
                List.of(fake("browser_click"), fake("browser_press_sequentially"), runner),
                99, null, List.of(), new LinkedHashSet<>(), scenario, new AtomicBoolean(), tape,
                new RunVerdict(), policy);
        runner.bind(wrapped, tape);
        return runner;
    }

    private static final String ALL_FOUR = """
            {"steps":[
              {"action":"select","name":"Firma Seçimi","value":"ASAŞ ALÜMİNYUM SAN."},
              {"action":"row","name":"Ödenecek"},
              {"action":"click","name":"Fatura İptal Et"},
              {"action":"click","name":"Geri"}]}""";

    /* --------------------------- expectations ---------------------------- */

    @Test
    @DisplayName("an expectation under a step belongs to that step, in either shape a UI sends it")
    void expectationsAttachToTheirStep() {
        List<StepExpectations.Step> steps = StepExpectations.parse(SCENARIO);
        assertThat(steps).hasSize(4);
        assertThat(steps.get(2).expected()).isEqualTo("\"Fatura İptal\" ekranı açılır");
        assertThat(steps.get(3).expected()).isNull();

        // An older UI numbered the expectation like a step.
        List<StepExpectations.Step> old = StepExpectations.parse("""
                1. "Fatura İptal Et" bağlantısına tıkla
                2. Beklenen: "Fatura İptal" ekranı açılır
                3. "Geri" düğmesine tıkla""");
        assertThat(old).extracting(StepExpectations.Step::number).containsExactly(1, 2);
        assertThat(old.get(0).expected()).isEqualTo("\"Fatura İptal\" ekranı açılır");
        assertThat(ClaimCheck.pressesAskedFor("""
                1. "Fatura İptal Et" bağlantısına tıkla
                2. Beklenen: "Tamam" düğmesine tıkla uyarısı görünür""")).containsExactly("Fatura İptal Et");
    }

    @Test
    @DisplayName("the final Beklenen section is not mistaken for a step's expectation")
    void finalSectionIsNotAStepExpectation() {
        List<StepExpectations.Step> steps = StepExpectations.parse("""
                Adımlar:
                1. "Geri" düğmesine tıkla

                Beklenen:
                - "Firma Fatura İşlemleri" listesi görünür""");
        assertThat(steps).singleElement().extracting(StepExpectations.Step::expected).isNull();
    }

    @Test
    @DisplayName("do_steps stops right after a step that carries an expectation, so its page can be checked")
    void batchStopsAtACheckpoint() {
        String out = runner(SCENARIO).acceptJson(ALL_FOUR);

        assertThat(clicked).containsExactly("e10", "e23", "e40", "e61");
        assertThat(out).contains("3. click \"Fatura İptal Et\" → tamam")
                .contains("BEKLENEN KONTROLÜ").contains("\"Fatura İptal\" ekranı açılır")
                .contains("DURDU").doesNotContain("4. click");
    }

    /* ------------------------------ writing ------------------------------ */

    @Test
    @DisplayName("a passed walk is written down by NAME: row \"Ödenecek\", not the reference that was clicked")
    void walkBecomesNamedSteps() {
        runner("").acceptJson(ALL_FOUR);

        CachedSteps.Extracted extracted = CachedSteps.fromWalk(tape.calls(), journal, LocalDate.of(2026, 11, 12));

        assertThat(extracted.usable()).isTrue();
        assertThat(extracted.steps()).extracting(CachedSteps::describe).containsExactly(
                "select \"Firma Seçimi\" = \"ASAŞ ALÜMİNYUM SAN.\"",
                "row \"Ödenecek\"",
                "click \"Fatura İptal Et\"",
                "click \"Geri\"");
        List<CachedStep> steps = new ArrayList<>(extracted.steps());
        assertThat(CachedSteps.align(steps, SCENARIO)).isEmpty();
        assertThat(steps.get(2).expected()).isEqualTo("\"Fatura İptal\" ekranı açılır");
        assertThat(CachedSteps.fromJson(CachedSteps.toJson(steps))).isEqualTo(steps);
    }

    @Test
    @DisplayName("the agent's single calls are resolved against the page they were made on")
    void singleCallsAreResolved() {
        ActionTape walk = ActionTape.open(dir, "explore", "", "", "", List.of());
        walk.record("browser_snapshot", "{}", ActionTape.Outcome.OK, LIST);
        walk.record("browser_click", "{\"target\":\"e10\"}", ActionTape.Outcome.OK, "ok");
        walk.record("browser_snapshot", "{}", ActionTape.Outcome.OK, OPEN);
        walk.record("browser_press_sequentially", "{\"text\":\"ASAŞ\"}", ActionTape.Outcome.OK, "ok");
        walk.record("browser_click", "{\"target\":\"e23\"}", ActionTape.Outcome.OK, "ok");
        walk.record("browser_snapshot", "{}", ActionTape.Outcome.OK, grid("", "e60"));
        walk.record("browser_click", "{\"target\":\"e99\"}", ActionTape.Outcome.REFUSED, "REFUSED");
        walk.record("browser_click", "{\"target\":\"e42\"}", ActionTape.Outcome.OK, "ok");
        walk.record("browser_snapshot", "{}", ActionTape.Outcome.OK, grid("e40", "e61"));
        walk.record("browser_click", "{\"target\":\"e61\"}", ActionTape.Outcome.OK, "ok");

        CachedSteps.Extracted extracted = CachedSteps.fromWalk(walk.calls(), new StepJournal(), LocalDate.now());

        assertThat(extracted.steps()).extracting(CachedSteps::describe).containsExactly(
                "select \"Firma Seçimi\" = \"ASAŞ ALÜMİNYUM SAN.\"",
                "row \"HLLSRC03\"",
                "click \"Fatura İptal Et\"");
    }

    @Test
    @DisplayName("a call that cannot be replayed makes the walk uncacheable, with the reason")
    void unreplayableCallRefusesTheCache() {
        ActionTape walk = ActionTape.open(dir, "explore", "", "", "", List.of());
        walk.record("browser_snapshot", "{}", ActionTape.Outcome.OK, CANCEL);
        walk.record("browser_handle_dialog", "{\"accept\":true}", ActionTape.Outcome.OK, "ok");

        CachedSteps.Extracted extracted = CachedSteps.fromWalk(walk.calls(), new StepJournal(), LocalDate.now());

        assertThat(extracted.usable()).isFalse();
        assertThat(extracted.refusal()).contains("browser_handle_dialog");
    }

    @Test
    @DisplayName("a cached date keeps its distance from the day it was recorded, so it never rots")
    void dateKeepsItsOffset() {
        ActionTape walk = ActionTape.open(dir, "explore", "", "", "", List.of());
        walk.record("browser_snapshot", "{}", ActionTape.Outcome.OK, page("""
                - generic [ref=e1]:
                  - generic [ref=e5]: Başlangıç Vadesi
                  - textbox [ref=e6]"""));
        walk.record("set_date", "{\"target\":\"e6\",\"date\":\"15/11/2026\"}", ActionTape.Outcome.OK, "Set to 15/11/2026");

        CachedStep step = CachedSteps.fromWalk(walk.calls(), new StepJournal(), LocalDate.of(2026, 11, 12))
                .steps().get(0);

        assertThat(step.dayOffset()).isEqualTo(3);
        assertThat(step.asStep(LocalDate.of(2026, 12, 1)).value()).isEqualTo("04/12/2026");
    }

    /* ------------------------------ replaying ---------------------------- */

    private List<CachedStep> cached(String scenario) {
        List<CachedStep> steps = new ArrayList<>(List.of(
                new CachedStep("select", "Firma Seçimi", "ASAŞ ALÜMİNYUM SAN.", null, null),
                new CachedStep("row", "Ödenecek", null, null, null),
                new CachedStep("click", "Fatura İptal Et", null, null, null),
                new CachedStep("click", "Geri", null, null, null)));
        CachedSteps.align(steps, scenario);
        return steps;
    }

    @Test
    @DisplayName("the cached steps run in code against new references; a quoted expectation is checked on its own page")
    void replayRunsAndChecks() {
        CachedWalk.Result walk = new CachedWalk(runner(SCENARIO)).run(cached(SCENARIO), LocalDate.now(), raw -> raw);

        assertThat(walk.complete()).isTrue();
        assertThat(clicked).containsExactly("e10", "e23", "e40", "e61", "e82");
        assertThat(walk.log()).anyMatch(line -> line.startsWith("3/4 click \"Fatura İptal Et\" → tamam"))
                .anyMatch(line -> line.contains("Fatura İptal\" ekranı açılır") && line.contains("görüldü"));
        // The replay is in the journal, so a walk the agent finishes after it can be cached whole.
        assertThat(CachedSteps.fromWalk(tape.calls(), journal, LocalDate.now()).steps()).hasSize(4);
        // And ClaimCheck reads the replayed clicks off the tape like the agent's.
        assertThat(ClaimCheck.unclicked(SCENARIO, tape.calls(), List.of())).isEmpty();
    }

    @Test
    @DisplayName("a quoted expectation the page does not show stops the replay there, for the agent to take over")
    void missingExpectationStops() {
        String scenario = SCENARIO.replace("\"Fatura İptal\" ekranı açılır", "\"Kayıt silindi\" mesajı görünür");

        CachedWalk.Result walk = new CachedWalk(runner(scenario)).run(cached(scenario), LocalDate.now(), raw -> raw);

        assertThat(walk.complete()).isFalse();
        assertThat(walk.stoppedAt()).isEqualTo(3);
        assertThat(walk.reason()).contains("beklenen görülmedi").contains("Kayıt silindi");
        assertThat(clicked).doesNotContain("e82");
        assertThat(CachedWalk.takeoverNote(walk)).contains("3. adımda durdu").contains("TEKRARLAMA");
    }

    @Test
    @DisplayName("an unquoted expectation keeps its page for the one verdict turn")
    void unquotedExpectationIsKeptForTheVerdict() {
        String scenario = SCENARIO.replace("\"Fatura İptal\" ekranı açılır", "iptal ekranı açılır");

        CachedWalk.Result walk = new CachedWalk(runner(scenario)).run(cached(scenario), LocalDate.now(), raw -> raw);

        assertThat(walk.complete()).isTrue();
        assertThat(walk.checkpoints()).singleElement().satisfies(point -> {
            assertThat(point.step()).isEqualTo(3);
            assertThat(point.page()).contains("heading \"Fatura İptal\"");
        });
        assertThat(CachedWalk.verdictTurn(scenario, walk, List.of(), walk.lastPage()))
                .contains("KODLA yapıldı").contains("3. adımdan HEMEN SONRAKİ ekran. Beklenen: iptal ekranı açılır");
    }

    @Test
    @DisplayName("a row that is no longer there stops the replay; nothing is guessed")
    void missingRowStops() {
        List<CachedStep> steps = cached(SCENARIO);
        steps.set(1, new CachedStep("row", "HLLSRC99", null, null, null));

        CachedWalk.Result walk = new CachedWalk(runner(SCENARIO)).run(steps, LocalDate.now(), raw -> raw);

        assertThat(walk.stoppedAt()).isEqualTo(2);
        assertThat(walk.reason()).contains("HLLSRC99");
        assertThat(walk.lastPage()).contains("Fatura İptal Et");
    }

    @Test
    @DisplayName("an edited scenario no longer matches its cache")
    void hashFollowsTheText() {
        assertThat(AiRunCache.sha256(SCENARIO)).isEqualTo(AiRunCache.sha256(SCENARIO + "\n"))
                .isNotEqualTo(AiRunCache.sha256(SCENARIO.replace("Geri", "Kapat")));
    }
}
