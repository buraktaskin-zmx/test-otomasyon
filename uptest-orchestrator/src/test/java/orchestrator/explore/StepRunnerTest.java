package orchestrator.explore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "İptal ekranından Geri ile listeye dönülür" in ONE do_steps call, against a
 * fake browser whose pages have the shape tape 2628c4fd recorded — including
 * the two things that broke a reference-based batch: the İşlemler panel gets
 * new references after a row click, and "Geri" exists only after "Fatura
 * İptal Et" is pressed.
 */
class StepRunnerTest {

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
                        - cell "Ödenecek" [ref=e54]
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

    private static final String CANCEL_WITH_DIALOG = page("""
            - generic [ref=e1]:
              - heading "Fatura İptal" [level=2] [ref=e80]
              - alertdialog "Uyarı" [ref=e90]:
                - generic [ref=e91]: Fatura zaten iptal edilmiş.
              - button "Geri" [ref=e82]""");

    /** Which page each click leads to, by target. */
    private Map<String, String> next;
    private String current;
    private final List<String> clicked = new ArrayList<>();

    private static final String SCENARIO = """
            1. Firma Seçimi alanından ASAŞ ALÜMİNYUM SAN. seç
            2. Listede Ödeme Durumu "Ödenecek" olan ilk faturanın satırını seç
            3. İşlemler panelindeki "Fatura İptal Et" düğmesine tıkla
            4. Açılan ekranda "Geri" düğmesine tıkla""";

    @BeforeEach
    void browser() {
        current = LIST;
        next = Map.of(
                "e10", OPEN,
                "e23", grid("", "e60"),
                // The row click redraws the İşlemler panel under a NEW reference.
                "e40", grid("e40", "e61"),
                "e61", CANCEL,
                "e82", grid("e40", "e62"));
    }

