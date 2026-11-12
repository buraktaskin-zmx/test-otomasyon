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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A batch has to be no less careful than the same steps taken one turn at a
 * time. These tests are that claim: the order, the guards, the stop at the
 * first step that did not happen, and the stop when the screen starts saying
 * something it was not saying before.
 */
class StepRunnerTest {

    @TempDir
    Path dir;

    /** What the browser answers while nothing is wrong. */
    private static final String QUIET_PAGE = """
            ### Page Snapshot
            ```yaml
            - textbox [ref=e15]
            - button "Kaydet" [ref=e20]
            ```""";

    /** The same screen, now refusing. */
    private static final String COMPLAINING_PAGE = """
            ### Page Snapshot
            ```yaml
            - alert "Toplam tutar giriniz"
            - textbox [ref=e15]
            - button "Kaydet" [ref=e20]
            ```""";

    private final List<String> reached = new ArrayList<>();
    private final List<String> arguments = new ArrayList<>();

    @Test
    @DisplayName("the steps run in order, each through the tool it belongs to")
    void stepsRunInOrder() {
        StepRunner runner = runner("fatura no gir ve kaydet", page -> QUIET_PAGE);

        String answer = runner.acceptJson("""
                {"steps":[
                  {"action":"type","ref":"e15","element":"Fatura No","value":"955231"},
                  {"action":"click","ref":"e20","element":"Kaydet butonu"}
                ]}""");

        assertThat(reached).containsExactly("browser_type", "browser_click");
        assertThat(answer)
                .contains("1. type e15 \"Fatura No\" = \"955231\" → tamam")
                .contains("2. click e20 \"Kaydet butonu\" → tamam")
                // One page, at the end — not one per step.
                .containsOnlyOnce("### Page Snapshot");
        // A masked box rebuilds its value from key events, so typing is slow.
        assertThat(arguments.get(0)).contains("\"slowly\":true").contains("\"ref\":\"e15\"");
    }

    @Test
    @DisplayName("a confirmation the scenario never named stops the batch where it stands")
    void unaskedConfirmationStopsTheBatch() {
        // The real scenario: open the cancel screen, then go back WITHOUT
        // confirming. A batch must not be the way round that rule.
        StepRunner runner = runner("fatura i̇ptal et'e tıkla. geri butonuna tıkla.", page -> QUIET_PAGE);

        String answer = runner.acceptJson("""
                {"steps":[
                  {"action":"click","ref":"e8","element":"Fatura İptal Et butonu"},
                  {"action":"click","ref":"e42","element":"Evet butonu"},
                  {"action":"click","ref":"e9","element":"Geri butonu"}
                ]}""");

        assertThat(reached).containsExactly("browser_click");
        assertThat(answer)
                .contains("1. click e8 \"Fatura İptal Et butonu\" → tamam")
                .contains("2. click e42 \"Evet butonu\" → REDDEDİLDİ")
                .contains("Batch durdu")
                .contains("Kalan adımlar YAPILMADI");
    }

    @Test
    @DisplayName("a step that throws stops the batch, and says which one")
    void aThrownStepStopsTheBatch() {
        // The instruction names Kaydet, or the commit guard would refuse the
        // click before the batch ever reached the step being tested — which
        // the first version of this test found out the hard way.
        StepRunner runner = runner("kaydet'e tıkla, fatura no gir, kaydet'e tıkla",
                page -> QUIET_PAGE, "browser_type", "Element is not editable");

        String answer = runner.acceptJson("""
                {"steps":[
                  {"action":"click","ref":"e20","element":"Kaydet"},
                  {"action":"type","ref":"e15","element":"Fatura No","value":"955231"},
                  {"action":"click","ref":"e20","element":"Kaydet"}
                ]}""");

        assertThat(reached).containsExactly("browser_click", "browser_type");
        assertThat(answer)
                .contains("2. type e15 \"Fatura No\" = \"955231\" → HATA: Element is not editable")
                .contains("Batch durdu");
    }

