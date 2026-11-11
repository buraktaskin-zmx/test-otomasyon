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
    @DisplayName("once the budget is spent every tool is refused, except the one that ends the walk")
    void spentBudgetStopsEverythingButTheReport() {
        RunVerdict verdict = new RunVerdict();
        List<String> reached = new ArrayList<>();
        ActionTape tape = ActionTape.open(dir, "explore", "x", "", "", List.of());
        AiRunPolicy broke = new AiRunPolicy(null, java.time.Duration.ZERO, 100, 220, 6000, () -> 100_000L);
        Object[] tools = BudgetedTool.wrap(
                List.of(fake(RunVerdict.TOOL, reached), fake("browser_click", reached)),
                999, null, List.of(), new LinkedHashSet<>(), "", new AtomicBoolean(), tape,
                verdict, broke);

        String refused = ((ToolCallback) tools[1]).call("{\"element\":\"Devam\"}");
        assertThat(refused).contains("TOKEN BUDGET SPENT").contains("report_result");
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
