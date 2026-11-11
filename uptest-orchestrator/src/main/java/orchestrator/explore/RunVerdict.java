package orchestrator.explore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * How an "AI ile çalıştır" walk ends: a structured result, not a paragraph.
 *
 * <p>The analyst wanted four things from a run of a saved scenario — the steps
 * that went right, the steps that did not, the warnings, and ONE verdict —
 * and the prompt used to ask for a numbered list ending in "Sonuç: BEKLENDİĞİ
 * GİBİ". That is a shape requested in prose, and this project has measured
 * what that buys: sometimes followed. The catalog endpoint stopped asking and
 * made its answer a tool call ({@code submit_catalog}); this is the same move.
 * Every argument is REQUIRED, because a model fills what a schema demands and
 * skips what it merely permits — "" or an empty list is how it says "none".
 *
 * <p><b>The verdict is settled here, not taken on trust.</b> A run that lists a
 * failed step did not pass, whatever {@code passed} says: the agent writing
 * "geçti" under a step it just described as failing would otherwise be shown
 * to the analyst as a green result.
 *
 * <p>{@code returnDirect} ends the tool loop on the call, as it does for the
 * catalog — the answer goes to the caller instead of back to the model, so
 * there is no next turn to wander off in. BudgetedTool refuses anything the
 * model still tries afterwards in the same turn.
 */
public final class RunVerdict {

    /** The tool's name, which BudgetedTool and the tape renderer key on. */
    public static final String TOOL = "report_result";

    private volatile Result result;

    /**
     * Warnings the CODE adds — the budget was exceeded, the login fell back to
     * the agent. Kept apart from the agent's own so that a model which never
     * mentions them cannot leave them out.
     */
    private final List<String> added = new CopyOnWriteArrayList<>();

    /**
     * @param succeeded     steps carried out that behaved as they should
     * @param failed        steps not carried out, or that did not show what was expected
     * @param warnings      what the analyst should know that did not decide the result
     * @param claimedPassed what the agent said
     */
    public record Result(List<String> succeeded, List<String> failed, List<String> warnings,
                         boolean claimedPassed) {

        /** Passed only when the agent says so AND no step failed. */
        public boolean passed() {
            return claimedPassed && failed.isEmpty();
        }
    }

