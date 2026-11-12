package orchestrator.explore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ai-run 01604939, 12.11.2026: invoices loading under an overlay, the agent pressing F5. */
class BusyPageTest {

    /** As SyncMcpToolCallback logged it, trimmed. */
    private static final String INTERCEPTED = """
            ### Error
            TimeoutError: browserBackend.callTool: Timeout 5000ms exceeded.
              - <div class="overlay-full nonprintable"></div> intercepts pointer events""";

    @Test
    @DisplayName("a click under the loading overlay is tried again until the overlay is gone")
    void clickIsRetriedUnderTheOverlay() {
        int[] tries = {0};
        String result = BusyPage.waitOut("browser_click", () -> {
            if (++tries[0] <= 2) {
                throw new IllegalStateException(INTERCEPTED);
            }
            return "clicked";
        });
        assertThat(result).isEqualTo("clicked");
        assertThat(tries[0]).isEqualTo(3);
    }

    @Test
    @DisplayName("an overlay that never goes: after the last attempt the error reaches the agent")
    void overlayThatStaysGivesUp() {
        int[] tries = {0};
        assertThatThrownBy(() -> BusyPage.waitOut("browser_click", () -> {
            tries[0]++;
            throw new IllegalStateException(INTERCEPTED);
        })).hasMessageContaining("intercepts pointer events");
        assertThat(tries[0]).isEqualTo(BusyPage.ATTEMPTS);
    }

    @Test
    @DisplayName("any other error is not retried")
    void otherErrorsAreNotRetried() {
        int[] tries = {0};
        assertThatThrownBy(() -> BusyPage.waitOut("browser_click", () -> {
            tries[0]++;
            throw new IllegalStateException("does not match any elements");
        }));
        assertThat(tries[0]).isEqualTo(1);
    }

    @Test
    @DisplayName("F5 and friends are refused; Escape and Enter are not")
    void reloadIsRefused() {
        assertThat(BusyPage.reload("browser_press_key", "{\"key\": \"F5\"}")).contains("reloading");
        assertThat(BusyPage.reload("browser_press_key", "{\"key\": \"Control+R\"}")).isNotNull();
        assertThat(BusyPage.reload("browser_press_key", "{\"key\": \"Escape\"}")).isNull();
        assertThat(BusyPage.reload("browser_press_key", "{\"key\": \"Enter\"}")).isNull();
        assertThat(BusyPage.reload("browser_click", "{\"key\": \"F5\"}")).isNull();
    }

    private static ToolCallback evaluate(List<String> answers, String schema) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("browser_evaluate").description("e").inputSchema(schema).build();
            }

            @Override
            public String call(String input) {
                return answers.isEmpty() ? "### Result\n\"IDLE\"" : answers.remove(0);
            }
        };
    }

    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"function\":{\"type\":\"string\"}}}";

    @Test
    @DisplayName("the page is read only once nothing covers it")
    void waitsWhileBusy() {
        List<String> answers = new ArrayList<>(List.of(
                // The echoed code carries the word BUSY too; only the Result counts.
                "### Result\n\"BUSY\"\n### Ran Playwright code\n```js\nawait page.evaluate('BUSY')\n```",
                "### Result\n\"BUSY\"",
                "### Ran Playwright code\n```js\n'BUSY' : 'IDLE'\n```\n### Result\n\"IDLE\""));
        BusyPage.waitWhileBusy(evaluate(answers, SCHEMA), Duration.ZERO);
        assertThat(answers).isEmpty();
    }

    @Test
    @DisplayName("no evaluate tool, or one without a function argument: no check, no failure")
    void missingProbeIsSkipped() {
        BusyPage.waitWhileBusy(null, Duration.ZERO);
        List<String> answers = new ArrayList<>(List.of("### Result\n\"BUSY\""));
        BusyPage.waitWhileBusy(evaluate(answers, "{\"type\":\"object\",\"properties\":{\"code\":{}}}"),
                Duration.ZERO);
        assertThat(answers).hasSize(1);
    }
}
