package orchestrator.explore;

import orchestrator.validation.ValidationError;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every quoted value in a generated scenario has to have come from somewhere.
 *
 * <p>This is what makes it safe to let a model write the Gherkin of a walk.
 * The model is good at the part code kept getting wrong — telling the real
 * path from the agent's detours — and bad at the part code is good at:
 * leaving words alone. Given a walk that never selected "Normal", it may still
 * write {@code select "Normal" from "Fatura Tipi"} because the catalog makes it
 * plausible. So the check is mechanical: a label, an option, a typed value or
 * an expected value must appear in the walk's own tape, in the catalog, or in
 * what the analyst wrote. Anything else was invented, and becomes a validator
 * finding the repair turn sees.
 *
 * <p>Deliberately a SUBSTRING check over folded text. It cannot tell which
 * screen a word was on, and it does not try: its job is to catch words that
 * exist nowhere, which is the failure that turns into a 30-second timeout
 * against a label the page never had.
 */
public final class Provenance {

    private static final Pattern STEP = Pattern.compile("^\\s*(Given|When|Then|And|But)\\s+(.*)$");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");

    private Provenance() {
    }

    /**
     * @param feature the generated .feature
     * @param sources everything a value may legitimately come from — the raw
     *                tape, the catalog YAML, the analyst's scenario text
     */
    public static List<ValidationError> check(String feature, String... sources) {
        StringBuilder corpus = new StringBuilder();
        for (String source : sources) {
            if (source != null) {
                // The tape stores results as JSON inside JSON, so quotes and
                // line breaks arrive escaped; neither matters to a word search.
                corpus.append(TapeRenderer.fold(source.replace("\\\"", "\"").replace("\\n", "\n")))
                        .append('\n');
            }
        }
        String known = corpus.toString();

        List<ValidationError> errors = new ArrayList<>();
        String[] lines = feature == null ? new String[0] : feature.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            Matcher step = STEP.matcher(lines[i]);
            if (!step.matches()) {
                continue;
            }
            String body = step.group(2).trim();
            // The login is grafted by the run from the chosen test user; it is
            // not part of the walk and its values are not in the tape.
            if (body.startsWith("login")) {
                continue;
            }
            Matcher quoted = QUOTED.matcher(body);
            while (quoted.find()) {
                String value = quoted.group(1);
                if (!seen(value, body, known)) {
                    errors.add(new ValidationError(i + 1, body,
                            "\"" + value + "\" ne ajanın yürüyüşünde, ne katalogda, ne de senaryo"
                                    + " metninde geçiyor — uydurulmuş görünüyor. Bu satırı yürüyüşte"
                                    + " gerçekten görülen etiket ve değerlerle yazın, ya da yürüyüş"
                                    + " bunu hiç yapmadıysa satırı çıkarın.",
                            List.of(), ValidationError.Kind.UNKNOWN_LABEL, value));
                }
            }
        }
        return errors;
    }

    private static boolean seen(String value, String step, String known) {
        String folded = TapeRenderer.fold(value);
        if (folded.isBlank() || known.contains(folded)) {
            return true;
        }
        // An amount is typed as two boxes, lira and kuruş, and written as one
        // "1000,50": each half was typed, the joined form never was.
        if (step.startsWith("enter amount") && value.contains(",")) {
            for (String part : value.split(",")) {
                if (!part.isBlank() && !known.contains(TapeRenderer.fold(part))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }
}
