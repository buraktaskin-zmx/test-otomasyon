package orchestrator.explore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tape 4bc5c9f0 (13.11.2026): "fatura oluştur", 17 steps, 32 turns, 192.735
 * tokens, unfinished. Every one of its steps fits the guide's grammar, so code
 * reads and does them all; and the two things that cost the walk most — the
 * hidden year list of "Başlangıç Vadesi" and the two-box "Toplam Tutar" — are
 * done the way a person does them.
 */
class CodeStepsTest {

    @TempDir
    Path dir;

    /** The steps of that run, as the instruction carried them. */
    private static final String FATURA = """
            1. "Firma Seçimi" listesinden "Sanko DBS Ödemeleri" seç
            2. İşlemler panelindeki "Fatura Oluştur" bağlantısına tıkla
            3. "Oluşturma Tipi" alanında "Bilgi Girişi" seçeneğini işaretle
            4. "Bayi Kodu" listesinden "SEN5" seç
            5. "Başlangıç Vadesi" alanına "27/11/2026" tarihini gir
            6. "Fatura No" alanına "11113" yaz
            7. "Orijinal Fatura No" alanına "2222" yaz
            8. "Döviz Cinsi" listesinden "TL" seç
            9. "Taksit Adedi" alanına "12" yaz
            10. "Toplam Tutar" alanına "1000,50" yaz
            11. "Orijinal Fatura Tutarı" alanına "33,00" yaz
            12. "Taksit Periyot Birimi" listesinden "Ay" seç
            13. "Taksit Periyodu" alanına "3" yaz
            14. "Fatura Dönemi" alanına "3" yaz
            15. "Fatura Tarihi" alanına "28/11/2026" tarihini gir
            16. "Fatura Tipi" listesinden "Normal" seç
            17. "Devam" düğmesine tıkla
               Beklenen (bu adımdan hemen sonra kontrol et): onay ekranı açılır""";

    @Test
    @DisplayName("all 17 steps of 4bc5c9f0 are read by code, none left to the model")
    void theWholeFaturaIsRead() {
        CodeSteps.Parsed parsed = CodeSteps.parse(FATURA);
        assertThat(parsed.all()).isTrue();
        assertThat(parsed.steps()).extracting(p -> p.step().action()).containsExactly(
                "select", "click", "click", "select", "date", "type", "type", "select", "type", "type", "type",
                "select", "type", "type", "date", "select", "click");
        assertThat(parsed.steps().get(2).step().name()).isEqualTo("Bilgi Girişi");
        assertThat(parsed.steps().get(4).step()).isEqualTo(new StepRunner.Step("date", "Başlangıç Vadesi", "27/11/2026"));
        assertThat(parsed.steps().get(9).step()).isEqualTo(new StepRunner.Step("type", "Toplam Tutar", "1000,50"));
        assertThat(parsed.steps().get(16).expected()).isEqualTo("onay ekranı açılır");
    }

    @Test
    @DisplayName("other phrasings the guide uses; and a step outside the grammar stops the reading there")
    void grammar() {
        assertThat(CodeSteps.read("Izgarada Ödeme Durumu \"Ödenecek\" olan ilk satırı seç ve başka satır seçme"))
                .isEqualTo(new StepRunner.Step("row", "Ödenecek", null));
        assertThat(CodeSteps.read("Açılan listenin arama kutusuna \"san\" yaz"))
                .isEqualTo(new StepRunner.Step("search", "san", "san"));
        assertThat(CodeSteps.read("Açılan güncelleme ekranında yalnızca \"Geri\" düğmesine tıkla"))
                .isEqualTo(new StepRunner.Step("click", "Geri", null));
        assertThat(CodeSteps.read("Tüm alanları boş bırak")).isNull();
        assertThat(CodeSteps.read("Firma Seçimi alanından ASAŞ ALÜMİNYUM SAN. seç")).isNull();

        CodeSteps.Parsed parsed = CodeSteps.parse("""
                1. "Firma Seçimi" listesinden "ASAŞ ALÜMİNYUM SAN." seç
                2. Tüm alanları boş bırak
                3. "Devam" düğmesine tıkla""");
        assertThat(parsed.all()).isFalse();
        assertThat(parsed.steps()).hasSize(1);
        assertThat(parsed.firstUnread()).isEqualTo(2);
    }

    /* ---------------- the amount: two boxes ---------------- */

    private static final Pattern TARGET = Pattern.compile("\"target\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TEXT = Pattern.compile("\"text\"\\s*:\\s*\"([^\"]*)\"");

    private final Map<String, String> boxes = new LinkedHashMap<>(Map.of("e722", "0", "e724", "00"));
    private final List<String> sent = new ArrayList<>();

    private String form() {
        return "### Page\n- Page URL: x\n### Snapshot\n```yaml\n- generic [ref=e684]:\n"
                + "  - generic [ref=e720]:\n"
                + "    - generic [ref=e721]: Toplam Tutar\n"
                + "    - textbox \"Toplam Tutar\" [ref=e722]: \"" + boxes.get("e722") + "\"\n"
                + "    - generic [ref=e723]: \",\"\n"
                + "    - textbox [ref=e724]: \"" + boxes.get("e724") + "\"\n"
                + "    - generic [ref=e915]: TL\n```";
    }

