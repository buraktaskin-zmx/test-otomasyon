package orchestrator.explore;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** True once the result is in; BudgetedTool refuses every call after it. */
    public boolean isClosed() {
        return result != null;
    }

    /** The result, or null when the agent never reported one. */
    public Result result() {
        return result;
    }

    /** The answer's {@code summary} object, or null when there is no result. */
    public Map<String, Object> toJson() {
        Result r = result;
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
        Result r = result;
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

    /** Trimmed, blanks dropped, null as empty — "" is how a model says "none". */
    static List<String> clean(List<String> items) {
        List<String> out = new ArrayList<>();
        if (items == null) {
            return out;
        }
        for (String item : items) {
            if (item != null && !item.isBlank()) {
                out.add(item.strip());
            }
        }
        return out;
    }
}
