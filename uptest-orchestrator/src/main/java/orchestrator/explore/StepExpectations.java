package orchestrator.explore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A scenario's numbered steps, each with the expectation written UNDER it.
 *
 * <p><b>Why.</b> BEKLENEN used to be checked only at the end of the walk, so
 * an intermediate state could not be checked at all: in "İptal ekranından
 * Geri ile listeye dönülür" the cancel window that "Fatura İptal Et" opens is
 * gone by the time "Geri" has been pressed. The analyst now writes the
 * expectation beneath the step that produces it:
 *
 * <pre>
 * 1. "Fatura İptal Et" bağlantısına tıkla
 *    Beklenen (bu adımdan hemen sonra kontrol et): "Fatura İptal" penceresi açılır
 * 2. "Geri" düğmesine tıkla
 * </pre>
 *
 * That is the shape {@code docAsText} writes for a step item that begins
 * with {@code Beklenen:}. An older UI numbered that item like any other step
 * ({@code 2. Beklenen: …}); it is read the same way, so a scenario behaves
 * identically whichever build sent it.
 *
 * <p>No database change: the expectation is a line of the steps field, read
 * by convention. A scenario without such a line parses to steps with no
 * expectation and is treated exactly as before.
 */
final class StepExpectations {

    /** One numbered step and the expectation written under it, or null. */
    record Step(int number, String text, String expected) {

        /** Every name the step quotes, in order. */
        List<String> quoted() {
            return quotedIn(text);
        }
    }

    private static final Pattern NUMBERED = Pattern.compile("^\\s*(\\d+)[.)]\\s+(.*)$");

    /** An indented expectation line: "   Beklenen …: text". */
    private static final Pattern INDENTED =
            Pattern.compile("^\\s+Beklenen[^:]*:\\s*(\\S.*)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** A step item that IS an expectation (an older UI numbered it). */
    private static final Pattern AS_STEP =
            Pattern.compile("^Beklenen[^:]*:\\s*(\\S.*)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"|“([^”]+)”");

    private StepExpectations() {
    }

    static List<Step> parse(String scenario) {
        List<Step> steps = new ArrayList<>();
        if (scenario == null) {
            return steps;
        }
        for (String line : scenario.split("\\R")) {
            Matcher numbered = NUMBERED.matcher(line);
            if (numbered.matches()) {
                String text = numbered.group(2).strip();
                Matcher expectation = AS_STEP.matcher(text);
                if (expectation.matches()) {
                    attach(steps, expectation.group(1));
                } else {
                    steps.add(new Step(steps.size() + 1, text, null));
                }
                continue;
            }
            Matcher indented = INDENTED.matcher(line);
            if (indented.matches()) {
                attach(steps, indented.group(1));
            }
        }
        return steps;
    }

    /** Only the steps that carry an expectation. */
    static List<Step> withExpectation(String scenario) {
        return parse(scenario).stream().filter(step -> step.expected() != null).toList();
    }

    /**
     * Compacted name → the expectation of the step quoting it. What
     * {@link StepRunner} stops on: a batch must not run past a step whose
     * result the scenario wants looked at.
     */
    static Map<String, String> checkpoints(String scenario) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Step step : withExpectation(scenario)) {
            for (String name : step.quoted()) {
                String key = ClaimCheck.compact(name);
                if (!key.isEmpty()) {
                    out.putIfAbsent(key, step.expected());
                }
            }
        }
        return out;
    }

    /**
     * One expectation the run must answer for: {@code number} is its B-number
     * (B1, B2 … in the order the instruction carries them), {@code afterStep}
     * the step it sits under, or 0 for an item of the final Beklenen list,
     * which is judged on the last page.
     */
    record Expectation(int number, int afterStep, String text) {

        /** Where it is judged, as the summary says it. */
        String where() {
            return afterStep > 0 ? afterStep + ". adımdan sonra" : "son ekranda";
        }
    }

    /** The final section's header, as {@code docAsText} writes it: "Beklenen:" on its own line. */
    private static final Pattern FINAL_HEADER =
            Pattern.compile("^\\s*Beklenen(?:ler)?\\s*:\\s*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * Every expectation of the scenario: those under a step first, in step
     * order, then each "- " item of the final Beklenen list.
     *
     * <p><b>Why a list and not the prose.</b> Measured 12.11.2026, tape
     * bd40c3ee: the final Beklenen had two items, the agent's report_result
     * answered neither — five steps in {@code succeeded}, {@code failed: []},
     * {@code passed: true} — and the run was shown green. A rule saying
     * "passed only when every BEKLENEN was seen" was already in the prompt.
     * RunVerdict now holds each of these to an answer.
     */
    static List<Expectation> all(String scenario) {
        List<Expectation> out = new ArrayList<>();
        for (Step step : withExpectation(scenario)) {
            out.add(new Expectation(out.size() + 1, step.number(), step.expected()));
        }
        if (scenario == null) {
            return out;
        }
        boolean inFinal = false;
        for (String line : scenario.split("\\R")) {
            if (FINAL_HEADER.matcher(line).matches()) {
                inFinal = true;
                continue;
            }
            if (!inFinal || line.isBlank()) {
                continue;
            }
            String item = line.strip();
            if (!item.startsWith("-")) {
                inFinal = false;
                continue;
            }
            item = item.substring(1).strip();
            if (!item.isEmpty()) {
                out.add(new Expectation(out.size() + 1, 0, item));
            }
        }
        return out;
    }

    /**
     * The list as the model is given it at the end of its instruction, so the
     * B-numbers it must answer with are not left for it to count.
     */
    static String checklist(List<Expectation> expectations) {
        if (expectations == null || expectations.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("\n\nBEKLENEN LİSTESİ — report_result'ın expectations"
                + " argümanında HER BİRİ için bir madde (B-numarasıyla, GÖRÜLDÜ ya da GÖRÜLMEDİ):\n");
        for (Expectation e : expectations) {
            out.append('B').append(e.number()).append(" (")
                    .append(e.afterStep() > 0 ? e.afterStep() + ". adımdan HEMEN SONRAKİ ekranda" : "SON ekranda")
                    .append("): ").append(e.text()).append('\n');
        }
        return out.toString();
    }

    static List<String> quotedIn(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher quoted = QUOTED.matcher(text);
        while (quoted.find()) {
            String name = quoted.group(1) != null ? quoted.group(1) : quoted.group(2);
            if (!name.isBlank()) {
                out.add(name.strip());
            }
        }
        return out;
    }

    private static void attach(List<Step> steps, String expected) {
        if (steps.isEmpty()) {
            return;
        }
        Step last = steps.get(steps.size() - 1);
        String joined = last.expected() == null ? expected.strip() : last.expected() + " " + expected.strip();
        steps.set(steps.size() - 1, new Step(last.number(), last.text(), joined));
    }
}
