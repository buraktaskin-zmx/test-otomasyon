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

        report.call("{\"warnings\":[\"Garantili Ödeme'ye dokunulmadı.\"]}");
        assertThat(reached).containsExactly(RunVerdict.TOOL);

        verdict.reportResult(List.of("a"), List.of(), List.of(), true);
        String refused = click.call("{\"element\":\"Devam\"}");
        assertThat(reached).containsExactly(RunVerdict.TOOL);
        assertThat(refused).contains("already been reported");
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