    @Tool(name = TOOL, returnDirect = true, description = """
            Call this ONCE when you are done with the scenario, or when you cannot go on.
            It ends the task: do not call any other tool after it.
            Every sentence you pass is in TURKISH, one short full sentence per item.""")
    public String reportResult(
            @ToolParam(description = "Every step of the instruction you carried out and that behaved as it "
                    + "should, in order, one Turkish sentence each: what you did and what the screen "
                    + "showed. Pass an empty list if there were none.")
            List<String> succeeded,
            @ToolParam(description = "Every step you could not carry out, skipped, or where the screen did "
                    + "NOT show what the instruction expected, one Turkish sentence each saying which "
                    + "step and why. Pass an empty list if there were none.")
            List<String> failed,
            @ToolParam(description = "Things the analyst should know that did not decide the result: an "
                    + "unexpected dialog, a very slow screen, an ambiguous instruction step, a value that "
                    + "looked slightly different. One Turkish sentence each. Pass an empty list if none.")
            List<String> warnings,
            @ToolParam(description = "true ONLY if every step was carried out AND every expected result "
                    + "(BEKLENEN) was seen on the screen. false otherwise.")
            boolean passed) {
        result = new Result(clean(succeeded), clean(failed), clean(warnings), passed);
        System.out.println("[uptest] explore: sonuc bildirildi — " + (result.passed() ? "GECTI" : "GECMEDI")
                + " (" + result.succeeded().size() + " dogru, " + result.failed().size() + " gecemedi, "
                + result.warnings().size() + " uyari)");
        return "Sonuç kaydedildi. Görev bitti.";
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The model's raw arguments, read leniently — BudgetedTool calls this
     * instead of letting Spring bind them.
     *
     * <p>Spring AI turns the argument JSON into the method's parameter types by
     * reflection, and a model that sends the right CONTENT in the wrong SHAPE
     * kills the call before any of our code runs. Measured, 11.11.2026: the
     * agent finished a walk and answered
     * {@code {"succeeded": "[\"Firma Fatura İşlemleri ekranına gidildi.\", …]"}}
     * — the array written out as a STRING. Reflection got a String for a
     * {@code List} parameter and threw a bare IllegalArgumentException, so a
     * walk that had done its work reported nothing at all.
     *
     * <p>No prompt rule fixes this: the schema already said array, and the
     * model double-encoded it anyway. So the shapes are accepted instead —
     * an array, an array written as a string, or a single sentence.
     *
     * @param arguments the tool call's raw JSON
     */
    public String acceptJson(String arguments) {
        JsonNode root;
        try {
            root = JSON.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
        } catch (Exception notJson) {
            root = JSON.createObjectNode();
        }
        return reportResult(items(root.get("succeeded")), items(root.get("failed")),
                items(root.get("warnings")), truth(root.get("passed")));
    }

    /** An array, an array written as a string, or one sentence — all read as a list. */
    static List<String> items(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (node.isArray()) {
            node.forEach(item -> out.add(item.isTextual() ? item.asText() : item.toString()));
            return out;
        }
        String text = node.isTextual() ? node.asText() : node.toString();
        String trimmed = text.strip();
        if (trimmed.startsWith("[")) {
            try {
                return items(JSON.readTree(trimmed));
            } catch (Exception notAnArray) {
                // Falls through: it only looked like one.
            }
        }
        out.add(text);
        return out;
    }

    /** true / "true" / "evet"; anything else is false, because a claim has to be made. */
    static boolean truth(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        String text = node.asText("").strip().toLowerCase(java.util.Locale.ROOT);
        return "true".equals(text) || "evet".equals(text);
    }

    /** True once the result is in; BudgetedTool refuses every call after it. */
    public boolean isClosed() {
        return result != null;
    }

    /** The result with the code's warnings after the agent's, or null when the agent never reported one. */
    public Result result() {
        Result r = result;
        if (r == null || added.isEmpty()) {
            return r;
        }
        List<String> warnings = new ArrayList<>(r.warnings());
        warnings.addAll(added);
        return new Result(r.succeeded(), r.failed(), warnings, r.claimedPassed());
    }

    /** A warning from the code, shown after the agent's own. */
    public void warn(String warning) {
        if (warning != null && !warning.isBlank()) {
            added.add(warning.strip());
        }
    }

    /** The answer's {@code summary} object, or null when there is no result. */
    public Map<String, Object> toJson() {
        Result r = result();
        if (r == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("succeeded", r.succeeded());
        out.put("failed", r.failed());
        out.put("warnings", r.warnings());
        out.put("verdict", r.passed() ? "PASSED" : "FAILED");
        return out;
    }

    /**
     * The same result as plain text, for the {@code answer} field every caller
     * already reads — so nothing that shows the prose answer goes blank.
     */
    public String asText() {
        Result r = result();
        if (r == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        section(out, "Doğru ilerleyen adımlar", r.succeeded());
        section(out, "Geçemeyen adımlar", r.failed());
        section(out, "Uyarılar", r.warnings());
        out.append("Sonuç: ").append(r.passed() ? "Geçti ✓" : "Geçmedi ✗");
        return out.toString();
    }

    private static void section(StringBuilder out, String title, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        out.append(title).append(":\n");
        for (String item : items) {
            out.append("- ").append(item).append('\n');
        }
        out.append('\n');
    }

    /**
     * Trimmed, blanks dropped, null as empty — "" is how a model says "none".
     *
     * Takes {@code List<?>} and not {@code List<String>} on purpose. The
     * arguments are deserialised into the declared parameter type by erasure,
     * so a model that answers with objects or numbers instead of sentences
     * ({@code [{"adim": "..."}]}, {@code [1, 2]}) hands this method a list of
     * maps that a {@code for (String ...)} loop would throw a
     * ClassCastException on — losing a finished walk over the shape of its
     * last answer. Whatever arrives is read as text instead.
     */
    static List<String> clean(List<?> items) {
        List<String> out = new ArrayList<>();
        if (items == null) {
            return out;
        }
        for (Object item : items) {
            String text = item == null ? "" : String.valueOf(item).strip();
            if (!text.isBlank()) {
                out.add(text);
            }
        }
        return out;
    }
}
