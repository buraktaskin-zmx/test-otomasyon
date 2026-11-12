package orchestrator.explore;

import orchestrator.explore.ReplayCalls.ReplayCall;
import orchestrator.explore.ReplayCalls.Selector;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "Hızlı çalıştır" and the expectations written under a step: a passed walk
 * is written down as tool calls with page-independent targets, and sent again
 * without reading the page to find anything.
 */
class StepCacheTest {

    @TempDir
    Path dir;

    private static String page(String yaml) {
        return "### Page\n- Page URL: x\n### Snapshot\n```yaml\n" + yaml + "\n```";
    }

    private static String ran(String code) {
        return "### Ran Playwright code\n```js\n" + code + "\n```\n### Page\n- Page URL: https://x/ngc";
    }

    private static final String SCENARIO = """
            1. "Firma Seçimi" listesinden "ASAŞ ALÜMİNYUM SAN." seç
            2. Ödeme Durumu "Ödenecek" olan ilk satırı seç
            3. İşlemler panelindeki "Fatura İptal Et" bağlantısına tıkla
               Beklenen (bu adımdan hemen sonra kontrol et): "Fatura İptal" ekranı açılır
            4. Açılan ekranda "Geri" düğmesine tıkla""";

    /* --------------------------- expectations ---------------------------- */