    private ToolCallback browser(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                if ("browser_snapshot".equals(name)) {
                    return form();
                }
                sent.add(name + " " + input);
                Matcher target = TARGET.matcher(input);
                Matcher text = TEXT.matcher(input);
                if ("browser_type".equals(name) && target.find() && text.find()) {
                    // A select-all came first, so the box is replaced; the mask groups thousands.
                    String typed = text.group(1);
                    boxes.put(target.group(1), "e722".equals(target.group(1)) && typed.length() > 3
                            ? typed.substring(0, typed.length() - 3) + "." + typed.substring(typed.length() - 3)
                            : typed);
                }
                return "### Ran Playwright code\n```js\nawait x;\n```";
            }
        };
    }

    @Test
    @DisplayName("\"1000,50\" into Toplam Tutar: lira and kuruş into their own boxes, each cleared, both read back")
    void amountIsTwoBoxes() {
        ActionTape tape = ActionTape.open(dir, "explore", "x", "", "", List.of());
        tape.record("browser_snapshot", "{}", ActionTape.Outcome.OK, form());
        AiRunPolicy policy = new AiRunPolicy(browser("browser_snapshot"), Duration.ZERO, 0, 220, 12_000, () -> 0L);
        StepRunner runner = new StepRunner();
        Object[] wrapped = BudgetedTool.wrap(List.of(browser("browser_click"), browser("browser_press_key"),
                        browser("browser_type"), runner), 99, null, List.of(), new LinkedHashSet<>(), "x",
                new AtomicBoolean(), tape, new RunVerdict(), policy);
        runner.bind(wrapped, tape);

        String out = runner.acceptJson("{\"steps\":[{\"action\":\"type\",\"name\":\"Toplam Tutar\",\"value\":\"1000,50\"}]}");

        assertThat(out).contains("→ tamam");
        assertThat(boxes).containsEntry("e722", "1.000").containsEntry("e724", "50");
        assertThat(sent).filteredOn(line -> line.startsWith("browser_press_key"))
                .hasSize(2).allMatch(line -> line.contains("ControlOrMeta+a"));
    }

    /* ---------------- the calendar whose year list is hidden ---------------- */

    private static final String EVALUATE_SCHEMA = "{\"properties\":{\"function\":{\"type\":\"string\"}}}";

    private static ToolCallback tool(String name, String schema, Function<String, String> answer) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name).inputSchema(schema).build();
            }

            @Override
            public String call(String input) {
                return answer.apply(input);
            }
        };
    }

    @Test
    @DisplayName("4bc5c9f0: the calendar shows November and hides its lists — reach December by the arrow")
    void calendarByArrows() {
        List<String> clicks = new ArrayList<>();
        List<String> selects = new ArrayList<>();
        DateTool dates = DateTool.bind(new ToolCallback[]{
                tool("browser_click", "{}", in -> {
                    clicks.add(in);
                    return "### Ran Playwright code\n```js\nawait page.getByRole('textbox', { name: 'Başlangıç"
                            + " Vadesi' }).click();\n```";
                }),
                tool("browser_select_option", "{}", in -> {
                    selects.add(in);
                    return "### Error\nTimeout 5000ms exceeded. element is not visible";
                }),
                tool("browser_evaluate", EVALUATE_SCHEMA, in -> "### Ran Playwright code\n```js\n'SHOWN ' + yy\n```\n"
                        + "### Result\n\"SHOWN 2026 10 0 0\"\n")});

        String result = dates.setDate("e701", "27/12/2026");

        assertThat(result).startsWith("Set to 27/12/2026");
        assertThat(selects).isEmpty();
        assertThat(clicks).hasSize(3);
        assertThat(clicks.get(1)).contains(".ui-datepicker-next");
        // By role: ai-run 4e5a83fa had a second, "nonprintable" "27" that is no link.
        assertThat(clicks.get(2)).contains("#ui-datepicker-div >> internal:role=link[name=\\\"27\\\"s]");
    }

    @Test
    @DisplayName("4e5a83fa: a target written as role + name becomes the role selector; others pass untouched")
    void roleAndNameTargets() {
        assertThat(BudgetedTool.roleTarget("{\"date\":\"27/11/2026\",\"target\":\"textbox \\\"Başlangıç Vadesi\\\"\"}"))
                .contains("\"target\":\"internal:role=textbox[name=\\\"Başlangıç Vadesi\\\"s]\"")
                .contains("\"date\":\"27/11/2026\"");
        assertThat(BudgetedTool.roleTarget("{\"target\":\"link \\\"27\\\"\"}"))
                .contains("internal:role=link[name=\\\"27\\\"s]");
        assertThat(BudgetedTool.roleTarget("{\"target\":\"e701\"}")).isEqualTo("{\"target\":\"e701\"}");
        assertThat(BudgetedTool.roleTarget("{\"target\":\"#select2-firma-container\"}"))
                .isEqualTo("{\"target\":\"#select2-firma-container\"}");
        assertThat(BudgetedTool.roleTarget("{\"text\":\"san\"}")).isEqualTo("{\"text\":\"san\"}");
    }

    @Test
    @DisplayName("when the calendar cannot be read, the year and month lists are used as before")
    void calendarUnreadable() {
        List<String> selects = new ArrayList<>();
        DateTool dates = DateTool.bind(new ToolCallback[]{
                tool("browser_click", "{}", in -> "ok"),
                tool("browser_select_option", "{}", in -> {
                    selects.add(in);
                    return "ok";
                }),
                tool("browser_evaluate", EVALUATE_SCHEMA, in -> "### Result\n\"SHOWN none\"\n")});

        assertThat(dates.setDate("e701", "27/11/2027")).startsWith("Set to");
        assertThat(selects).hasSize(2);
        assertThat(selects.get(0)).contains("2027");
        assertThat(selects.get(1)).contains("\"10\"");
    }
}