    @Test
    @DisplayName("the batch stops the moment the screen starts complaining")
    void aComplainingScreenStopsTheBatch() {
        // Quiet for the baseline and the first step, then the application
        // refuses — the one thing a plan written in advance can walk into.
        AtomicInteger look = new AtomicInteger();
        StepRunner runner = runner("tutar ve kuruş gir, kaydet'e tıkla",
                page -> look.incrementAndGet() <= 2 ? QUIET_PAGE : COMPLAINING_PAGE);

        String answer = runner.acceptJson("""
                {"steps":[
                  {"action":"type","ref":"e15","element":"Tutar","value":"1250"},
                  {"action":"type","ref":"e16","element":"Kuruş","value":"50"},
                  {"action":"click","ref":"e20","element":"Kaydet"}
                ]}""");

        assertThat(reached).containsExactly("browser_type", "browser_type");
        assertThat(answer)
                .contains("ekran bir şey söylüyor: Toplam tutar giriniz")
                .contains("Batch durdu (ekran değişti)");
    }

    @Test
    @DisplayName("a screen that was ALREADY complaining does not stop every batch after step one")
    void anOldComplaintIsNotANewOne() {
        // Otherwise a page carrying a permanent status line would make the
        // batch tool useless, silently, and nothing would say why.
        StepRunner runner = runner("tutar gir ve kaydet'e tıkla", page -> COMPLAINING_PAGE);

        runner.acceptJson("""
                {"steps":[
                  {"action":"type","ref":"e15","element":"Tutar","value":"1250"},
                  {"action":"click","ref":"e20","element":"Kaydet"}
                ]}""");

        assertThat(reached).containsExactly("browser_type", "browser_click");
    }

    @Test
    @DisplayName("more steps than allowed are refused whole, never half-carried-out")
    void tooManyStepsAreRefusedWhole() {
        StepRunner runner = runner("uzun form", page -> QUIET_PAGE);
        StringBuilder many = new StringBuilder("{\"steps\":[");
        for (int i = 0; i < 9; i++) {
            many.append(i > 0 ? "," : "")
                    .append("{\"action\":\"click\",\"ref\":\"e").append(i).append("\"}");
        }

        String answer = runner.acceptJson(many.append("]}").toString());

        assertThat(reached).isEmpty();
        assertThat(answer).contains("at most 8 steps").contains("you sent 9");
    }

    @Test
    @DisplayName("the shapes a model really sends: an array as a STRING, a bare array, one object")
    void acceptsLooseShapes() {
        StepRunner runner = runner("", page -> QUIET_PAGE);
        runner.acceptJson("{\"steps\": \"[{\\\"action\\\":\\\"click\\\",\\\"ref\\\":\\\"e1\\\"}]\"}");
        assertThat(reached).containsExactly("browser_click");

        reached.clear();
        runner.acceptJson("[{\"action\":\"click\",\"ref\":\"e2\"},{\"action\":\"press\",\"value\":\"Enter\"}]");
        assertThat(reached).containsExactly("browser_click", "browser_press_key");

        reached.clear();
        runner.acceptJson("{\"action\":\"click\",\"ref\":\"e3\",\"element\":\"Kaydet\"}");
        assertThat(reached).containsExactly("browser_click");

        reached.clear();
        // The sentence form, and the alternative key names for a reference.
        runner.acceptJson("{\"actions\":[\"click e4\",{\"action\":\"type\",\"target\":\"e5\",\"text\":\"x\"}]}");
        assertThat(reached).containsExactly("browser_click", "browser_type");
    }

    @Test
    @DisplayName("arguments that are not a batch at all are answered, not thrown")
    void rubbishIsAnswered() {
        StepRunner runner = runner("", page -> QUIET_PAGE);

        assertThat(runner.acceptJson("}{ bozuk")).contains("no steps");
        assertThat(runner.acceptJson("{\"steps\":[]}")).contains("no steps");
        assertThat(reached).isEmpty();
    }

