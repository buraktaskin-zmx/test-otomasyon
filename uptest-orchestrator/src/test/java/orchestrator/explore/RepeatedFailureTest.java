package orchestrator.explore;

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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The same failing call, sent again on a page nothing has changed: refused.
 *
 * The 12.11.2026 walk sent {@code textbox "Seçiniz"} and {@code textbox} five
 * times each and got the same error every time.
 */
class RepeatedFailureTest {

    @TempDir
    Path dir;

    private final List<String> reached = new ArrayList<>();

    private static final String BAD = "{\"target\":\"textbox \\\"Seçiniz\\\"\",\"text\":\"san\"}";
    private static final String OTHER_BAD = "{\"target\":\"textbox\",\"text\":\"san\"}";

    @Test
    @DisplayName("the identical failed call, nothing in between: the second one never reaches the browser")
    void secondIdenticalFailureIsRefused() {
        Object[] tools = tools(policy());
        ToolCallback type = (ToolCallback) tools[1];

        assertThat(type.call(BAD)).contains("does not match any elements").contains("THE TARGET IS WRONG");
        String refused = type.call(BAD);

        assertThat(refused).contains("REFUSED").contains("exactly this call")
                .contains("does not match any elements");
        assertThat(reached).containsExactly("browser_type");
    }

    @Test
    @DisplayName("different arguments are a different call and go through")
    void differentArgumentsPass() {
        ToolCallback type = (ToolCallback) tools(policy())[1];
        type.call(BAD);
        type.call(OTHER_BAD);
        assertThat(reached).containsExactly("browser_type", "browser_type");
    }

    @Test
    @DisplayName("a successful action changes the page, so the failed call may be tried again after it")
    void successfulActionForgets() {
        Object[] tools = tools(policy());
        ToolCallback click = (ToolCallback) tools[0];
        ToolCallback type = (ToolCallback) tools[1];

        type.call(BAD);
        click.call("{\"target\":\"e42\"}");
        type.call(BAD);
        assertThat(reached).containsExactly("browser_type", "browser_click", "browser_type");
    }

    @Test
    @DisplayName("a call that succeeded may be repeated: only failures are remembered")
    void successesAreNotRefused() {
        ToolCallback click = (ToolCallback) tools(policy())[0];
        click.call("{\"target\":\"e42\"}");
        click.call("{\"target\":\"e42\"}");
        assertThat(reached).containsExactly("browser_click", "browser_click");
    }

    @Test
    @DisplayName("a failure with no hint is still rethrown the first time, and refused the second")
    void rethrownFailureIsRememberedToo() {
        ToolCallback type = (ToolCallback) tools(policy())[1];
        String odd = "{\"target\":\"e7\",\"text\":\"boom\"}";
        assertThatThrownBy(() -> type.call(odd)).hasMessageContaining("element is detached");
        assertThat(type.call(odd)).contains("REFUSED").contains("element is detached");
    }

    @Test
    @DisplayName("MCP's '### Error' heading in a returned result counts as a failure")
    void returnedErrorCounts() {
        AiRunPolicy policy = policy();
        policy.outcome("browser_click", "{\"target\":\"x\"}", "### Error\nTimeout 5000ms", false);
        assertThat(policy.failedBefore("browser_click", "{\"target\":\"x\"}")).contains("Timeout");
        assertThat(policy.failedBefore("browser_click", "{\"target\":\"y\"}")).isNull();
        assertThat(policy.failedBefore("browser_type", "{\"target\":\"x\"}")).isNull();
    }

    @Test
    @DisplayName("Ekran Keşfi has no policy and keeps today's behaviour")
    void noPolicyNoRefusal() {
        ToolCallback type = (ToolCallback) tools(null)[1];
        type.call(BAD);
        type.call(BAD);
        assertThat(reached).containsExactly("browser_type", "browser_type");
    }

    private static AiRunPolicy policy() {
        return new AiRunPolicy(null, Duration.ZERO, 0, 220, 6000, () -> 0L);
    }

    private Object[] tools(AiRunPolicy policy) {
        ActionTape tape = ActionTape.open(dir, "explore", "x", "", "", List.of());
        return BudgetedTool.wrap(List.of(tool("browser_click"), tool("browser_type")),
                99, null, List.of(), new LinkedHashSet<>(), "", new AtomicBoolean(), tape,
                new RunVerdict(), policy);
    }

    /** Fails on the invented targets the way MCP does; anything else succeeds. */
    private ToolCallback tool(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                reached.add(name);
                if (input.contains("textbox")) {
                    throw new IllegalStateException("Error: \"textbox\" does not match any elements.");
                }
                if (input.contains("boom")) {
                    throw new IllegalStateException("Error: element is detached from the DOM");
                }
                return "ok";
            }
        };
    }
}
