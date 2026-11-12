package orchestrator.explore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The answer an agent gets when it names its target with words.
 *
 * Both failure texts are copied from the console of the 12.11.2026 walk that
 * spent seventeen turns on them — the "san" search in Firma Seçimi, where the
 * search box sat past the part of a 20.192-character page the model was shown.
 */
class TargetHintTest {

    @TempDir
    Path dir;

    /** {@code textbox "Seçiniz"} handed to the CSS parser. */
    private static final String PARSED_AS_CSS =
            "Error: browserBackend.callTool: Unexpected token \"\" while parsing css selector \"\"."
                    + " Did you mean to CSS.escape it?";

    /** {@code textbox} as a tag name, which no element on the page has. */
    private static final String NO_SUCH_ELEMENT = "Error: \"textbox\" does not match any elements.";

    @Test
    @DisplayName("a target written as words is answered with what a target is, and what to use instead")
    void explainsTheTargetAndNamesTheKeyboardTool() {
        for (String failure : List.of(PARSED_AS_CSS, NO_SUCH_ELEMENT)) {
            assertThat(BudgetedTool.targetHint(failure))
                    .contains("THE TARGET IS WRONG")
                    .contains("e42")
                    .contains("browser_press_sequentially")
                    .contains("no target");
        }
    }

    @Test
    @DisplayName("any other failure gets no hint, so every other error stays as it was")
    void staysOutOfOtherFailures() {
        assertThat(BudgetedTool.targetHint("Timeout 5000ms exceeded")).isNull();
        assertThat(BudgetedTool.targetHint(null)).isNull();
    }

    @Test
    @DisplayName("the walk gets the error AND the hint back, instead of the bare error being thrown")
    void theHintReachesTheModel() {
        ToolCallback type = BudgetedToolCaller.wrapOne(dir, "browser_type", NO_SUCH_ELEMENT);

        String answer = type.call("{\"target\": \"textbox\", \"text\": \"san\"}");

        assertThat(answer).contains(NO_SUCH_ELEMENT).contains("browser_press_sequentially");
    }

    @Test
    @DisplayName("an error with no hint is still thrown, exactly as before")
    void otherErrorsAreStillThrown() {
        ToolCallback type = BudgetedToolCaller.wrapOne(dir, "browser_type", "Timeout 5000ms exceeded");

        assertThatThrownBy(() -> type.call("{\"target\": \"e15\", \"text\": \"san\"}"))
                .hasMessageContaining("Timeout 5000ms exceeded");
    }

    @Test
    @DisplayName("the keyboard tool is offered to AI ile çalıştır, and the page comes back after it")
    void theKeyboardToolIsOfferedAndActs() {
        assertThat(ExploreController.AI_RUN_TOOLS).contains("browser_press_sequentially");
        assertThat(AiRunPolicy.ACTING).contains("browser_press_sequentially");
    }

    /** One wrapped tool that always fails with the given text. */
    private static final class BudgetedToolCaller {
        static ToolCallback wrapOne(Path dir, String name, String failure) {
            ToolCallback failing = new ToolCallback() {
                @Override
                public ToolDefinition getToolDefinition() {
                    return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
                }

                @Override
                public String call(String input) {
                    throw new IllegalStateException(failure);
                }
            };
            ActionTape tape = ActionTape.open(dir, "explore", "x", "", "", List.of());
            Object[] tools = BudgetedTool.wrap(List.of(failing), 99, null, List.of(),
                    new LinkedHashSet<>(), "", new AtomicBoolean(), tape, null, null);
            return (ToolCallback) tools[0];
        }
    }
}
