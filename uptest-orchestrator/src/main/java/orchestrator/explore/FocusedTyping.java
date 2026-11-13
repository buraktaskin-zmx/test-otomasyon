package orchestrator.explore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * {@code browser_press_sequentially} — typing into whatever has the keyboard
 * focus — for an MCP that does not serve it.
 *
 * <p><b>Measured 13.11.2026, Mac, MCP 0.0.82.</b> "Firma Seçimi listesinde
 * arama yapılır": the agent opened the dropdown and, exactly as RUN_SYSTEM
 * tells it to, called {@code browser_press_sequentially} with "san". Spring
 * answered {@code No ToolCallback found for tool name:
 * browser_press_sequentially} and the whole run died. The name was in
 * {@code AI_RUN_TOOLS}, but the toolbox is the MCP's tools FILTERED by that
 * list, so a name the server does not serve is silently not there — the
 * prompt, the target hints and do_steps all pointed the agent at a tool it
 * did not have. The 82008f1 note "0.0.82'de var" had never met a real run.
 *
 * <p>So the code provides it, from two tools every walk already depends on:
 * {@code browser_evaluate} marks the focused box (an opened Select2 puts the
 * focus in its search box; when nothing editable has it, the open Select2's
 * search box is taken instead), and {@code browser_type} — the tool the login
 * types with, a CSS selector as {@code target}, {@code slowly} for real key
 * events — types into the mark. Real key events matter: Select2 filters on
 * them, and a value set in one go filters nothing.
 *
 * <p>Offered only when the MCP lacks the tool; where it has one, the MCP's
 * own is used unchanged.
 */
public class FocusedTyping {

    /** The name the prompt, the target hints, do_steps and the cache already use. */
    public static final String TOOL = "browser_press_sequentially";

    /** Set on the one element typed into; cleared from every other first. */
    static final String MARK = "data-uptest-focus";

    /** Marks the focused editable element, or the open Select2's search box. */
    static final String TAG = """
            () => {
              document.querySelectorAll('[data-uptest-focus]').forEach(e => e.removeAttribute('data-uptest-focus'));
              const editable = e => !!e && (e.isContentEditable || e.tagName === 'TEXTAREA'
                || (e.tagName === 'INPUT' && !['hidden', 'radio', 'checkbox', 'button', 'submit'].includes(e.type)));
              let e = document.activeElement;
              if (!editable(e)) {
                e = document.querySelector('.select2-container--open .select2-search__field, .select2-drop-active input.select2-input');
              }
              if (!editable(e)) return 'NOFOCUS';
              e.setAttribute('data-uptest-focus', '1');
              return 'FOCUS ' + e.tagName + '.' + (e.className || '');
            }""";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolCallback evaluate;
    private final ToolCallback type;

    private FocusedTyping(ToolCallback evaluate, ToolCallback type) {
        this.evaluate = evaluate;
        this.type = type;
    }

    /**
     * Null when the MCP serves the tool itself, or lacks what this needs
     * (an evaluate that takes {@code function}, and browser_type).
     */
    static FocusedTyping bind(ToolCallback[] callbacks) {
        if (find(callbacks, TOOL) != null) {
            return null;
        }
        ToolCallback evaluate = find(callbacks, "browser_evaluate");
        ToolCallback type = find(callbacks, "browser_type");
        if (evaluate == null || type == null || evaluate.getToolDefinition().inputSchema() == null
                || !evaluate.getToolDefinition().inputSchema().contains("\"function\"")) {
            return null;
        }
        return new FocusedTyping(evaluate, type);
    }

    private static ToolCallback find(ToolCallback[] callbacks, String name) {
        if (callbacks == null) {
            return null;
        }
        for (ToolCallback callback : callbacks) {
            if (name.equals(callback.getToolDefinition().name())) {
                return callback;
            }
        }
        return null;
    }

    @Tool(name = TOOL, description = "Type text into the element that has the keyboard focus, with NO "
            + "target. Use it for an opened dropdown's search box: click the dropdown once, then call "
            + "this with just the text.")
    public String pressSequentially(
            @ToolParam(description = "The text to type, exactly as it should appear") String text) {
        if (text == null || text.isEmpty()) {
            return "### Error\nNo text given: pass the text to type.";
        }
        String tagged;
        try {
            tagged = resultOf(evaluate.call("{\"function\":" + json(TAG) + "}"));
        } catch (RuntimeException failed) {
            return "### Error\nThe focused box could not be found: " + failed.getMessage();
        }
        if (tagged == null || tagged.contains("NOFOCUS") || !tagged.contains("FOCUS")) {
            System.out.println("[uptest] explore: " + TOOL + " — odakta yazilacak kutu yok");
            return "### Error\nNothing on the page has the keyboard focus to type into, and no dropdown is "
                    + "open. Click the dropdown (or the box) first, then call " + TOOL + " with the text.";
        }
        System.out.println("[uptest] explore: " + TOOL + " kodla — "
                + tagged.substring(tagged.indexOf("FOCUS")).lines().findFirst().orElse("").strip());
        return type.call("{\"target\":" + json("[" + MARK + "=\"1\"]") + ",\"text\":" + json(text)
                + ",\"slowly\":true}");
    }

    /**
     * Only the {@code ### Result} section: the answer also echoes the code it
     * ran, and that code contains both words this looks for.
     */
    static String resultOf(String answer) {
        String plain = answer == null ? "" : LeanResults.unwrap(answer);
        int at = plain.indexOf("### Result");
        if (at < 0) {
            return null;
        }
        int end = plain.indexOf("###", at + 10);
        return end < 0 ? plain.substring(at) : plain.substring(at, end);
    }

    private static String json(String value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
