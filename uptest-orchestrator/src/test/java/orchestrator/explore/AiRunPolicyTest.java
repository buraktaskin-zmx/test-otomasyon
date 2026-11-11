package orchestrator.explore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

class AiRunPolicyTest {

    @TempDir
    Path dir;

    private final AtomicInteger snapshots = new AtomicInteger();
    private final AtomicLong spent = new AtomicLong();

    private final ToolCallback snapshot = new ToolCallback() {
        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name("browser_snapshot").description("s").inputSchema("{}").build();
        }

        @Override
        public String call(String input) {
            snapshots.incrementAndGet();
            return "### Page\n- Page Snapshot:\n- button \"Kaydet\" [ref=e5]";
        }
    };

    private AiRunPolicy policy(long budget) {
        return new AiRunPolicy(snapshot, Duration.ZERO, budget, spent::get);
    }

    private ActionTape tape() {
        return ActionTape.open(dir, "explore", "x", "", "", List.of());
    }

    @Test
    @DisplayName("after a click the page comes back with it, and the tape keeps it as its own snapshot")
    void actionGetsThePage() {
        ActionTape tape = tape();
        String out = policy(0).after("browser_click", "Clicked", UnaryOperator.identity(), tape);
        assertThat(out).startsWith("Clicked").contains("button \"Kaydet\"");
        assertThat(snapshots).hasValue(1);
        assertThat(tape.calls()).extracting(TapeRenderer.Call::tool).containsExactly("browser_snapshot");
    }

    @Test
    @DisplayName("a result that already carries the page, or a tool that does not act, gets no second read")
    void noDoubleRead() {
        policy(0).after("browser_click", "### Page\n- Page Snapshot:\n...", UnaryOperator.identity(), tape());
        policy(0).after("browser_snapshot", "x", UnaryOperator.identity(), tape());
        assertThat(snapshots).hasValue(0);
    }

    @Test
    @DisplayName("the budget note: nothing under 80%, a nudge past it, a firmer one past 100% — never a refusal")
    void budgetNote() {
        AiRunPolicy policy = policy(100_000);
        spent.set(79_999);
        assertThat(policy.budgetNote()).isEmpty();
        spent.set(85_000);
        assertThat(policy.budgetNote()).contains("%85").contains("report_result");
        spent.set(120_000);
        assertThat(policy.budgetNote()).contains("AŞILDI");
        assertThat(policy(0).budgetNote()).isEmpty();
    }
}