    @Test
    @DisplayName("a date goes to set_date, never to the typing tool")
    void datesGoThroughSetDate() {
        StepRunner runner = runner("vade gir", page -> QUIET_PAGE);

        runner.acceptJson("{\"steps\":[{\"action\":\"date\",\"ref\":\"e30\","
                + "\"element\":\"Başlangıç Vadesi\",\"value\":\"25/09/2026\"}]}");

        assertThat(reached).containsExactly("set_date");
        assertThat(arguments.get(0)).contains("\"target\":\"e30\"").contains("\"date\":\"25/09/2026\"");
    }

    @Test
    @DisplayName("Spring can build the tool and describe its argument — or ai-run breaks at request time")
    void springCanDescribeTheBatch() {
        // The argument is a LIST OF RECORDS, and the schema for it is
        // generated by Spring AI at the moment the tools are assembled — that
        // is, inside the request. A generator that could not handle the shape
        // would not fail here in a test but there, on a walk, with the
        // analyst waiting. So the shape is pinned.
        var callbacks = org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
                .toolObjects(new StepRunner(8)).build().getToolCallbacks();

        assertThat(callbacks).hasSize(1);
        var definition = callbacks[0].getToolDefinition();
        assertThat(definition.name()).isEqualTo(StepRunner.TOOL);
        assertThat(definition.inputSchema())
                .contains("steps")
                .contains("action")
                .contains("ref");
        // And it still survives the leaning every ai-run definition gets.
        assertThat(LeanTools.lean(definition, 220).inputSchema()).contains("action");
    }

    /* ------------------------------------------------------------------ */

    private StepRunner runner(String instruction, Function<Integer, String> pages) {
        return runner(instruction, pages, null, null);
    }

    /**
     * A runner wired exactly as ExploreController wires it: the steps go
     * through BudgetedTool-wrapped callbacks, so the guards in these tests are
     * the real ones and not a copy.
     */
    private StepRunner runner(String instruction, Function<Integer, String> pages,
                              String throwsOn, String message) {
        ActionTape tape = ActionTape.open(dir, "explore", instruction, "", "", List.of());
        AtomicInteger look = new AtomicInteger();
        ToolCallback snapshot = fake("browser_snapshot", () -> pages.apply(look.incrementAndGet()));
        AiRunPolicy policy = new AiRunPolicy(snapshot, Duration.ZERO, 0, 220, 6000, () -> 0L);

        List<ToolCallback> browser = new ArrayList<>();
        for (String name : List.of("browser_click", "browser_type", "browser_select_option",
                "browser_press_key", "set_date")) {
            browser.add(name.equals(throwsOn) ? thrower(name, message) : fake(name, () -> "Done"));
        }

        StepRunner runner = new StepRunner(8);
        Object[] wrapped = BudgetedTool.wrap(browser, 99, null, List.of(), new LinkedHashSet<>(),
                fold(instruction), new AtomicBoolean(), tape, new RunVerdict(), policy, runner);
        Map<String, ToolCallback> byName = new LinkedHashMap<>();
        for (Object one : wrapped) {
            ToolCallback callback = (ToolCallback) one;
            byName.put(callback.getToolDefinition().name(), callback);
        }
        runner.use(byName, policy, tape);
        return runner;
    }

    /** BudgetedTool folds the instruction before storing it; the test must match. */
    private static String fold(String text) {
        return text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.forLanguageTag("tr-TR"));
    }

    private ToolCallback fake(String name, java.util.function.Supplier<String> answer) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                if (!"browser_snapshot".equals(name)) {
                    reached.add(name);
                    arguments.add(input);
                }
                return answer.get();
            }
        };
    }

    private ToolCallback thrower(String name, String message) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                reached.add(name);
                arguments.add(input);
                throw new IllegalStateException(message);
            }
        };
    }
}
