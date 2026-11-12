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

    /** What a failing call answers; a test about network errors replaces it. */
    private String errorText = "### Error\nTimeout 5000ms exceeded";

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
                        return errorText;
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
    @DisplayName("the login box is not there yet: the code waits and retries instead of giving up")
    void waitsForTheLoginBox() {
        // Fails the first two attempts at #userCode, as a page still loading does.
        int[] seen = {0};
        AiRunPrelude.Outcome outcome = new AiRunPrelude(
                mcp(line -> line.contains("#userCode") && ++seen[0] <= 2), tape(), Duration.ZERO)
                .run(NGC, "NAKİT YÖNETİMİ", "Firma Fatura İşlemleri");

        assertThat(outcome.reached()).isEqualTo(AiRunPrelude.Reached.ON_SCREEN);
        assertThat(calls.stream().filter(line -> line.contains("#userCode")).count()).isEqualTo(3);
    }

    @Test
    @DisplayName("a login box that never appears still falls back to the agent")
    void givesUpAfterTheRetries() {
        AiRunPrelude.Outcome outcome = run(line -> line.contains("#userCode"), NGC, tape());
        assertThat(outcome.reached()).isEqualTo(AiRunPrelude.Reached.NOTHING);
        assertThat(calls.stream().filter(line -> line.contains("#userCode")).count()).isEqualTo(6);
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
    @DisplayName("a network failure is named as one — it is not the login code's fault")
    void networkFailuresAreNamed() {
        assertThat(AiRunPrelude.diagnose("Error: browserBackend.callTool: net::ERR_NAME_NOT_RESOLVED at https://x"))
                .contains("AĞ").contains("VPN").contains("Kodda yapılacak bir şey yok");
        assertThat(AiRunPrelude.diagnose("net::ERR_CONNECTION_RESET at https://x"))
                .contains("AĞ").contains("ERR_CONNECTION_RESET");
        assertThat(AiRunPrelude.diagnose("\"#userCode\" does not match any elements."))
                .contains("SAYFA").contains("#userCode");
        assertThat(AiRunPrelude.diagnose("bilinmeyen bir şey")).contains("bilinmeyen bir şey");
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
                .contains(CompactingAdvisor.SCREEN_OPEN + "\nPAGE\n" + CompactingAdvisor.SCREEN_CLOSE)
                .doesNotContain("pw987654");
    }

    private static final String RESET =
            "### Error\nError: browserBackend.callTool: net::ERR_CONNECTION_RESET at https://x/indexNgc.do";

    private long navigations() {
        return calls.stream().filter(line -> line.startsWith("browser_navigate")).count();
    }

    @Test
    @DisplayName("a reset on the way in is tried again — the Mac path drops ~2 in 5 connections")
    void resetNavigationIsRetried() {
        errorText = RESET;
        int[] left = {2};
        AiRunPrelude.Outcome outcome = run(
                line -> line.startsWith("browser_navigate") && left[0]-- > 0, NGC, tape());
        assertThat(outcome.reached()).isEqualTo(AiRunPrelude.Reached.ON_SCREEN);
        assertThat(navigations()).isEqualTo(3);
    }

    @Test
    @DisplayName("a path that stays down gives up after five attempts, and says it was the connection")
    void resetEveryTimeGivesUp() {
        errorText = RESET;
        AiRunPrelude.Outcome outcome = run(line -> line.startsWith("browser_navigate"), NGC, tape());
        assertThat(outcome.reached()).isEqualTo(AiRunPrelude.Reached.NOTHING);
        assertThat(navigations()).isEqualTo(5);
        assertThat(outcome.note()).contains("BAĞLANTI").contains("5 denemenin hepsinde")
                .contains("ERR_CONNECTION_RESET").doesNotContain("proxy");
    }

    @Test
    @DisplayName("off the VPN is not retried: the name will not resolve on the fifth try either")
    void unresolvedNameIsNotRetried() {
        errorText = "### Error\nnet::ERR_NAME_NOT_RESOLVED at https://x/indexNgc.do";
        AiRunPrelude.Outcome outcome = run(line -> line.startsWith("browser_navigate"), NGC, tape());
        assertThat(outcome.reached()).isEqualTo(AiRunPrelude.Reached.NOTHING);
        assertThat(navigations()).isEqualTo(1);
        assertThat(outcome.note()).contains("VPN");
    }

    @Test
    void certificateIsNotRetried() {
        assertThat(AiRunPrelude.worthRetrying("net::ERR_CERT_AUTHORITY_INVALID")).isFalse();
        assertThat(AiRunPrelude.worthRetrying("net::ERR_CONNECTION_RESET")).isTrue();
        assertThat(AiRunPrelude.diagnose("net::ERR_CERT_AUTHORITY_INVALID at x"))
                .contains("Sertifika").contains("tekrar denemek düzeltmez");
    }

    /** ProductPanel.aiRun's text, character for character. */
    private static final String UI_TEXT = String.join("\n",
            "NAKİT YÖNETİMİ menüsü altındaki \"Firma Fatura İşlemleri\" ekranına git.",
            "Sonra şu senaryoyu uygula:",
            "",
            "Firma Seçimi listesinde arama yapılır",
            "Adımlar:",
            "1. Firma Seçimi listesini aç");

    @Test
    @DisplayName("on the screen: the UI's 'ekranına git' opening is dropped — it contradicted 'Menüye tekrar gitme'")
    void onScreenDropsTheGoToLine() {
        String text = AiRunPrelude.instruction(
                new AiRunPrelude.Outcome(AiRunPrelude.Reached.ON_SCREEN, "PAGE", null),
                UI_TEXT, "COMPOSED", "NAKİT YÖNETİMİ", "Firma Fatura İşlemleri", 100);
        assertThat(text).contains("Menüye tekrar gitme")
                .doesNotContain("ekranına git.")
                .doesNotContain("Sonra şu senaryoyu uygula:")
                .contains("Firma Seçimi listesinde arama yapılır\nAdımlar:\n1. Firma Seçimi listesini aç");
    }

    @Test
    @DisplayName("logged in but the menu did not open: the go-to line stays, the agent still has to find the screen")
    void loggedInKeepsTheGoToLine() {
        String text = AiRunPrelude.instruction(
                new AiRunPrelude.Outcome(AiRunPrelude.Reached.LOGGED_IN, "PAGE", "menü koddan açılamadı"),
                UI_TEXT, "COMPOSED", "NAKİT YÖNETİMİ", "Firma Fatura İşlemleri", 100);
        assertThat(text).contains(UI_TEXT);
    }

    @Test
    @DisplayName("only the LEADING go-to goes: the same words inside the scenario are left as written")
    void onlyTheOpeningIsDropped() {
        String inside = "Faturayı seç.\nListe ekranına git.";
        assertThat(AiRunPrelude.withoutGoTo(inside)).isEqualTo(inside);
        assertThat(AiRunPrelude.withoutGoTo("\"X\" ekranına git.\nSonra şu senaryoyu uygula:\n\nAdım"))
                .isEqualTo("Adım");
        assertThat(AiRunPrelude.withoutGoTo("\"X\" ekranına git.\nAdım")).isEqualTo("Adım");
        assertThat(AiRunPrelude.withoutGoTo("")).isEmpty();
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
