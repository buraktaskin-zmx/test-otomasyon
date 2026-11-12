package orchestrator.explore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Several steps in one turn, carried out by us instead of one per model call.
 *
 * <p><b>Why this exists.</b> Every tool call is a whole turn, and a turn pays
 * again for the system prompt, the tool definitions, the chat template's own
 * markup and the current page. Measured 12.11.2026: a walk of 22 turns cost
 * 125.000 tokens, half of it pages — so a four-field form, which is five
 * sequential clicks and types, cost five pages and five of everything else to
 * do something with no decision in it. The model is not deciding anything
 * between "type the invoice number" and "type the amount"; it is paying for
 * the privilege of being asked again.
 *
 * <p>So the model says what the next few steps are, once, and this runs them.
 *
 * <p><b>It is not a blind plan, and that is the whole design.</b> Three things
 * make a batch no less careful than the same steps taken one turn at a time:
 *
 * <ol>
 *   <li><b>Every step goes through the ordinary guards.</b> The steps are not
 *       sent to the browser directly — they are sent through the very same
 *       {@link BudgetedTool}-wrapped callbacks the model would have called, so
 *       the off-limits list, the unasked-confirmation refusal, the closed
 *       browser, the token wall and the action tape all behave exactly as they
 *       do for a single call. Hiding a tool from the model was never a safety
 *       boundary; the guards are, and a batch does not get past them.
 *   <li><b>It stops at the first step that did not happen.</b> A refusal or a
 *       thrown error ends the batch there, and the model is told which step
 *       and why.
 *   <li><b>It looks at the page between steps.</b> After each step the page is
 *       read — an MCP call, no tokens — and if the application has started
 *       SAYING something it was not saying before the batch began (a
 *       validation message, a confirmation dialog), the batch stops. That is
 *       the one failure a plan written in advance can walk into, and it is
 *       caught in code rather than hoped against.
 * </ol>
 *
 * <p>What the model gets back is one line per step and ONE page: the screen as
 * it stands after the last step that ran.
 *
 * <p><b>The tape never sees this tool.</b> Each sub-step is recorded under the
 * real tool it used ({@code browser_click}, {@code browser_type}, …) because
 * that is what the wrapped callback records, so TapeRenderer writes the same
 * Gherkin whether the agent took five turns or one. {@code do_steps} itself is
 * in TapeRenderer's silent list.
 */
public class StepRunner {

    static final String TOOL = "do_steps";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * What a step can be, and the tool each one goes to.
     *
     * Deliberately small. These are the actions that have no decision in them
     * once the element is known — which is exactly the set worth batching.
     * Anything else (reading the page, handling a dialog, reporting the
     * result) stays a turn of its own, because the model has to look at what
     * came back before it can choose what to do next.
     */
    private static final Map<String, String> TOOL_OF = Map.of(
            "click", "browser_click",
            "type", "browser_type",
            "select", "browser_select_option",
            "press", "browser_press_key",
            "date", "set_date");

    /** One step of a batch. */
    public record Step(
            @ToolParam(description = "click, type, select, press or date") String action,
            @ToolParam(description = "The element's reference from the page, e.g. e42. Not needed "
                    + "for press.", required = false) String ref,
            @ToolParam(description = "What the element is, in a few words, as the page names it",
                    required = false) String element,
            @ToolParam(description = "The text to type, the option to choose, the date as "
                    + "gg/aa/yyyy, or the key to press. Not needed for click.",
                    required = false) String value) {
    }

    private final int maxSteps;
    private Map<String, ToolCallback> tools = Map.of();
    private AiRunPolicy policy;
    private ActionTape tape;

    StepRunner(int maxSteps) {
        this.maxSteps = Math.max(1, maxSteps);
    }

    /**
     * Hands the runner the WRAPPED tools, after they have been built.
     *
     * Two-phase on purpose, and the order is the reason: the runner has to be
     * among the tool objects that get wrapped (so the batch itself is guarded
     * and recorded like any other call), and it needs the results of that same
     * wrapping to drive its steps. {@code DateTool.bind} has the same shape
     * for the opposite reason — it binds to the RAW callbacks, because its
     * four browser calls are one date and must not be counted four times.
     */
    void use(Map<String, ToolCallback> wrapped, AiRunPolicy policy, ActionTape tape) {
        this.tools = Map.copyOf(wrapped);
        this.policy = policy;
        this.tape = tape;
    }

