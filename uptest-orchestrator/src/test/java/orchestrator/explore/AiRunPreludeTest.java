package orchestrator.explore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class AiRunPreludeTest {

    @TempDir
    Path dir;

    private final List<String> calls = new ArrayList<>();

    private static final AiRunPrelude.Login NGC = new AiRunPrelude.Login(
            "ngc", "https://x/indexNgc.do", "QZ06395288", "3014", "pw987654", "34020");

    /** Every MCP tool; a call whose "tool args" line matches {@code fails} answers with an error. */
    private ToolCallback[] mcp(Predicate<String> fails) {
        List<ToolCallback> out = new ArrayList<>();
        for (String name : List.of("browser_navigate", "browser_type", "browser_click", "browser_snapshot")) {
            out.add(new ToolCallback() {
                @Override
                public ToolDefinition getToolDefinition() {
                    return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
                }

                @Override
                public String call(String input) {
                    String line = name + " " + input;
                    calls.add(line);
                    if (fails.test(line)) {
                        return "### Error\nTimeout 5000ms exceeded";
                    }
                    return name.equals("browser_snapshot") ? "- heading \"Firma Fatura İşlemleri\" [ref=e9]" : "ok";
                }
            });
        }
        return out.toArray(ToolCallback[]::new);
    }

    private AiRunPrelude.Outcome run(Predicate<String> fails, AiRunPrelude.Login login, ActionTape tape) {
        return new AiRunPrelude(mcp(fails), tape, Duration.ZERO)
                .run(login, "NAKİT YÖNETİMİ", "Firma Fatura İşlemleri");
    }

    private ActionTape tape() {
        return ActionTape.open(dir, "explore", "x", "", "", List.of("pw987654", "34020"));
    }

    @Test
    @DisplayName("NGC: the executor's selectors, the SMS step, the menu — and the screen is open")
    void ngcLoginAndMenu() throws IOException {
        ActionTape tape = tape();
        AiRunPrelude.Outcome outcome = run(line -> false, NGC, tape);

        assertThat(outcome.reached()).isEqualTo(AiRunPrelude.Reached.ON_SCREEN);
        assertThat(outcome.page()).contains("Firma Fatura İşlemleri");
        assertThat(calls).extracting(line -> line.split(" ", 2)[0] + " " + target(line)).containsExactly(
                "browser_navigate ",
                "browser_type #userCode",
                "browser_type #userId",
                "browser_type #password",
                "browser_click #btnSubmit",
                "browser_type #otpPassword",
                "browser_click #btnSubmit",
                "browser_click i:text-is(\"NAKİT YÖNETİMİ\")",
                "browser_click .main-nav a:visible:text-is(\"Firma Fatura İşlemleri\")",
                "browser_snapshot ");
        tape.finish();
        String file = Files.readString(tape.file(), StandardCharsets.UTF_8);
        assertThat(file).contains("prelude_login").contains("prelude_menu").doesNotContain("pw987654");
    }

    @Test
    @DisplayName("no SMS screen: the code moves on without a second submit")
    void noSmsScreen() {
        AiRunPrelude.Outcome outcome = run(line -> line.contains("otpPassword"), NGC, tape());
        assertThat(outcome.reached()).isEqualTo(AiRunPrelude.Reached.ON_SCREEN);
        assertThat(calls.stream().filter(line -> line.contains("#btnSubmit")).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a menu that never opens: three tries, then the agent finds it from a logged-in page")
    void menuFallsBackToTheAgent() {
        AiRunPrelude.Outcome outcome = run(line -> line.contains("main-nav"), NGC, tape());
        assertThat(outcome.reached()).isEqualTo(AiRunPrelude.Reached.LOGGED_IN);
        assertThat(outcome.note()).contains("menü koddan açılamadı");
        assertThat(calls.stream().filter(line -> line.contains("main-nav")).count()).isEqualTo(3);
        assertThat(outcome.page()).isNotNull();
    }

    @Test
    @DisplayName("a login that fails hands everything back: the agent gets today's full instruction")
    void loginFailureFallsBackCompletely() {
        AiRunPrelude.Outcome outcome = run(line -> line.contains("#userId"), NGC, tape());
        assertThat(outcome.reached()).isEqualTo(AiRunPrelude.Reached.NOTHING);
        assertThat(AiRunPrelude.instruction(outcome, "senaryo", "COMPOSED", "M", "S", 100))
                .isEqualTo("COMPOSED");
    }

    @Test
    void unknownAppTouchesNothing() {
        AiRunPrelude.Outcome outcome = run(line -> false,
                new AiRunPrelude.Login("xyz", "u", "c", "u", "p", null), tape());
        assertThat(outcome.reached()).isEqualTo(AiRunPrelude.Reached.NOTHING);
        assertThat(calls).isEmpty();
    }

    @Test
    @DisplayName("NGI logs in with its own two boxes")
    void ngiFields() {
        assertThat(AiRunPrelude.fieldsOf(new AiRunPrelude.Login("NGI", "u", "123", null, "p", null)))
                .containsExactly("#cifNoLabel", "123", "#password", "p");
    }

    @Test
    @DisplayName("on the screen: the agent gets the scenario and the page between markers — no login, no password")
    void instructionOnScreen() {
        String text = AiRunPrelude.instruction(
                new AiRunPrelude.Outcome(AiRunPrelude.Reached.ON_SCREEN, "PAGE", null),
                "Faturayı seç.", "Şifre = pw987654", "NAKİT YÖNETİMİ", "Firma Fatura İşlemleri", 100);
        assertThat(text).contains("ekranı açık").contains("Faturayı seç.")
                .contains(CompactingChatModel.SCREEN_OPEN + "\nPAGE\n" + CompactingChatModel.SCREEN_CLOSE)
                .doesNotContain("pw987654");
    }

    private static String target(String line) {
        int at = line.indexOf("\"target\":\"");
        if (at < 0) {
            return "";
        }
        String rest = line.substring(at + 10);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '\\' && i + 1 < rest.length()) {
                out.append(rest.charAt(++i));
            } else if (c == '"') {
                break;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
