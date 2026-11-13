package orchestrator.explore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     * Failures the CODE found in the agent's claim (ClaimCheck): a step it
     * reported done that the tape shows it never did. Kept apart for the same
     * reason as {@link #added}, and they decide the verdict like any failure.
     */
    private final List<String> refutedFailures = new CopyOnWriteArrayList<>();

    /** The captions of those steps, folded — the agent's own lines naming them leave "succeeded". */
    private final List<String> refutedCaptions = new CopyOnWriteArrayList<>();

    /**
     * The scenario's expectations (StepExpectations.all), each of which the
     * model must answer for in {@code expectations}. Empty: nothing is held
     * to an answer, exactly as before.
     */
    private volatile List<StepExpectations.Expectation> expected = List.of();

    /** The model's {@code expectations} answers; null when the CODE reported the result. */
    private volatile List<String> answers;

    /**
     * B-number → the code's own finding on that expectation (ExpectationCheck),
     * which decides it whatever the model answered.
     */
    private final Map<Integer, Overruled> overruled = new ConcurrentHashMap<>();

    /** {@code verb}: "kontrol edilemedi" (judged on the wrong page) or "görülmedi" (on no page at all). */
    private record Overruled(String verb, String why) {
    }

    /** A model line that is its own report on a B-numbered expectation; the code reports those. */
    private static final Pattern OWN_B_LINE = Pattern.compile("^\\s*B\\d+\\b");

    /** "B2 GÖRÜLDÜ: …", "2) GÖRÜLMEDİ - …": the B-number an answer names. */
    private static final Pattern ANSWER_NUMBER =
            Pattern.compile("^\\s*B?\\s*(\\d+)\\s*[.):\\-]?\\s*", Pattern.CASE_INSENSITIVE);

    /** The status word an answer starts with, once folded. */
    private static final Pattern ANSWER_STATUS = Pattern.compile("^(gorulmedi|goruldu)\\b\\s*[:\\-–—]?\\s*");

    /**
     * @param succeeded    steps carried out that behaved as they should
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
                    + "showed. Steps only: the Beklenen go in expectations. Pass an empty list if there "
                    + "were none.")
            List<String> succeeded,
            @ToolParam(description = "Every step you could not carry out, skipped, or where the screen did "
                    + "NOT show what the instruction expected, one Turkish sentence each saying which "
                    + "step and why. Pass an empty list if there were none.")
            List<String> failed,
            @ToolParam(description = "Things the analyst should know that did not decide the result: an "
                    + "unexpected dialog, a very slow screen, an ambiguous instruction step, a value that "
                    + "looked slightly different. One Turkish sentence each. Pass an empty list if none.")
            List<String> warnings,
            @ToolParam(description = "One item for EVERY line of the BEKLENEN LİSTESİ at the end of the "
                    + "instruction, with its B-number: \"B1 GÖRÜLDÜ: <what on the page shows it>\" or "
                    + "\"B1 GÖRÜLMEDİ: <what the page shows instead>\". Judge each on the page the list "
                    + "names for it: right after its step, or the LAST page. A page you are no longer "
                    + "looking at does not count, and neither does what you expect a page to show. A "
                    + "Beklenen with no item here counts as NOT seen. Pass an empty list only if the "
                    + "instruction has no BEKLENEN LİSTESİ.")
            List<String> expectations,
            @ToolParam(description = "true ONLY if every step was carried out AND every expected result "
                    + "(BEKLENEN) was seen on the screen. false otherwise.")
            boolean passed) {
        answers = clean(expectations);
        result = new Result(clean(succeeded), clean(failed), clean(warnings), passed);
        System.out.println("[uptest] explore: sonuc bildirildi — ajanin iddiasi "
                + (result.passed() ? "GECTI" : "GECMEDI")
                + " (" + result.succeeded().size() + " dogru, " + result.failed().size() + " gecemedi, "
                + result.warnings().size() + " uyari, " + answers.size() + "/" + expected.size()
                + " beklenen cevaplandi; karar kodla kesinlesir)");
        return "Sonuç kaydedildi. Görev bitti.";
    }

    /**
     * A result the CODE reports — the screen never opened, a cached call
     * failed. It is already a failure; the expectations are not asked about.
     */
    public void reportResult(List<String> succeeded, List<String> failed, List<String> warnings,
                             boolean passed) {
        reportResult(succeeded, failed, warnings, List.of(), passed);
        answers = null;
    }

    /** The expectations every report must answer for; set before the walk. */
    public void expecting(List<StepExpectations.Expectation> expectations) {
        expected = expectations == null ? List.of() : List.copyOf(expectations);
    }

    List<StepExpectations.Expectation> expected() {
        return expected;
    }

    /** False when the CODE reported the result (the walk was stopped): no Beklenen is judged then. */
    boolean answeredByModel() {
        return answers != null;
    }

    /** The code found expectation {@code number} cannot be judged where it is checked. */
    void misplaced(int number, String why) {
        if (why != null && !why.isBlank()) {
            overruled.putIfAbsent(number, new Overruled("kontrol edilemedi", why.strip()));
        }
    }

    /** The code found a name of expectation {@code number} on no page of the walk: it cannot have been seen. */
    void unseen(int number, String why) {
        if (why != null && !why.isBlank()) {
            overruled.put(number, new Overruled("görülmedi", why.strip()));
        }
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
                items(root.get("warnings")), items(root.get("expectations")), truth(root.get("passed")));
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
        List<String> given = answers;
        boolean judged = given != null && !expected.isEmpty();
        if (r == null || (added.isEmpty() && refutedFailures.isEmpty() && !judged)) {
            return r;
        }
        List<String> succeeded = new ArrayList<>();
        for (String line : r.succeeded()) {
            String folded = ClaimCheck.compact(line);
            if (refutedCaptions.stream().noneMatch(folded::contains)
                    && !(judged && OWN_B_LINE.matcher(line).find())) {
                succeeded.add(line);
            }
        }
        // The model's own "B2 …" lines repeat what settleExpectations reports, in code.
        List<String> failed = new ArrayList<>(r.failed().stream()
                .filter(line -> !(judged && OWN_B_LINE.matcher(line).find())).toList());
        failed.addAll(refutedFailures);
        if (judged) {
            settleExpectations(given, succeeded, failed);
        }
        List<String> warnings = new ArrayList<>(r.warnings());
        warnings.addAll(added);
        return new Result(succeeded, failed, warnings, r.claimedPassed());
    }

    /**
     * Every expectation held to its answer, IN CODE: no answer, or anything
     * but GÖRÜLDÜ, is a failure, so a Beklenen the model passes over in
     * silence can no longer leave the run green (tape bd40c3ee). An answer
     * names its B-number; one that does not is taken in order. An expectation
     * the code found misplaced (ExpectationCheck) fails whatever the model
     * said, because the page it is judged on did not show what it names.
     */
    private void settleExpectations(List<String> given, List<String> succeeded, List<String> failed) {
        Map<Integer, String> byNumber = new HashMap<>();
        List<String> unnumbered = new ArrayList<>();
        for (String answer : given) {
            Matcher number = ANSWER_NUMBER.matcher(answer);
            if (number.find() && number.end() > 0) {
                byNumber.putIfAbsent(Integer.parseInt(number.group(1)), answer.substring(number.end()).strip());
            } else {
                unnumbered.add(answer.strip());
            }
        }
        int next = 0;
        for (StepExpectations.Expectation e : expected) {
            String answer = byNumber.get(e.number());
            if (answer == null && next < unnumbered.size()) {
                answer = unnumbered.get(next++);
            }
            String head = "Beklenen (" + e.where() + ") ";
            Overruled wrong = overruled.get(e.number());
            if (wrong != null) {
                failed.add(head + wrong.verb() + ": " + e.text() + " — " + wrong.why());
                continue;
            }
            if (answer == null) {
                failed.add(head + "kontrol edilmedi: " + e.text() + " — Ajan bu Beklenen için sonuç"
                        + " bildirmedi; kod bunu Geçmedi saydı.");
                continue;
            }
            // fold() maps letter for letter, so the status's length is the same in the original.
            Matcher status = ANSWER_STATUS.matcher(TapeRenderer.fold(answer));
            boolean known = status.find();
            boolean seen = known && "goruldu".equals(status.group(1));
            String evidence = known ? answer.substring(Math.min(answer.length(), status.end())).strip() : answer;
            String tail = evidence.isBlank() ? "" : " — " + evidence;
            if (seen) {
                succeeded.add(head + "görüldü: " + e.text() + tail);
            } else {
                failed.add(head + "görülmedi: " + e.text() + tail);
            }
        }
    }

    /**
     * A step the agent reported done and the tape shows it never did. It
     * leaves "succeeded" (every agent line naming the caption does) and is
     * listed as failed, so the run is Geçmedi whatever {@code passed} said.
     */
    public void refute(String caption, String failure) {
        String folded = ClaimCheck.compact(caption == null ? "" : caption);
        if (folded.isEmpty() || failure == null || failure.isBlank()) {
            return;
        }
        refutedCaptions.add(folded);
        refutedFailures.add(failure.strip());
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
