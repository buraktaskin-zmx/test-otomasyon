package orchestrator.explore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 13.11.2026, Mac, MCP 0.0.82: the agent called browser_press_sequentially,
 * the MCP did not serve it, and Spring's "No ToolCallback found" ended the
 * run. These hold the code's own version to what the walk needs.
 */
class FocusedTypingTest {

    @TempDir
    Path dir;

    private static final String EVALUATE_SCHEMA = "{\"properties\":{\"function\":{\"type\":\"string\"}}}";

    /** What MCP answers an evaluate with: the code it ran (which contains NOFOCUS too), then the result. */
    private static String answer(String result) {
        return "### Ran Playwright code\n```js\nawait page.evaluate('() => { … return \\'NOFOCUS\\'; … }');\n```\n"
                + "### Result\n\"" + result + "\"\n";
    }

    private static ToolCallback tool(String name, String schema, Function<String, String> answer) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name).inputSchema(schema).build();
            }

            @Override
            public String call(String input) {
                return answer.apply(input);
            }
        };
    }

    @Test
    @DisplayName("the focused search box is marked, then typed into with real key events")
    void typesIntoTheFocusedBox() {
        List<String> typed = new ArrayList<>();
        ToolCallback[] mcp = {
                tool("browser_evaluate", EVALUATE_SCHEMA, in -> answer("FOCUS INPUT.select2-search__field")),
                tool("browser_type", "{}", in -> {
                    typed.add(in);
                    return "### Ran Playwright code\n```js\nawait page.locator('[data-uptest-focus=\"1\"]')"
                            + ".pressSequentially('san');\n```";
                })};
        FocusedTyping typing = FocusedTyping.bind(mcp);
        assertThat(typing).isNotNull();
        String result = typing.pressSequentially("san");
        assertThat(result).doesNotContain("### Error");
        assertThat(typed).singleElement().asString()
                .contains("\"target\":\"[data-uptest-focus=\\\"1\\\"]\"")
                .contains("\"text\":\"san\"")
                .contains("\"slowly\":true");
    }

    @Test
    @DisplayName("nothing focused and no dropdown open: an error saying what to do, nothing typed")
    void nothingFocused() {
        List<String> typed = new ArrayList<>();
        FocusedTyping typing = FocusedTyping.bind(new ToolCallback[]{
                tool("browser_evaluate", EVALUATE_SCHEMA, in -> answer("NOFOCUS")),
                tool("browser_type", "{}", in -> {
                    typed.add(in);
                    return "";
                })});
        assertThat(typing.pressSequentially("san")).startsWith("### Error").contains("Click the dropdown");
        assertThat(typed).isEmpty();
    }

    @Test
    @DisplayName("an MCP that serves the tool keeps its own; one without evaluate gets none")
    void onlyWhenMissing() {
        assertThat(FocusedTyping.bind(new ToolCallback[]{
                tool(FocusedTyping.TOOL, "{}", in -> ""),
                tool("browser_evaluate", EVALUATE_SCHEMA, in -> ""),
                tool("browser_type", "{}", in -> "")})).isNull();
        assertThat(FocusedTyping.bind(new ToolCallback[]{tool("browser_type", "{}", in -> "")})).isNull();
    }

    @Test
    @DisplayName("tape 4f5f0598: a failed typing answered as text is an error, and never reaches the cache")
    void aFailedTypingIsNotCached() {
        // Spring hands a @Tool's String answer back JSON-quoted: that is how it sat on the tape.
        String failed = "\"### Error\\nNothing on the page has the keyboard focus to type into, and no dropdown"
                + " is open.\"";
        assertThat(BudgetedTool.reportsError(failed)).isTrue();
        assertThat(BudgetedTool.reportsError("### Error\nlocator.click: Timeout")).isTrue();
        assertThat(BudgetedTool.reportsError("[{\"text\":\"### Ran Playwright code\\n```js\\nawait x;\\n```\"}]"))
                .isFalse();

        String opened = "### Ran Playwright code\n```js\nawait page.getByRole('textbox', { name: 'Seçiniz' })"
                + ".click();\n```";
        String typed = "\"### Ran Playwright code\\n```js\\nawait page.locator('[data-uptest-focus=\\\"1\\\"]')"
                + ".pressSequentially('ASAŞ ALÜMİNYUM SAN.');\\n```\"";
        String args = "{\"text\":\"ASAŞ ALÜMİNYUM SAN.\"}";
        List<TapeRenderer.Call> walk = List.of(
                new TapeRenderer.Call(2, AiRunPrelude.MENU_TOOL, "{}", "OK", "açıldı"),
                new TapeRenderer.Call(4, "browser_click", "{\"target\":\"e423\"}", "OK", opened),
                new TapeRenderer.Call(6, "browser_click", "{\"target\":\"e423\"}", "OK", opened),
                new TapeRenderer.Call(8, FocusedTyping.TOOL, args, "OK", failed),
                new TapeRenderer.Call(11, "browser_click", "{\"target\":\"e423\"}", "OK", opened),
                new TapeRenderer.Call(13, FocusedTyping.TOOL, args, "OK", typed));
        ReplayCalls.Extracted extracted = ReplayCalls.fromWalk(walk, java.time.LocalDate.now());
        assertThat(extracted.calls()).extracting(ReplayCalls.ReplayCall::tool)
                .containsExactly("browser_click", "browser_click", "browser_click", FocusedTyping.TOOL);
    }

    @Test
    @DisplayName("Hızlı çalıştır replays a recorded browser_press_sequentially through the same code")
    void replayUsesIt() {
        List<String> typed = new ArrayList<>();
        ToolCallback[] mcp = {
                tool("browser_evaluate", EVALUATE_SCHEMA, in -> in.contains("BUSY") ? "### Result\n\"IDLE\"\n"
                        : answer("FOCUS INPUT.select2-search__field")),
                tool("browser_type", "{}", in -> {
                    typed.add(in);
                    return "### Ran Playwright code\n```js\nawait page.locator('x').pressSequentially('san');\n```";
                }),
                tool("browser_snapshot", "{}", in -> "### Page\n- textbox [ref=e1]")};
        ReplayCalls.ReplayCall call = new ReplayCalls.ReplayCall(FocusedTyping.TOOL, null, "{\"text\":\"san\"}",
                null, List.of("san"), "yaz \"san\"", null);
        FastReplay.Result walk = new FastReplay(mcp, ActionTape.open(dir, "explore", "t", "", "", List.of()),
                java.time.Duration.ZERO).run(List.of(call), java.time.LocalDate.now(), page -> page);
        assertThat(walk.complete()).isTrue();
        assertThat(typed).singleElement().asString().contains("\"text\":\"san\"");
    }
}