    private static final Pattern TARGET = Pattern.compile("\"target\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ELEMENT = Pattern.compile("\"element\"\\s*:\\s*\"([^\"]+)\"");

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
                Matcher element = ELEMENT.matcher(input);
                String ref = target.find() ? target.group(1) : "";
                String what = element.find() ? element.group(1) : "";
                clicked.add(ref);
                String after = next.get(ref);
                if (after != null) {
                    current = after;
                }
                return "### Ran Playwright code\n```js\nawait page.getByRole('button', { name: '"
                        + what + "' }).click();\n```";
            }
        };
    }

    private StepRunner runner(String scenario) {
        ActionTape tape = ActionTape.open(dir, "explore", scenario, "", "", List.of());
        tape.record("browser_snapshot", "{}", ActionTape.Outcome.OK, current);
        AiRunPolicy policy = new AiRunPolicy(fake("browser_snapshot"), Duration.ZERO, 0, 220, 12_000, () -> 0L);
        StepRunner runner = new StepRunner();
        Object[] wrapped = BudgetedTool.wrap(
                List.of(fake("browser_click"), fake("browser_press_sequentially"), runner),
                99, null, List.of(), new LinkedHashSet<>(), scenario, new AtomicBoolean(), tape,
                new RunVerdict(), policy);
        runner.bind(wrapped, tape);
        this.tape = tape;
        return runner;
    }

    private ActionTape tape;

    @Test
    @DisplayName("the whole scenario in one call, each element found by name when its step runs")
    void wholeScenarioInOneCall() {
        StepRunner runner = runner(SCENARIO);

        String out = runner.acceptJson("""
                {"steps":[
                  {"action":"select","name":"Firma Seçimi","value":"ASAŞ ALÜMİNYUM SAN."},
                  {"action":"row","name":"Ödenecek"},
                  {"action":"click","name":"Fatura İptal Et"},
                  {"action":"click","name":"Geri"}]}""");

        // e61, not e60: the panel's reference after the row click.
        assertThat(clicked).containsExactly("e10", "e23", "e40", "e61", "e82");
        assertThat(out).contains("1. select \"Firma Seçimi\" = \"ASAŞ ALÜMİNYUM SAN.\" → tamam")
                .contains("2. row \"Ödenecek\" → tamam (2 satır eşleşti, ilki: HLLSRC03 Ödenecek)")
                .contains("4. click \"Geri\" → tamam")
                .doesNotContain("DURDU");
        // ClaimCheck reads the sub-steps off the tape exactly as single calls.
        assertThat(ClaimCheck.unclicked(SCENARIO, tape.calls(), List.of())).isEmpty();
    }

    @Test
    @DisplayName("a confirmation the scenario never named is refused inside the batch, and the batch stops")
    void unaskedConfirmationStopsTheBatch() {
        next = Map.of("e10", OPEN, "e23", grid("", "e60"), "e40", grid("e40", "e61"), "e61", CANCEL);
        StepRunner runner = runner(SCENARIO);

        String out = runner.acceptJson("""
                {"steps":[
                  {"action":"select","name":"Firma Seçimi","value":"ASAŞ ALÜMİNYUM SAN."},
                  {"action":"row","name":"HLLSRC03"},
                  {"action":"click","name":"Fatura İptal Et"},
                  {"action":"click","name":"Evet"},
                  {"action":"click","name":"Geri"}]}""");

        assertThat(clicked).containsExactly("e10", "e23", "e40", "e61");
        assertThat(out).contains("4. click \"Evet\" → REDDEDİLDİ").contains("DURDU")
                .doesNotContain("5. click");
    }

    @Test
    @DisplayName("a dialog that appears stops the batch before the next step")
    void newDialogStops() {
        next = Map.of("e10", OPEN, "e23", grid("", "e60"), "e40", grid("e40", "e61"),
                "e61", CANCEL_WITH_DIALOG);
        StepRunner runner = runner(SCENARIO);

        String out = runner.acceptJson("""
                [{"action":"select","name":"Firma Seçimi","value":"ASAŞ ALÜMİNYUM SAN."},
                 {"action":"row","name":"Ödenecek"},
                 {"action":"click","name":"Fatura İptal Et"},
                 {"action":"click","name":"Geri"}]""");

        assertThat(clicked).doesNotContain("e82");
        assertThat(out).contains("ekran yeni bir şey söylüyor").contains("Uyarı").contains("DURDU")
                // the page as it stands, for the model to go on from
                .contains("alertdialog \"Uyarı\"");
    }

    @Test
    @DisplayName("a name that is not on the page stops the batch; nothing is guessed")
    void unknownNameStops() {
        StepRunner runner = runner(SCENARIO);

        String out = runner.acceptJson("{\"steps\":[{\"action\":\"click\",\"name\":\"Fatura Sil\"},"
                + "{\"action\":\"select\",\"name\":\"Firma Seçimi\",\"value\":\"ASAŞ ALÜMİNYUM SAN.\"}]}");

        assertThat(clicked).isEmpty();
        assertThat(out).contains("sayfada \"Fatura Sil\" adlı tıklanabilir öğe yok").contains("DURDU");
    }

    @Test
    @DisplayName("an option missing from the list is searched for, then read back")
    void optionNotListedIsSearched() {
        String searched = OPEN.replace("option \"ASAŞ ALÜMİNYUM SAN.\" [ref=e23]", "option \"AYDEM\" [ref=e24]");
        next = Map.of("e10", searched, "e23", grid("", "e60"));
        StepRunner runner = runner(SCENARIO);
        // The search narrows the list to the firm.
        ToolCallback typing = fakeTyping(OPEN);
        Object[] wrapped = BudgetedTool.wrap(List.of(fake("browser_click"), typing, runner),
                99, null, List.of(), new LinkedHashSet<>(), SCENARIO, new AtomicBoolean(), tape,
                new RunVerdict(), new AiRunPolicy(fake("browser_snapshot"), Duration.ZERO, 0, 220, 12_000,
                        () -> 0L));
        runner.bind(wrapped, tape);

        String out = runner.acceptJson("{\"steps\":[{\"action\":\"select\",\"name\":\"Firma Seçimi\","
                + "\"value\":\"ASAŞ ALÜMİNYUM SAN.\"}]}");

        assertThat(clicked).containsExactly("e10", "", "e23");
        assertThat(out).contains("→ tamam");
    }

    private ToolCallback fakeTyping(String after) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("browser_press_sequentially")
                        .description("x").inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                clicked.add("");
                current = after;
                return "typed";
            }
        };
    }

    @Test
    @DisplayName("the shapes a model sends: a bare array, a string, one object")
    void lenientShapes() {
        assertThat(StepRunner.parse("[{\"action\":\"click\",\"name\":\"Geri\"}]")).hasSize(1);
        assertThat(StepRunner.parse("{\"steps\":\"[{\\\"action\\\":\\\"click\\\",\\\"name\\\":\\\"Geri\\\"}]\"}"))
                .hasSize(1);
        assertThat(StepRunner.parse("{\"action\":\"click\",\"element\":\"Geri\"}"))
                .containsExactly(new StepRunner.Step("click", "Geri", ""));
        assertThat(StepRunner.parse("not json")).isEmpty();
    }
}