    @Tool(name = TOOL,
            description = "Carry out several steps in ONE call, in order. Use this whenever the next "
                    + "steps are decided — filling a form, picking a row and pressing a button. Each "
                    + "step is checked as it runs and the batch stops at the first one that fails or "
                    + "the moment the screen starts complaining; you get one line per step and the "
                    + "page as it stands at the end. Do not put a step in here that depends on what "
                    + "an earlier step will reveal.")
    public String doSteps(@ToolParam(description = "The steps, in order, at most eight")
                          List<Step> steps) {
        return run(steps == null ? List.of() : steps);
    }

    /**
     * The arguments as the model actually sends them.
     *
     * A list of objects is the shape a model gets wrong most often, and one
     * finished walk was already lost to exactly this on {@code report_result}:
     * the arrays arrived written out as a STRING, Spring's reflection threw
     * before any of our code ran, and the whole run reported nothing. So the
     * shapes are all accepted here instead — the list under {@code steps},
     * under {@code actions}, as a bare array, as a string holding an array, or
     * as one object meaning a batch of one. A step itself may be an object or
     * a sentence like {@code "type e15 955231"}.
     */
    String acceptJson(String arguments) {
        List<Step> steps;
        try {
            steps = parse(arguments);
        } catch (RuntimeException unreadable) {
            return "do_steps could not read its arguments (" + unreadable.getMessage()
                    + "). Send: {\"steps\":[{\"action\":\"click\",\"ref\":\"e42\","
                    + "\"element\":\"Fatura İptal Et butonu\"}]}";
        }
        if (steps.isEmpty()) {
            return "do_steps was called with no steps. Send at least one, for example "
                    + "{\"steps\":[{\"action\":\"type\",\"ref\":\"e15\",\"element\":\"Fatura No\","
                    + "\"value\":\"955231\"}]}";
        }
        if (steps.size() > maxSteps) {
            // Not truncated: a batch half-carried-out is worse than one not
            // started, because the model would have to work out which half.
            return "do_steps takes at most " + maxSteps + " steps and you sent " + steps.size()
                    + ". Send the first " + maxSteps + " and the rest in the next call.";
        }
        return run(steps);
    }

    /* ------------------------------------------------------------------ */

    private String run(List<Step> steps) {
        if (steps.isEmpty()) {
            return "do_steps was called with no steps.";
        }
        if (tools.isEmpty() || policy == null || tape == null) {
            return "do_steps is not available on this walk; take the steps one at a time.";
        }

        // What the screen was already saying before anything was touched. A
        // page that permanently carries a status line would otherwise stop
        // every batch after its first step — the test is "did something NEW
        // appear", not "is there an alert anywhere".
        String before = LeanPage.complaint(policy.pageNow(tape));

        List<String> log = new ArrayList<>();
        String page = null;
        String halted = null;

        policy.quiet(true);
        try {
            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                String action = action(step);
                String target = TOOL_OF.get(action);
                ToolCallback tool = target == null ? null : tools.get(target);
                if (tool == null) {
                    log.add(line(i, step, "BİLİNMEYEN ADIM: " + (target == null
                            ? "'" + step.action() + "' diye bir adım yok; " + String.join(", ",
                                    TOOL_OF.keySet().stream().sorted().toList())
                            : target + " bu koşuda yok")));
                    halted = "adım tanınmadı";
                    break;
                }

                String answer;
                try {
                    answer = tool.call(argumentsFor(action, step));
                } catch (BudgetedTool.BrowserClosed closed) {
                    // The operator shut the window: that leaves the model loop
                    // altogether and is not ours to swallow.
                    throw closed;
                } catch (RuntimeException failed) {
                    log.add(line(i, step, "HATA: " + firstLine(String.valueOf(failed.getMessage()))));
                    halted = "adım hata verdi";
                    break;
                }
                if (tool instanceof BudgetedTool guarded && guarded.refusedLastCall()) {
                    log.add(line(i, step, "REDDEDİLDİ: " + firstLine(answer)));
                    halted = "adım reddedildi";
                    break;
                }

                log.add(line(i, step, "tamam"));
                page = policy.pageNow(tape);
                String now = LeanPage.complaint(page);
                if (now != null && !now.equals(before)) {
                    log.add("   ekran bir şey söylüyor: " + now);
                    halted = "ekran değişti";
                    break;
                }
            }
        } finally {
            policy.quiet(false);
        }

