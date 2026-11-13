package orchestrator.explore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunVerdictTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("a failed step means Geçmedi, whatever the agent claimed")
    void failedStepOverridesClaim() {
        RunVerdict verdict = new RunVerdict();
        verdict.reportResult(List.of("Giriş yapıldı."), List.of("İptal butonu bulunamadı."), List.of(), true);
        assertThat(verdict.result().passed()).isFalse();
        assertThat(verdict.toJson()).containsEntry("verdict", "FAILED");
    }

    @Test
    @DisplayName("blank items are 'none', and empty sections are left out of the text")
    void blanksDroppedAndEmptySectionsOmitted() {
        RunVerdict verdict = new RunVerdict();
        verdict.reportResult(List.of("Fatura listelendi.", " "), Arrays.asList("", null), null, true);
        assertThat(verdict.result().failed()).isEmpty();
        assertThat(verdict.result().warnings()).isEmpty();
        assertThat(verdict.result().passed()).isTrue();
        assertThat(verdict.asText())
                .contains("Doğru ilerleyen adımlar:\n- Fatura listelendi.")
                .doesNotContain("Geçemeyen")
                .doesNotContain("Uyarılar")
                .endsWith("Sonuç: Geçti ✓");
    }

    @Test
    @DisplayName("a model that answers with objects instead of sentences does not lose the walk")
    void survivesNonStringItems() {
        RunVerdict verdict = new RunVerdict();
        List<Object> odd = List.of(java.util.Map.of("adim", "Fatura seçildi"), 42);
        @SuppressWarnings("unchecked")
        List<String> asStrings = (List<String>) (List<?>) odd;
        verdict.reportResult(asStrings, List.of(), List.of(), true);

        assertThat(verdict.result().succeeded()).hasSize(2);
        assertThat(verdict.result().succeeded().get(1)).isEqualTo("42");
        assertThat(verdict.result().passed()).isTrue();
    }

    @Test
    @DisplayName("the arrays written out as a STRING — the shape that killed a finished walk")
    void acceptsArraysEncodedAsAString() {
        RunVerdict verdict = new RunVerdict();
        verdict.acceptJson("{\"succeeded\": \"[\\\"Ekrana gidildi.\\\", \\\"Firma seçildi.\\\"]\","
                + " \"failed\": [\"Fatura yok.\"], \"warnings\": [], \"passed\": false}");

        assertThat(verdict.result().succeeded())
                .containsExactly("Ekrana gidildi.", "Firma seçildi.");
        assertThat(verdict.result().failed()).containsExactly("Fatura yok.");
        assertThat(verdict.result().passed()).isFalse();
    }

    @Test
    @DisplayName("one sentence instead of a list, and \"true\" instead of true, are both read")
    void acceptsLooseShapes() {
        RunVerdict verdict = new RunVerdict();
        verdict.acceptJson("{\"succeeded\": \"Her şey yapıldı.\", \"passed\": \"true\"}");

        assertThat(verdict.result().succeeded()).containsExactly("Her şey yapıldı.");
        assertThat(verdict.result().failed()).isEmpty();
        assertThat(verdict.result().passed()).isTrue();
    }

    @Test
    @DisplayName("arguments that are not JSON at all still close the walk, as a failure")
    void acceptsRubbish() {
        RunVerdict verdict = new RunVerdict();
        verdict.acceptJson("}{ bozuk");
        assertThat(verdict.isClosed()).isTrue();
        assertThat(verdict.result().passed()).isFalse();
    }

    @Test
    void noReportMeansNoSummary() {
        RunVerdict verdict = new RunVerdict();
        assertThat(verdict.isClosed()).isFalse();
        assertThat(verdict.toJson()).isNull();
        assertThat(verdict.asText()).isEmpty();
    }

    @Test
    @DisplayName("the report passes the off-limits guard and a spent budget; every call after it is refused")
    void reportBypassesPageGuardsThenClosesTheWalk() {
        RunVerdict verdict = new RunVerdict();
        List<String> reached = new ArrayList<>();
        ActionTape tape = ActionTape.open(dir, "explore", "TIKLAMA: Garantili Ödeme", "", "", List.of());
        Object[] tools = BudgetedTool.wrap(
                List.of(fake(RunVerdict.TOOL, reached), fake("browser_click", reached)),
                0, null, List.of("Garantili Ödeme"), new LinkedHashSet<>(),
                "TIKLAMA: Garantili Ödeme", new AtomicBoolean(), tape, verdict, null);
        ToolCallback report = (ToolCallback) tools[0];
        ToolCallback click = (ToolCallback) tools[1];

        report.call("{\"succeeded\":[\"Yapıldı.\"],\"warnings\":[\"Garantili Ödeme'ye dokunulmadı.\"],"
                + "\"passed\":true}");

        // It went to the verdict, not through Spring's binding: the call is
        // read by RunVerdict.acceptJson, so the wrapped callback is never
        // reached and a shape we did not ask for cannot kill a finished walk.
        assertThat(reached).isEmpty();
        assertThat(verdict.isClosed()).isTrue();
        assertThat(verdict.result().warnings()).containsExactly("Garantili Ödeme'ye dokunulmadı.");

        String refused = click.call("{\"element\":\"Devam\"}");
        assertThat(reached).isEmpty();
        assertThat(refused).contains("already been reported");
    }

    @Test
    @DisplayName("once the budget is spent the next tool ENDS the walk in code (13 refused turns, 4bc5c9f0)")
    void spentBudgetStopsEverythingButTheReport() {
        RunVerdict verdict = new RunVerdict();
        List<String> reached = new ArrayList<>();
        ActionTape tape = ActionTape.open(dir, "explore", "x", "", "", List.of());
        AiRunPolicy broke = new AiRunPolicy(null, java.time.Duration.ZERO, 100, 220, 6000, () -> 100_000L);
        Object[] tools = BudgetedTool.wrap(
                List.of(fake(RunVerdict.TOOL, reached), fake("browser_click", reached)),
                999, null, List.of(), new LinkedHashSet<>(), "", new AtomicBoolean(), tape,
                verdict, broke);

        assertThatThrownBy(() -> ((ToolCallback) tools[1]).call("{\"element\":\"Devam\"}"))
                .isInstanceOf(BudgetedTool.WalkOver.class).hasMessageContaining("token bütçesi doldu");
        assertThat(reached).isEmpty();

        ((ToolCallback) tools[0]).call("{\"succeeded\":[\"Giriş yapıldı.\"],\"passed\":false}");
        assertThat(verdict.isClosed()).isTrue();
        assertThat(verdict.result().succeeded()).containsExactly("Giriş yapıldı.");
    }

    @Test
    @DisplayName("a confirmation the scenario never named is refused — the invoice that got cancelled")
    void unaskedConfirmationIsRefused() {
        // The real scenario: open the cancel screen, then go back WITHOUT confirming.
        String scenario = fold("Fatura İptal Et'e tıkla. Geri butonuna tıkla.");
        RunVerdict verdict = new RunVerdict();
        List<String> reached = new ArrayList<>();
        ActionTape tape = ActionTape.open(dir, "explore", scenario, "", "", List.of());
        AiRunPolicy policy = new AiRunPolicy(null, java.time.Duration.ZERO, 0, 220, 6000, () -> 0L);
        Object[] tools = BudgetedTool.wrap(List.of(fake("browser_click", reached)),
                99, null, List.of(), new LinkedHashSet<>(), scenario, new AtomicBoolean(), tape,
                verdict, policy);
        ToolCallback click = (ToolCallback) tools[0];

        String refused = click.call("{\"element\":\"Evet butonu\",\"ref\":\"e42\"}");
        assertThat(refused).contains("REFUSED").contains("evet");
        assertThat(reached).isEmpty();

        // What the scenario DOES name goes through, confirm-ish or not.
        click.call("{\"element\":\"Fatura İptal Et butonu\",\"ref\":\"e8\"}");
        click.call("{\"element\":\"Geri butonu\",\"ref\":\"e9\"}");
        assertThat(reached).containsExactly("browser_click", "browser_click");
    }

    @Test
    @DisplayName("12.11.2026: a click by reference alone is judged by what the page says the reference is")
    void referenceOnlyClickIsReadOffThePage() {
        String scenario = fold("3. İşlemler panelindeki \"Fatura İptal Et\" düğmesine tıkla\n"
                + "4. Açılan ekranda \"Geri\" düğmesine tıkla");
        RunVerdict verdict = new RunVerdict();
        List<String> reached = new ArrayList<>();
        ActionTape tape = ActionTape.open(dir, "explore", scenario, "", "", List.of());
        // As MCP really returns it: a JSON array, the tree's line breaks escaped.
        String tree = "- generic [ref=f4e1]:\\n"
                + "  - button \\\"Evet\\\" [ref=f4e20] [cursor=pointer]\\n"
                + "  - link [ref=f4e21] [cursor=pointer]:\\n"
                + "    - text: Geri\\n"
                + "  - link \\\"Silinmiş Faturalar\\\" [ref=f4e22] [cursor=pointer]";
        tape.record("browser_snapshot", "{}", ActionTape.Outcome.OK,
                "[{\"text\":\"### Page\\n- Page URL: x\\n### Snapshot\\n```yaml\\n" + tree + "\\n```\"}]");
        AiRunPolicy policy = new AiRunPolicy(null, java.time.Duration.ZERO, 0, 220, 6000, () -> 0L);
        Object[] tools = BudgetedTool.wrap(List.of(fake("browser_click", reached)),
                99, null, List.of(), new LinkedHashSet<>(), scenario, new AtomicBoolean(), tape,
                verdict, policy);
        ToolCallback click = (ToolCallback) tools[0];

        assertThat(click.call("{\"target\": \"f4e20\"}")).contains("REFUSED").contains("evet");
        assertThat(reached).isEmpty();

        // Named only by a child's text, and asked for: goes through.
        click.call("{\"target\": \"f4e21\"}");
        // "sil" inside a word of the page's own text is not a delete button.
        click.call("{\"target\": \"f4e22\"}");
        assertThat(reached).containsExactly("browser_click", "browser_click");
    }

    @Test
    @DisplayName("12.11.2026, d88e4b14: click failed, refused, refused… — after four the walk ends in code")
    void stuckWalkIsStopped() {
        RunVerdict verdict = new RunVerdict();
        List<String> reached = new ArrayList<>();
        ActionTape tape = ActionTape.open(dir, "explore", "x", "", "", List.of());
        AiRunPolicy policy = new AiRunPolicy(null, java.time.Duration.ZERO, 0, 220, 6000, () -> 0L);
        ToolCallback failing = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("browser_click").description("c").inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                reached.add("browser_click");
                // Not an overlay error: those are waited out first (BusyPageTest).
                return "### Error\nTimeout 5000ms exceeded: element is not visible";
            }
        };
        Object[] tools = BudgetedTool.wrap(
                List.of(failing, fake("browser_snapshot", reached), fake(RunVerdict.TOOL, reached)),
                99, null, List.of(), new LinkedHashSet<>(), "x", new AtomicBoolean(), tape, verdict, policy);
        ToolCallback click = (ToolCallback) tools[0];
        ToolCallback look = (ToolCallback) tools[1];

        // The measured shape: the same click, a look, the same click again.
        click.call("{\"target\": \"f1e204\"}");                    // failed
        look.call("{}");
        assertThat(click.call("{\"target\": \"f1e204\"}")).contains("REFUSED"); // same failure
        look.call("{}");
        click.call("{\"target\": \"f1e204\"}");
        click.call("{\"target\": \"f1e204\"}");

        assertThatThrownBy(() -> look.call("{}")).isInstanceOf(BudgetedTool.WalkOver.class)
                .hasMessageContaining("üst üste olmadı");
        assertThatThrownBy(() -> click.call("{\"target\": \"f1e999\"}")).isInstanceOf(BudgetedTool.WalkOver.class);
        assertThat(reached).containsExactly("browser_click", "browser_snapshot", "browser_snapshot");

        ((ToolCallback) tools[2]).call("{\"succeeded\":[],\"failed\":[\"Ekran yüklenmedi.\"],\"passed\":false}");
        assertThat(verdict.isClosed()).isTrue();
    }

    @Test
    @DisplayName("an action that works resets the count")
    void successResetsTheCount() {
        AiRunPolicy policy = new AiRunPolicy(null, java.time.Duration.ZERO, 0, 220, 6000, () -> 0L);
        for (int i = 0; i < AiRunPolicy.STUCK_AFTER - 1; i++) {
            policy.missed("browser_click");
        }
        policy.outcome("browser_click", "{}", "ok", false);
        policy.missed("browser_click");
        assertThat(policy.stuck()).isFalse();
    }

    /** BudgetedTool folds the instruction before storing it; the test must match. */
    private static String fold(String text) {
        return text.replaceAll("\s+", " ").trim().toLowerCase(java.util.Locale.forLanguageTag("tr-TR"));
    }

    private static ToolCallback fake(String name, List<String> reached) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                reached.add(name);
                return "ok";
            }
        };
    }
}
