package orchestrator.chat;

import orchestrator.validation.ValidationError;
import orchestrator.validation.ValidationResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a validation finding into a QUESTION for the analyst.
 *
 * The chat is the repair loop with a human in it. Today a finding goes back to
 * the MODEL, which guesses; this session watched it guess twice and get it
 * wrong both times. Asked to verify a read-only field it first invented a
 * value ({@code has amount "*"}), and then, shown the complaint, deleted the
 * assertion and returned a scenario that checks nothing. Neither is a choice
 * an analyst would have made, and neither had to be made by a model: the
 * analyst is sitting right there and knows the answer.
 *
 * So the finding becomes a question with the legal answers as buttons. The
 * buttons are not invented here — they ARE the routes the validator's own
 * message already names. For an unearned assertion those are "give me the
 * value" and "drop the check", which is exactly what the message says.
 *
 * DELIBERATELY NARROW. Three kinds get a tailored question because their legal
 * answers are known and finite. Everything else shows the validator's own
 * message and asks for free text, which is honest: a question with invented
 * options would be this class guessing, and guessing is the thing it exists to
 * stop. Adding a kind here is cheap; pretending to know one is not.
 */
public final class ChatQuestions {

    /**
     * One answer the analyst can click.
     *
     * {@code needsValue} opens an input instead of resolving immediately —
     * "Beklenen değeri ben gireyim" cannot be answered by a click alone.
     */
    public record ChatOption(String id, String label, boolean needsValue, String valuePrompt) {
        public static ChatOption plain(String id, String label) {
            return new ChatOption(id, label, false, null);
        }

        public static ChatOption asking(String id, String label, String valuePrompt) {
            return new ChatOption(id, label, true, valuePrompt);
        }
    }

    /** What the analyst is shown: one question, its buttons, and whether typing is allowed. */
    public record ChatQuestion(String text, List<ChatOption> options, boolean allowsFreeText) {
    }

    /**
     * The question plus what each answer MEANS, kept apart on purpose.
     *
     * The templates never leave the server. The browser gets option ids and
     * labels; what an id does to the request is decided here, from the
     * validation the server itself produced. A client that sent back an
     * unknown id simply resolves to nothing.
     */
    public record Resolution(ChatQuestion question, Map<String, String> templates) {
    }

    private ChatQuestions() {
    }

    /**
     * The first finding worth asking about, or empty when there is nothing to
     * ask — a valid scenario, or a verdict with no errors in it.
     *
     * One question at a time. A chat that dumped four at once would be an
     * error list with a different font, and the answer to the first one
     * usually changes the rest: a corrected label often takes the unknown
     * option and the unearned assertion with it.
     */
    public static Optional<Resolution> resolve(ValidationResult result) {
        if (result == null || result.valid() || result.errors().isEmpty()) {
            return Optional.empty();
        }
        ValidationError error = result.errors().get(0);
        String subject = error.subject();

        return Optional.of(switch (error.kind()) {
            case UNEARNED_ASSERTION -> unearnedAssertion(subject);
            case UNKNOWN_LABEL -> unknownLabel(subject, error.suggestions());
            case UNKNOWN_OPTION -> unknownOption(subject, error.suggestions());
            default -> freeText(error);
        });
    }

    /**
     * The one the mockup drew, and the one this whole session was about.
     *
     * A readonly field holds whatever the application derived. The scenario
     * never typed it, so it cannot be asserted unless the analyst says what
     * to expect — and the analyst is the only one who can.
     */
    private static Resolution unearnedAssertion(String label) {
        Map<String, String> templates = new LinkedHashMap<>();
        templates.put("value", "\"" + label + "\" alanı \"%s\" yazmalı.");
        templates.put("skip", "\"" + label + "\" alanını doğrulama.");

        return new Resolution(
                new ChatQuestion(
                        "Katalogda \"" + label + "\" salt okunur bir alan — uygulamanın kendi "
                                + "doldurduğu bir değer. Beklenen değeri siz vermediğiniz sürece "
                                + "doğrulayamam, çünkü tahmin etmek yanlış bir testi yeşil "
                                + "gösterir. İki yol var:",
                        List.of(
                                ChatOption.asking("value", "Beklenen değeri ben gireyim",
                                        "\"" + label + "\" ne yazmalı?"),
                                ChatOption.plain("skip", "Doğrulamayı atla")),
                        false),
                templates);
    }

    /**
     * A label the catalog does not have. The near misses come from the
     * validator's own Levenshtein ranking, so the buttons are the screen's
     * real labels rather than anything made up here.
     */
    private static Resolution unknownLabel(String label, List<String> nearest) {
        Map<String, String> templates = new LinkedHashMap<>();
        List<ChatOption> options = new ArrayList<>();

        for (int i = 0; i < nearest.size(); i++) {
            String id = "near" + i;
            options.add(ChatOption.plain(id, nearest.get(i)));
            templates.put(id, "\"" + label + "\" yerine \"" + nearest.get(i) + "\" alanını kullan.");
        }

        return new Resolution(
                new ChatQuestion(
                        "Katalogda \"" + label + "\" diye bir alan yok. Bunlardan birini mi "
                                + "kastettiniz?",
                        options,
                        true),
                templates);
    }

    /** A value the field does not offer, answered with the ones it does. */
    private static Resolution unknownOption(String label, List<String> allowed) {
        Map<String, String> templates = new LinkedHashMap<>();
        List<ChatOption> options = new ArrayList<>();

        for (int i = 0; i < allowed.size(); i++) {
            String id = "opt" + i;
            options.add(ChatOption.plain(id, allowed.get(i)));
            templates.put(id, "\"" + label + "\" için \"" + allowed.get(i) + "\" seç.");
        }

        return new Resolution(
                new ChatQuestion(
                        "\"" + label + "\" alanı o değeri sunmuyor. Katalogda şunlar var:",
                        options,
                        true),
                templates);
    }

    /**
     * Everything else: the validator's own words, and a box to answer in.
     *
     * No invented buttons. The validator's messages are written for an analyst
     * already — they name the line, what was written and what is wrong with it
     * — so showing one and asking "how should we fix it?" loses nothing. The
     * alternative would be this class deciding what the legal answers are for
     * a finding it does not understand.
     */
    private static Resolution freeText(ValidationError error) {
        String where = error.step() == null || error.step().isBlank()
                ? ""
                : "\n\nSatır " + error.line() + ": " + error.step();

        return new Resolution(
                new ChatQuestion(error.message() + where + "\n\nNasıl ilerleyelim?",
                        List.of(), true),
                Map.of());
    }

    /**
     * What the chosen answer adds to the request.
     *
     * Returns null when the id is unknown, which the caller treats as "nothing
     * was answered" rather than as an error: a stale button from a question
     * that has since been replaced must not corrupt the request.
     */
    public static String applied(Resolution pending, String optionId, String value) {
        if (pending == null || optionId == null) {
            return null;
        }
        String template = pending.templates().get(optionId);
        if (template == null) {
            return null;
        }
        if (!template.contains("%s")) {
            return template;
        }
        return value == null || value.isBlank() ? null : template.formatted(value.trim());
    }
}