        if (page == null) {
            page = policy.pageNow(tape);
        }
        StringBuilder out = new StringBuilder(String.join("\n", log));
        if (halted != null) {
            out.append("\n\nBatch durdu (").append(halted)
                    .append("). Kalan adımlar YAPILMADI — aşağıdaki sayfaya bakıp devam et.");
        }
        if (page != null) {
            out.append("\n\n").append(page);
        }
        return out.toString();
    }

    /** The arguments the underlying tool expects, built from the step. */
    private static String argumentsFor(String action, Step step) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        String ref = text(step.ref());
        String element = text(step.element());
        String value = text(step.value());
        switch (action) {
            case "press" -> arguments.put("key", value);
            case "date" -> {
                arguments.put("target", ref);
                arguments.put("date", value);
            }
            case "select" -> {
                arguments.put("element", element.isEmpty() ? ref : element);
                arguments.put("ref", ref);
                arguments.put("values", List.of(value));
            }
            case "type" -> {
                arguments.put("element", element.isEmpty() ? ref : element);
                arguments.put("ref", ref);
                arguments.put("text", value);
                // The masked amount and date boxes rebuild their value from
                // key events, so a value written in one go is painted back
                // over. CommonActions learned this the same way.
                arguments.put("slowly", true);
            }
            default -> {
                arguments.put("element", element.isEmpty() ? ref : element);
                arguments.put("ref", ref);
            }
        }
        try {
            return JSON.writeValueAsString(arguments);
        } catch (Exception impossible) {
            return "{}";
        }
    }

    private static String line(int index, Step step, String outcome) {
        StringBuilder line = new StringBuilder().append(index + 1).append(". ").append(action(step));
        if (!text(step.ref()).isEmpty()) {
            line.append(' ').append(text(step.ref()));
        }
        if (!text(step.element()).isEmpty()) {
            line.append(" \"").append(text(step.element())).append('"');
        }
        if (!text(step.value()).isEmpty()) {
            line.append(" = \"").append(text(step.value())).append('"');
        }
        return line.append(" → ").append(outcome).toString();
    }

    private static String action(Step step) {
        return text(step.action()).toLowerCase(Locale.ROOT);
    }

    private static String firstLine(String text) {
        String first = text == null ? "" : text.strip().lines().findFirst().orElse("");
        return first.length() > 160 ? first.substring(0, 160) + "…" : first;
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }

    /* ------------------------------------------------------------------ */

    /** Keys a model might reasonably use for the list, in the order tried. */
    private static final List<String> LIST_KEYS = List.of("steps", "actions", "items", "step");

    private static final List<String> REF_KEYS = List.of("ref", "target", "reference", "selector");
    private static final List<String> ELEMENT_KEYS = List.of("element", "description", "name", "label");
    private static final List<String> VALUE_KEYS = List.of("value", "text", "date", "option", "key");

    private List<Step> parse(String arguments) {
        JsonNode root = read(arguments);
        if (root == null) {
            return List.of();
        }
        JsonNode list = root;
        if (root.isObject()) {
            list = null;
            for (String key : LIST_KEYS) {
                JsonNode found = root.get(key);
                if (found != null && !found.isNull()) {
                    list = found.isTextual() ? read(found.asText()) : found;
                    break;
                }
            }
            if (list == null) {
                // One object with an action in it IS a batch of one: a model
                // that sends {"action":"click","ref":"e8"} meant a step.
                return root.has("action") ? List.of(step(root)) : List.of();
            }
        }
        if (list != null && list.isTextual()) {
            list = read(list.asText());
        }
        if (list == null) {
            return List.of();
        }
        if (list.isObject()) {
            return List.of(step(list));
        }
        List<Step> steps = new ArrayList<>();
        for (JsonNode element : list) {
            Step step = element.isTextual() ? sentence(element.asText()) : step(element);
            if (step != null && !text(step.action()).isEmpty()) {
                steps.add(step);
            }
        }
        return steps;
    }

    private static JsonNode read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(json);
        } catch (Exception notJson) {
            return null;
        }
    }

    private static Step step(JsonNode node) {
        return new Step(first(node, List.of("action", "type", "do")), first(node, REF_KEYS),
                first(node, ELEMENT_KEYS), first(node, VALUE_KEYS));
    }

    /** {@code "type e15 955231"} — the shape a model falls back to under pressure. */
    private static Step sentence(String text) {
        String[] parts = text.strip().split("\\s+", 3);
        if (parts.length == 0 || parts[0].isBlank()) {
            return null;
        }
        return new Step(parts[0], parts.length > 1 ? parts[1] : "", "",
                parts.length > 2 ? parts[2] : "");
    }

    private static String first(JsonNode node, List<String> keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return "";
    }
}
