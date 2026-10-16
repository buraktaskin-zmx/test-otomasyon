package orchestrator.validation;

import java.util.List;

/**
 * One reason a scenario would not run, addressed to an analyst rather than a
 * developer: which line, what was written, and the closest steps that do exist.
 */
public record ValidationError(
        long line,
        String step,
        String message,
        List<String> suggestions
) {
    public static ValidationError syntax(long line, String message) {
        return new ValidationError(line, null, message, List.of());
    }

    // Analyst-facing text is Turkish, like the rest of the UI. Gherkin's own
    // syntax errors stay in the parser's English — they come from the library.
    public static ValidationError undefinedStep(long line, String step, List<String> suggestions) {
        return new ValidationError(line, step, "Tanımsız adım", suggestions);
    }

    /**
     * A label the catalog does not list. The step pattern itself is fine, so
     * the message has to say where the real mistake is — inside the quotes.
     */
    public static ValidationError unknownLabel(long line, String step, String label, List<String> nearest) {
        return new ValidationError(line, step,
                "Katalogda \"" + label + "\" diye bir alan yok", nearest);
    }

    /** The right pattern for what this field actually is. */
    public static ValidationError wrongVerb(long line, String step, String label, String type, String expected) {
        return new ValidationError(line, step,
                "\"" + label + "\" alanının tipi " + type + "; bu adım onu süremez",
                List.of(expected));
    }

    /**
     * An assertion on a value the scenario never typed and the analyst never
     * supplied — so the model made it up.
     *
     * This message used to offer three ways out, and the middle one was
     * "delete the verification". The model read it and did exactly that: on a
     * read-only screen, where nothing is ever filled in and so NO value-bearing
     * assertion can be earned, deleting was the only route it was shown to a
     * valid scenario. What came back checked nothing at all.
     *
     * That route is now itself a validation error, so advertising it was
     * telling the model to walk into the other rule. It is gone, and the third
     * way out — an assertion that needs no value — takes its place, with the
     * patterns attached as suggestions so the repair prompt actually prints
     * them. {@code llm.max-repairs} is 1, which means the errors raised on the
     * SECOND attempt never reach the model: the way out has to be offered here,
     * on the first, or it is never offered at all.
     */
    public static ValidationError unearnedAssertion(long line, String step, String label, String value,
                                                    List<String> valueFreeChecks) {
        return new ValidationError(line, step,
                "\"" + label + "\" bu senaryoda doldurulmadi ve beklenen deger \"" + value
                        + "\" istekte de yok — uygulamanin turettigi bir degeri tahmin etmeyin. "
                        + "Ya alani doldurun, ya beklenen degeri istege yazin, ya da deger "
                        + "gerektirmeyen bir kontrol kullanin. Bu dogrulamayi SILMEYIN: hicbir "
                        + "sey dogrulamayan bir senaryo da gecersizdir.",
                valueFreeChecks);
    }

    /** A confirmation message rewritten instead of copied. */
    public static ValidationError paraphrasedMessage(long line, String step, String expected) {
        return new ValidationError(line, step,
                "Onay mesaji test verisi degildir, katalogtan birebir kopyalanir",
                List.of("verify \"" + expected + "\" text is displayed"));
    }

    /** A message asserted on a screen whose text nobody recorded. */
    public static ValidationError uncataloguedMessage(long line, String step) {
        return new ValidationError(line, step,
                "Katalogda success_message yok, istekte de bu metin gecmiyor — ekranin yazdigi "
                        + "metni tahmin etmeyin. Ya kataloga gercek metni ekleyin, ya bu satiri silin.",
                List.of());
    }

    /** A Scenario Outline whose Examples table nothing reads. */

    /**
     * A `click` aimed at the result-grid row the flow enters through.
     *
     * Both steps are valid patterns and the value is a real catalogued one, so
     * nothing else here objects — the model emitted `click "FORD DBS
     * ODEMELERI"` on the line ABOVE its own `select row "FORD DBS ODEMELERI"`.
     * `click` resolves getByRole(BUTTON|LINK) and a grid cell is neither, so the
     * step spends its whole timeout and the run dies on a line that only
     * repeated the next one.
     */
    public static ValidationError rowNotClickable(long line, String step, String entryRow) {
        return new ValidationError(
                line, step,
                "\"" + entryRow + "\" bir IZGARA SATIRI, buton ya da link degil; `click` onu "
                        + "bulamaz ve 30 saniye bekleyip duser. Satir zaten `select row` ile "
                        + "seciliyor — bu satiri silin.",
                List.of("select row \"" + entryRow + "\""));
    }

    public static ValidationError unusedOutline(long line) {
        return new ValidationError(line, null,
                "Scenario Outline kullanilmis ama hicbir adimda <yer tutucu> yok; "
                        + "Examples tablosu suslemeden ibaret. Duz 'Scenario:' yazin.",
                List.of());
    }

    /**
     * A scenario that checks nothing — the quietest possible pass.
     *
     * Found on a read-only screen, where every assertion is unearnable by
     * construction: the scenario fills nothing in, so {@code
     * checkAssertionsAreEarned} refuses any expected value the analyst did not
     * write down. The model's repair turn took the only route left to "valid"
     * and DELETED the assertion, returning a scenario that logs in, opens the
     * record and stops. It passed validation, it would pass every run, and the
     * analyst saw a green badge.
     *
     * The suggestions name the legal ways out, because an error with no way out
     * is how the previous repair turn ended up here. They are passed in rather
     * than written here so the caller can filter them against what the live
     * executor actually publishes — suggesting a step this deployment does not
     * serve would send the model straight into "Tanımsız adım".
     */
    public static ValidationError nothingAsserted(long line, List<String> suggestions) {
        return new ValidationError(line, null,
                "Bu senaryo hicbir sey dogrulamiyor: tek bir `verify` adimi yok. Boyle bir test "
                        + "her kosuda gecer ve hicbir zaman bir sey yakalamaz. En az bir dogrulama "
                        + "ekleyin — degerini istekte yazdiginiz bir alani dogrulayin (ornegin "
                        + "actiginiz kaydin referans numarasi), ya da deger gerektirmeyen bir "
                        + "kontrol kullanin.",
                suggestions);
    }

    /** A value the field does not offer, answered with the values it does. */
    public static ValidationError unknownOption(long line, String step, String label, String value,
                                                List<String> allowed) {
        return new ValidationError(line, step,
                "\"" + label + "\" alanı \"" + value + "\" değerini sunmuyor", allowed);
    }
}