    @Test
    @DisplayName("an expectation under a step belongs to that step, in either shape a UI sends it")
    void expectationsAttachToTheirStep() {
        List<StepExpectations.Step> steps = StepExpectations.parse(SCENARIO);
        assertThat(steps).hasSize(4);
        assertThat(steps.get(2).expected()).isEqualTo("\"Fatura İptal\" ekranı açılır");
        assertThat(steps.get(3).expected()).isNull();

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

    /* ----------------------------- do_steps ------------------------------ */

    private static final String CANCEL_LINK = page("""
            - generic [ref=e1]:
              - heading "Firma Fatura İşlemleri" [level=2] [ref=e2]
              - link "Fatura İptal Et" [ref=e61]""");

    private static final String CANCEL = page("""
            - generic [ref=e1]:
              - heading "Fatura İptal" [level=2] [ref=e80]
              - button "Geri" [ref=e82]""");

    @Test
    @DisplayName("do_steps stops right after a step that carries an expectation, so its page can be checked")
    void batchStopsAtACheckpoint() {
        List<String> clicked = new ArrayList<>();
        String[] current = {CANCEL_LINK};
        Map<String, String> next = Map.of("e61", CANCEL);
        ToolCallback snapshot = tool("browser_snapshot", input -> current[0]);
        ToolCallback click = tool("browser_click", input -> {
            String ref = arg(input, "target");
            clicked.add(ref);
            current[0] = next.getOrDefault(ref, current[0]);
            return ran("await page.getByRole('link', { name: 'x' }).click();");
        });
        ActionTape tape = ActionTape.open(dir, "explore", SCENARIO, "", "", List.of());
        tape.record("browser_snapshot", "{}", ActionTape.Outcome.OK, current[0]);
        AiRunPolicy policy = new AiRunPolicy(snapshot, Duration.ZERO, 0, 220, 12_000, () -> 0L);
        StepRunner runner = new StepRunner().checkpoints(StepExpectations.checkpoints(SCENARIO));
        Object[] wrapped = BudgetedTool.wrap(List.of(click, runner), 99, null, List.of(), new LinkedHashSet<>(),
                SCENARIO, new AtomicBoolean(), tape, new RunVerdict(), policy);
        runner.bind(wrapped, tape);

        String out = runner.acceptJson("""
                {"steps":[{"action":"click","name":"Fatura İptal Et"},{"action":"click","name":"Geri"}]}""");

        assertThat(clicked).containsExactly("e61");
        assertThat(out).contains("BEKLENEN KONTROLÜ").contains("\"Fatura İptal\" ekranı açılır")
                .contains("DURDU").doesNotContain("2. click");
    }

    /* --------------------------- the selectors --------------------------- */

    @Test
    @DisplayName("the Playwright line MCP echoed becomes the selector Playwright serialises it to")
    void locatorsBecomeSelectors() {
        assertThat(Selector.of("await page.getByRole('link', { name: 'Fatura İptal Et' }).click();").selector())
                .isEqualTo("internal:role=link[name=\"Fatura İptal Et\"i]");
        assertThat(Selector.of("await page.getByRole('button', { name: 'Geri', exact: true }).click();").selector())
                .isEqualTo("internal:role=button[name=\"Geri\"s]");
        assertThat(Selector.of("await page.getByRole('option', { name: 'ASAŞ ALÜMİNYUM SAN.' }).click();")
                .names()).containsExactly("ASAŞ ALÜMİNYUM SAN.");
        assertThat(Selector.of("await page.locator('#select2-firma-container').click();").selector())
                .isEqualTo("#select2-firma-container");
        assertThat(Selector.of("await page.getByLabel('Fatura No').fill('123');").selector())
                .isEqualTo("internal:label=\"Fatura No\"i");
        assertThat(Selector.of("await page.getByText('Detay', { exact: true }).first().click();").selector())
                .isEqualTo("internal:text=\"Detay\"s >> nth=0");
        assertThat(Selector.of("await page.getByRole('row', { name: 'HLLSRC03 Ödenecek' })"
                + ".getByRole('radio').check();").selector())
                .isEqualTo("internal:role=row[name=\"HLLSRC03 Ödenecek\"i] >> internal:role=radio");
        assertThat(Selector.of("await page.getByRole('heading', { name: 'Ödeme \\'Hızlı\\'', level: 2 }).click();")
                .selector()).isEqualTo("internal:role=heading[level=2][name=\"Ödeme 'Hızlı'\"i]");
    }

    @Test
    @DisplayName("a locator that only means something on one page load is refused, not guessed")
    void refLocatorsAreRefused() {
        assertThat(Selector.of("await page.locator('aria-ref=e42').click();").selector()).isNull();
        assertThat(Selector.of("await page.getByText(/Fatura/).click();").selector()).isNull();
        assertThat(Selector.of("").why()).contains("döndürmemiş");
    }

    /* ------------------------------ writing ------------------------------ */

    private ActionTape walk() {
        ActionTape tape = ActionTape.open(dir, "explore", SCENARIO, "", "", List.of());
        tape.record(AiRunPrelude.LOGIN_TOOL, "{}", ActionTape.Outcome.OK, "Giriş yapıldı");
        tape.record(AiRunPrelude.MENU_TOOL, "{}", ActionTape.Outcome.OK, "açıldı");
        tape.record("browser_snapshot", "{}", ActionTape.Outcome.OK, CANCEL_LINK);
        tape.record("browser_click", "{\"target\":\"e10\",\"element\":\"Firma Seçimi\"}", ActionTape.Outcome.OK,
                ran("await page.locator('#select2-firma-container').click();"));
        tape.record("browser_click", "{\"target\":\"e23\"}", ActionTape.Outcome.OK,
                ran("await page.getByRole('option', { name: 'ASAŞ ALÜMİNYUM SAN.' }).click();"));
        tape.record("browser_click", "{\"target\":\"e40\"}", ActionTape.Outcome.OK,
                ran("await page.getByRole('row', { name: 'HLLSRC03 Ödenecek' }).click();"));
        tape.record("browser_click", "{\"target\":\"e81\"}", ActionTape.Outcome.REFUSED, "REFUSED: evet");
        tape.record("browser_click", "{\"target\":\"e61\",\"element\":\"Fatura İptal Et\"}", ActionTape.Outcome.OK,
                ran("await page.getByRole('link', { name: 'Fatura İptal Et' }).click();"));
        tape.record("set_date", "{\"target\":\"e6\",\"date\":\"15/11/2026\"}", ActionTape.Outcome.OK,
                "Set to 15/11/2026. Clicking the day …\n" + ran("await page.getByRole('textbox', { name: 'Vade' }).click();"));
        tape.record("browser_click", "{\"target\":\"e82\"}", ActionTape.Outcome.OK,
                ran("await page.getByRole('button', { name: 'Geri' }).click();"));
        tape.record(RunVerdict.TOOL, "{}", ActionTape.Outcome.OK, "Sonuç kaydedildi");
        return tape;
    }

    @Test
    @DisplayName("a passed walk is written down as its tool calls, each with a target that does not depend on the page")
    void walkBecomesReplayCalls() {
        ReplayCalls.Extracted extracted = ReplayCalls.fromWalk(walk().calls(), LocalDate.of(2026, 11, 12));

        assertThat(extracted.usable()).as(extracted.refusal()).isTrue();
        List<ReplayCall> calls = new ArrayList<>(extracted.calls());
        assertThat(calls).extracting(ReplayCall::target).containsExactly(
                "#select2-firma-container",
                "internal:role=option[name=\"ASAŞ ALÜMİNYUM SAN.\"i]",
                "internal:role=row[name=\"HLLSRC03 Ödenecek\"i]",
                "internal:role=link[name=\"Fatura İptal Et\"i]",
                "internal:role=textbox[name=\"Vade\"i]",
                "internal:role=button[name=\"Geri\"i]");
        // The refused "Evet" is not part of the walk; the date keeps its distance from the recording day.
        assertThat(calls.get(4).dayOffset()).isEqualTo(3);
        assertThat(calls.get(4).dateFor(LocalDate.of(2026, 12, 1))).isEqualTo("04/12/2026");
        assertThat(calls.get(3).argsFor(LocalDate.now())).contains("\"target\":\"internal:role=link")
                .doesNotContain("e61");

        assertThat(ReplayCalls.align(calls, SCENARIO)).isEmpty();
        assertThat(calls.get(3).expected()).isEqualTo("\"Fatura İptal\" ekranı açılır");
        assertThat(ReplayCalls.fromJson(ReplayCalls.toJson(calls))).isEqualTo(calls);
        // A row written by the earlier, name-based cache reads as no cache at all.
        assertThat(ReplayCalls.fromJson("[{\"action\":\"click\",\"name\":\"Geri\"}]")).isEmpty();
    }

    @Test
    @DisplayName("a call whose target only lives on one page load makes the walk uncacheable, with the reason")
    void unreplayableWalkIsRefused() {
        ActionTape tape = ActionTape.open(dir, "explore", "", "", "", List.of());
        tape.record("browser_click", "{\"target\":\"e42\"}", ActionTape.Outcome.OK,
                ran("await page.locator('aria-ref=e42').click();"));

        ReplayCalls.Extracted extracted = ReplayCalls.fromWalk(tape.calls(), LocalDate.now());

        assertThat(extracted.usable()).isFalse();
        assertThat(extracted.refusal()).contains("sayfadan bağımsız");
    }

    /* ------------------------------ replaying ---------------------------- */

    private final List<String> sent = new ArrayList<>();
    private int snapshots;
    private String failOn;

    @BeforeEach
    void reset() {
        sent.clear();
        snapshots = 0;
        failOn = null;
    }

    private ToolCallback[] browser() {
        return new ToolCallback[]{
                tool("browser_click", input -> {
                    String target = arg(input, "target");
                    sent.add(target);
                    return target.equals(failOn)
                            ? "### Error\nError: locator.click: Timeout 5000ms exceeded waiting for " + target
                            : ran("await page.locator('x').click();");
                }),
                tool("browser_select_option", input -> "ok"),
                tool("browser_snapshot", input -> {
                    snapshots++;
                    return CANCEL;
                })};
    }

    private List<ReplayCall> recorded() {
        List<ReplayCall> calls = new ArrayList<>(ReplayCalls.fromWalk(walk().calls(), LocalDate.now()).calls());
        calls.removeIf(call -> "set_date".equals(call.tool()));
        ReplayCalls.align(calls, SCENARIO);
        return calls;
    }

    @Test
    @DisplayName("every call is sent as recorded; the page is read only at the checkpoints")
    void replaySendsEveryCall() {
        ActionTape tape = ActionTape.open(dir, "explore", "", "", "", List.of());
        FastReplay.Result walk = new FastReplay(browser(), tape, Duration.ZERO)
                .run(recorded(), LocalDate.now(), raw -> raw);

        assertThat(walk.complete()).isTrue();
        assertThat(sent).containsExactly("#select2-firma-container",
                "internal:role=option[name=\"ASAŞ ALÜMİNYUM SAN.\"i]",
                "internal:role=row[name=\"HLLSRC03 Ödenecek\"i]",
                "internal:role=link[name=\"Fatura İptal Et\"i]",
                "internal:role=button[name=\"Geri\"i]");
        // One page after "Fatura İptal Et" (it has a Beklenen) and one at the end — none to find anything.
        assertThat(snapshots).isEqualTo(2);
        assertThat(walk.checkpoints()).singleElement().extracting(FastReplay.Checkpoint::step).isEqualTo(4);
        assertThat(FastReplay.verdictTurn(SCENARIO, walk, List.of(), walk.lastPage()))
                .contains("KODLA").contains("4. adımdan HEMEN SONRAKİ ekran");
        // ClaimCheck reads the replayed clicks off the tape.
        assertThat(tape.calls()).filteredOn(call -> "browser_click".equals(call.tool())).hasSize(5);
    }

    @Test
    @DisplayName("the first call that errors stops the replay; nothing after it is sent")
    void firstErrorStops() {
        failOn = "internal:role=row[name=\"HLLSRC03 Ödenecek\"i]";
        FastReplay.Result walk = new FastReplay(browser(), ActionTape.open(dir, "explore", "", "", "", List.of()),
                Duration.ZERO).run(recorded(), LocalDate.now(), raw -> raw);

        assertThat(walk.complete()).isFalse();
        assertThat(walk.stoppedAt()).isEqualTo(3);
        assertThat(walk.reason()).contains("Timeout");
        assertThat(sent).hasSize(3).doesNotContain("internal:role=link[name=\"Fatura İptal Et\"i]");
        assertThat(walk.done()).hasSize(2);
        assertThat(snapshots).isZero();
    }

    @Test
    @DisplayName("a closed browser ends the run as a closed browser, not as a broken cache")
    void closedBrowserIsNotABrokenCache() {
        ToolCallback[] closed = {tool("browser_click", input -> "### Error\nTarget page, context or browser has been closed")};

        assertThatThrownBy(() -> new FastReplay(closed, ActionTape.open(dir, "explore", "", "", "", List.of()),
                Duration.ZERO).run(recorded(), LocalDate.now(), raw -> raw))
                .isInstanceOf(BudgetedTool.BrowserClosed.class);
    }

    @Test
    @DisplayName("an edited scenario no longer matches its cache")
    void hashFollowsTheText() {
        assertThat(AiRunCache.sha256(SCENARIO)).isEqualTo(AiRunCache.sha256(SCENARIO + "\n"))
                .isNotEqualTo(AiRunCache.sha256(SCENARIO.replace("Geri", "Kapat")));
    }

    /* ------------------------------------------------------------------ */

    private static final Pattern ARG = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static String arg(String json, String name) {
        Matcher match = ARG.matcher(json);
        while (match.find()) {
            if (match.group(1).equals(name)) {
                return match.group(2).replace("\\\"", "\"");
            }
        }
        return "";
    }

    private static ToolCallback tool(String name, java.util.function.Function<String, String> answer) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                return answer.apply(input);
            }
        };
    }
}
