package orchestrator.flow;

import orchestrator.validation.ValidationError;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every quoted word in a scenario written from a drawing has to have come
 * from somewhere.
 *
 * <p>This is what makes it safe to let a model write Gherkin against a
 * catalog that may not exist yet. The model is good at the part code keeps
 * getting wrong — following a path and choosing what to test — and bad at the
 * part code is good at: leaving words alone. Given a screen whose read never
 * mentioned "Normal", it will still write {@code select "Normal" from "Fatura
 * Tipi"} because it is plausible. So the check is mechanical: a label, an
 * option, a button or a value must appear in what the vision model read off
 * the screens, in the edge labels the analyst drew, in the catalog, in the
 * rules or in the test data. Anything else was invented and becomes a finding
 * the repair turn sees.
 *
 * <p>Deliberately a SUBSTRING check over folded text, exactly as
 * {@code Provenance} is for the walk. It cannot tell which screen a word was
 * on and does not try: its job is to catch words that exist NOWHERE, which is
 * the failure that turns into a thirty-second timeout against a label the
 * page never had.
 */
final class FlowProvenance {

    private static final Pattern STEP = Pattern.compile("^\\s*(Given|When|Then|And|But)\\s+(.*)$");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");

    private FlowProvenance() {
    }

    /**
     * @param sources everything a value may legitimately come from: the screen
     *                reads, the edge labels, the catalog, the rules, the test
     *                data and the planned intent
     */
    static List<ValidationError> check(String feature, String... sources) {
        StringBuilder corpus = new StringBuilder();
        for (String source : sources) {
            if (source != null) {
                corpus.append(fold(source)).append('\n');
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
            // not part of the drawing and its values are nowhere in it.
            if (body.startsWith("login")) {
                continue;
            }
            Matcher quoted = QUOTED.matcher(body);
            while (quoted.find()) {
                String value = quoted.group(1);
                if (!seen(value, body, known)) {
                    errors.add(new ValidationError(i + 1, body,
                            "\"" + value + "\" ne okunan ekranlarda, ne çizilen geçişlerde, ne"
                                    + " katalogda, ne kurallarda, ne de test verisinde geçiyor —"
                                    + " uydurulmuş görünüyor. Bu satırı ekranda gerçekten okunan"
                                    + " etiket ve değerlerle yazın, ya da akış bunu içermiyorsa"
                                    + " satırı çıkarın.",
                            List.of(), ValidationError.Kind.UNKNOWN_LABEL, value));
                }
            }
        }
        return errors;
    }

    private static boolean seen(String value, String step, String known) {
        String folded = fold(value);
        if (folded.isBlank() || known.contains(folded)) {
            return true;
        }
        // Whitespace off both sides. A label the screen wraps over two lines
        // is read with its words run together ("Fatura SorgulamaTarih
        // Kriteri" — measured on this application), while a person, a catalog
        // and therefore the scenario write it with the space. Refusing that
        // would send a correct line to the repair turn to be "fixed" into a
        // wrong one.
        if (known.replace(" ", "").contains(folded.replace(" ", ""))) {
            return true;
        }
        // A relative date is written by the scenario, not read off a screen:
        // "+3 gün" is the correct way to say a date here and appears nowhere.
        if (step.startsWith("enter date") && RELATIVE_DATE.matcher(value.trim()).matches()) {
            return true;
        }
        // An amount is typed as two boxes, lira and kuruş, and written as one
        // "1000,50": each half may be known, the joined form never is.
        if (step.startsWith("enter amount") && value.contains(",")) {
            for (String part : value.split(",")) {
                if (!part.isBlank() && !known.contains(fold(part))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /** "+3 gün", "-1 hafta", "bugün" — what RelativeDate resolves at run time. */
    private static final Pattern RELATIVE_DATE = Pattern.compile(
            "(?i)^([+-]?\\d+\\s*(gün|hafta|ay|yıl|yil)|bugün|bugun|yarın|yarin|dün|dun)$");

    /**
     * Folded for a word search: case and the Turkish I/İ/ı collapsed, accents
     * dropped, whitespace squeezed. The last one matters here — a label the
     * screen wraps over two lines reads with the words run together, and the
     * same label in a catalog has a space.
     */
    static String fold(String text) {
        if (text == null) {
            return "";
        }
        String lower = text.toLowerCase(Locale.forLanguageTag("tr"));
        String plain = Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
                .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c');
        return plain.replaceAll("\\s+", " ").trim();
    }
}
