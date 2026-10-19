package orchestrator.validation;

import io.cucumber.gherkin.GherkinParser;
import io.cucumber.messages.types.Envelope;
import io.cucumber.messages.types.Feature;
import io.cucumber.messages.types.FeatureChild;
import io.cucumber.messages.types.GherkinDocument;
import io.cucumber.messages.types.ParseError;
import io.cucumber.messages.types.Step;
import orchestrator.catalog.CatalogModel;
import orchestrator.product.ProductModel;
import orchestrator.vocabulary.Vocabulary;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Checks a Gherkin document against the executor's step vocabulary — parse
 * errors first, then every step line.
 *
 * Uses the real Gherkin parser and Cucumber's own expression matcher, so the
 * line numbers and the accept/reject decision are the ones Cucumber would
 * produce. Synchronous and browser-free: it should cost milliseconds.
 */
@Service
public class ScenarioValidator {

    private static final int MAX_SUGGESTIONS = 3;

    public ValidationResult validate(String featureText, Vocabulary vocabulary) {
        return validate(featureText, vocabulary, null);
    }

    /**
     * The same checks, plus what the catalog can settle.
     *
     * A step can match its pattern perfectly and still be wrong: the mistake
     * lives inside the quotes. Both generations of the DBS scenario carried
     * exactly one such line — `enter` on a dropdown, then a label with a
     * stray letter — and both cost a browser run to find. The catalog knows
     * the answer to both, in milliseconds.
     *
     * @param catalog may be null; then nothing below the vocabulary check runs
     */
    public ValidationResult validate(String featureText, Vocabulary vocabulary, CatalogModel catalog) {
        return validate(featureText, vocabulary, catalog, null);
    }

    /**
     * @param intent what the analyst asked for, or null. Used for one check
     *               only: a value the scenario asserts without ever typing it
     *               is allowed when the analyst supplied that value, and is an
     *               invention otherwise.
     */
    public ValidationResult validate(String featureText, Vocabulary vocabulary, CatalogModel catalog,
                                     String intent) {
        return validate(featureText, vocabulary, catalog, intent, null);
    }

    /**
     * The same checks again, plus the sentences the PRODUCT says the
     * application prints.
     *
     * This exists for one finding. {@link #checkMessageIsCatalogued} accepted
     * exactly one asserted sentence — the catalog's {@code success_message} —
     * and its own javadoc records the consequence: a negative scenario
     * asserting an error dialog's text was flagged too. That shut the door on
     * most of what is worth testing in a payment product, because almost every
     * business rule there is a refusal ("Geçersiz IBAN numarası girdiniz.")
     * and a screen's catalog has no place to record one. A catalog describes
     * what a screen HAS; only a person can write down what the application
     * must SAY when it says no.
     *
     * <p>Note which half of the product is consulted here: all of its
     * messages, never only the rules the analyst ticked for this generation.
     * The selection shapes the prompt; it must not shape the validator, or a
     * draft accepted at generation would be refused by the identical check at
     * the run gate.
     *
     * @param product may be null, and then this is byte-for-byte the previous
     *                behaviour — the accepted set falls back to the single
     *                catalogued success message
     */
    public ValidationResult validate(String featureText, Vocabulary vocabulary, CatalogModel catalog,
                                     String intent, ProductModel product) {
        List<ValidationError> errors = new ArrayList<>();

        GherkinParser parser = GherkinParser.builder().includePickles(false).build();

        List<Step> steps = new ArrayList<>();
        try (ByteArrayInputStream source = new ByteArrayInputStream(featureText.getBytes(StandardCharsets.UTF_8));
             Stream<Envelope> envelopes = parser.parse("scenario.feature", source)) {

            envelopes.forEach(envelope -> {
                envelope.getParseError().ifPresent(error -> errors.add(toSyntaxError(error)));
                envelope.getGherkinDocument().ifPresent(document -> collectSteps(document, steps));
            });
        } catch (Exception e) {
            errors.add(ValidationError.syntax(0, "Senaryo ayrıştırılamadı: " + e.getMessage()));
            return ValidationResult.failed(0, errors);
        }

        // A syntax error means the AST is incomplete; reporting "undefined step"
        // on top of it would just be noise.
        if (!errors.isEmpty()) {
            return ValidationResult.failed(steps.size(), errors);
        }

        if (steps.isEmpty()) {
            errors.add(ValidationError.syntax(0, "Adım bulunamadı: çalıştırılacak bir senaryo yok"));
            return ValidationResult.failed(0, errors);
        }

        for (Step step : steps) {
            String text = step.getText().trim();
            if (vocabulary.match(text).isEmpty()) {
                errors.add(ValidationError.undefinedStep(
                        step.getLocation().getLine(),
                        text,
                        vocabulary.suggestionsFor(text, MAX_SUGGESTIONS)));
            }
        }

        // Only once every line is a known step. Asking the catalog about a
        // line that binds to nothing would report the same mistake twice.
        // Whether every line bound to a step, captured BEFORE the checks below
        // add anything. The catalog checks need that to be true — asking what
        // a step's arguments mean is pointless when the step is unknown — but
        // they must not be silenced by findings that leave the steps intact.
        boolean everyStepIsKnown = errors.isEmpty();

        checkOutlineIsUsed(featureText, errors);

        if (everyStepIsKnown) {
            checkSomethingIsAsserted(steps, vocabulary, catalog, errors);
        }

        if (everyStepIsKnown && catalog != null) {
            for (Step step : steps) {
                checkAgainstCatalog(step, vocabulary, catalog, errors);
            }
            checkAssertionsAreEarned(steps, vocabulary, catalog, intent, product, errors);
        }

        return errors.isEmpty()
                ? ValidationResult.ok(steps.size())
                : ValidationResult.failed(steps.size(), errors);
    }

    /**
     * A scenario has to check something, or it is not a test.
     *
     * This is the mirror of {@link #checkAssertionsAreEarned} and it exists
     * because that rule, correct as it is, has a degenerate solution. On a
     * read-only screen the scenario fills nothing in, so no assertion can ever
     * be "earned" unless the analyst wrote the expected value down. Shown that
     * error, the repair turn did the one thing that makes it go away: it
     * deleted the assertion. What came back was five steps that log in, open a
     * record and stop — valid, green, and incapable of failing.
     *
     * So the two rules are enforced together. One says an assertion must not be
     * invented; this one says there must BE an assertion. Between them the only
     * way out is the honest one: assert a value the request names, or use a
     * check that needs no value.
     *
     * Deliberately not gated on HAVING a catalog. Whether a scenario tests
     * anything is a fact about the scenario, and a flow with no catalog is no
     * more entitled to assert nothing. The catalog is read for one thing only:
     * to recognise the one screen where this rule can have no legal answer.
     */
    private static void checkSomethingIsAsserted(List<Step> steps, Vocabulary vocabulary,
                                                 CatalogModel catalog, List<ValidationError> errors) {
        // A rule whose only escape is a step this deployment does not serve is
        // not a rule, it is a deadlock. On a read-only screen every
        // value-bearing assertion is already refused by
        // checkAssertionsAreEarned, so if the executor publishes no value-free
        // assertion either, there is NOTHING the model can write — and raising
        // the error turns a reported problem into an unproducible scenario.
        //
        // This is the date-verb lesson applied one level up: a CORRECTION must
        // not outrun the deployment, and neither must a RULE. The orchestrator
        // and the executors restart separately, and this exact gap was walked
        // into once already — the rule shipped before the step it depends on.
        //
        // A scenario that asserts nothing is bad. Being unable to produce one
        // at all is worse, and it is the analyst who pays for it.
        if (readOnlyScreen(catalog) && waysToAssert(vocabulary).isEmpty()) {
            System.out.println("[uptest] validator: salt-okunur ekran, ama bu executor "
                    + "deger gerektirmeyen hicbir dogrulama yayinlamiyor "
                    + "(verify ... field is masked) — 'hic dogrulama yok' kurali atlandi. "
                    + "Executor'i yeniden derleyip baslatin, sonra vocabulary'yi tazeleyin.");
            return;
        }

        long lastLine = 0;
        for (Step step : steps) {
            lastLine = step.getLocation().getLine();
            boolean asserts = vocabulary.matchWithArguments(step.getText().trim())
                    .map(match -> match.step().pattern().startsWith("verify "))
                    .orElse(false);
            if (asserts) {
                return;
            }
        }
        errors.add(ValidationError.nothingAsserted(lastLine, waysToAssert(vocabulary)));
    }

    /**
     * The value-free assertions, filtered to what this executor serves.
     *
     * An unfiltered list would be the date-verb mistake again: a correction
     * that outruns the deployment turns a reported problem into an unrunnable
     * scenario. The orchestrator and the executors restart separately, so a
     * step this orchestrator knows about may not exist on the other side yet.
     */
    private static List<String> waysToAssert(Vocabulary vocabulary) {
        return EARNABLE_WITHOUT_FILLING.stream().filter(vocabulary::has).toList();
    }

    /** Null-safe {@link CatalogModel#readOnlyScreen()}; no catalog means unknown. */
    private static boolean readOnlyScreen(CatalogModel catalog) {
        return catalog != null && catalog.readOnlyScreen();
    }

    /**
     * The assertions a scenario can make without having typed anything first,
     * and therefore the only ones available on a screen that is only read.
     *
     * `is masked` takes no expected value at all. `text is displayed` takes
     * one, but it asserts a MESSAGE rather than a field's value, so it is
     * routed to {@link #checkMessageIsCatalogued} and never has to be earned
     * by a fill.
     */
    private static final List<String> EARNABLE_WITHOUT_FILLING = List.of(
            "verify the {string} field is masked",
            "verify {string} text is displayed");

    /* ========================== catalog checks ========================== */

    /**
     * Every assertion must be earned: either the scenario typed the value, or
     * the analyst supplied it.
     *
     * A field the scenario never filled holds whatever the application
     * derived, and the model has no way to know it — so it invents something
     * plausible. Real case: asked to check "Bayi Ünvanı", which the app looks
     * up from the chosen dealer code, it asserted "Ford Otomobil Ticaret A.Ş."
     * against a screen that says "TEST / TEST UNVANI".
     *
     * The intent is the escape hatch, and the right one: an analyst who knows
     * the expected value writes it down, and then asserting it is knowledge
     * rather than invention.
     */
    private static void checkAssertionsAreEarned(List<Step> steps, Vocabulary vocabulary, CatalogModel catalog,
                                                 String intent, ProductModel product,
                                                 List<ValidationError> errors) {
        Set<String> written = new HashSet<>();
        String said = intent == null ? "" : fold(intent);
        List<String> known = knownMessages(catalog, product);

        for (Step step : steps) {
            String text = step.getText().trim();
            Vocabulary.Match match = vocabulary.matchWithArguments(text).orElse(null);
            if (match == null) {
                continue;
            }
            String pattern = match.step().pattern();

            if (pattern.startsWith("verify ") && pattern.endsWith("text is displayed")
                    && !match.arguments().isEmpty()) {
                checkMessageIsCatalogued(text, match.arguments().get(0),
                        step.getLocation().getLine(), catalog, known, said, errors);
                continue;
            }

            Roles roles = roles(pattern, match.arguments().size());
            if (roles == null) {
                continue;
            }
            String label = match.arguments().get(roles.label());
            String value = match.arguments().get(roles.value());

            if (!pattern.startsWith("verify ")) {
                written.add(label);
                continue;
            }
            if (written.contains(label) || said.contains(fold(value))) {
                continue;
            }
            // Only for fields the catalog knows; an unknown label is already
            // reported by the check above and one complaint is enough.
            if (catalog.field(label).isPresent()) {
                errors.add(ValidationError.unearnedAssertion(
                        step.getLocation().getLine(), text, label, value,
                        waysToAssert(vocabulary)));
            }
        }
    }

    /**
     * The catalog's confirmation text, copied rather than paraphrased.
     *
     * A message is not test data (the prompt says so and the model agreed,
     * then wrote "Fatura başarıyla oluşturuldu" for a screen that says
     * "Fatura Oluşturma işleminiz başarıyla gerçekleşmiştir."). Checked only
     * when the catalog actually gives a message — a flow without one has
     * nothing to compare against.
     *
     * This used to read only {@code catalog.successMessage()}, and carried a
     * known limitation: a negative scenario asserting an error dialog's text
     * was flagged too. That was never arguable for a product whose rules are
     * mostly refusals — it meant those scenarios could not be generated at
     * all, and the only workaround was for the analyst to paste the error text
     * into the intent so the escape hatch below would fire. The fix is not to
     * loosen the check but to widen what counts as catalogued: a screen's
     * catalog records what the screen HAS, and a product's rules record what
     * the application SAYS when it refuses. Both are written down; neither is
     * invented.
     *
     * @param known every sentence this screen and its product are known to
     *              print. Empty when neither says anything, and then nothing
     *              here has a comparison to make
     * @param catalog consulted for one question only — whether a success
     *                message exists — because that is what decides which of
     *                the two findings is the truthful one
     */
    private static void checkMessageIsCatalogued(String text, String asserted, long line,
                                                 CatalogModel catalog, List<String> known,
                                                 String said, List<ValidationError> errors) {
        if (known.stream().anyMatch(message -> fold(message).equals(fold(asserted)))) {
            return;
        }

        // "Paraphrased" is a claim about the SUCCESS message, so it is only
        // sayable when the catalog carries one. Without it there is nothing
        // this screen declares it prints, and the finding is the older,
        // weaker one — along with its intent escape, which must survive:
        // dropping it would turn a scenario that validates today into an
        // error the moment a product is selected, for no new reason.
        String success = catalog == null ? null : catalog.successMessage();
        if (success == null || success.isBlank()) {
            // No message in the catalog, so there is nothing on this screen the
            // scenario is entitled to claim. The prompt already says to end
            // without one; a model that writes a plausible sentence anyway
            // sends a run hunting for text the application never prints.
            // Unless the analyst dictated it — then it is knowledge, not
            // invention, the same exception assertions get.
            if (!said.contains(fold(asserted))) {
                errors.add(ValidationError.uncataloguedMessage(line, text));
            }
            return;
        }
        errors.add(ValidationError.paraphrasedMessage(line, text, nearest(asserted, known)));
    }

    /**
     * Every sentence the scenario is entitled to claim the application prints:
     * the catalog's success message, plus every message the product's rules
     * record.
     *
     * Order matters only for the suggestion, and the success message goes
     * first because a scenario that paraphrases anything usually paraphrased
     * that one.
     */
    private static List<String> knownMessages(CatalogModel catalog, ProductModel product) {
        List<String> known = new ArrayList<>();
        if (catalog != null && catalog.successMessage() != null && !catalog.successMessage().isBlank()) {
            known.add(catalog.successMessage());
        }
        if (product != null) {
            product.messages().stream().filter(m -> !m.isBlank()).forEach(known::add);
        }
        return known;
    }

    /**
     * The known message the draft most likely meant, so the repair prompt and
     * the analyst both get the right sentence rather than "it is not the
     * success message".
     *
     * With one known message this is exactly what the old code reported.
     */
    private static String nearest(String asserted, List<String> known) {
        return known.stream()
                .min(Comparator.comparingInt(candidate -> distance(fold(asserted), fold(candidate))))
                .orElse("");
    }

    /**
     * A Scenario Outline whose Examples nothing reads.
     *
     * Generated as "Scenario Outline" with a one-row table and steps carrying
     * literal values, so the table is decoration: it says the scenario is data
     * driven when it is not, and the next person edits the table expecting the
     * run to change.
     *
     * Checked on the raw text rather than the AST because the question is
     * whether a "<placeholder>" appears anywhere in the steps at all, which is
     * exactly what the source shows.
     */
    private static void checkOutlineIsUsed(String featureText, List<ValidationError> errors) {
        Matcher outline = OUTLINE.matcher(featureText);
        if (outline.find() && !PLACEHOLDER.matcher(featureText).find()) {
            long line = featureText.substring(0, outline.start()).split("\\R", -1).length;
            errors.add(ValidationError.unusedOutline(line));
        }
    }

    private static final Pattern OUTLINE = Pattern.compile("(?m)^\\s*(Scenario Outline|Senaryo Taslağı)\\s*:");
    private static final Pattern PLACEHOLDER = Pattern.compile("<[^<>\\s][^<>]*>");

    /**
     * Which argument is the field label and which is the value.
     *
     * Derived from the pattern text rather than from a hand-kept table of
     * step names: a table goes stale the day someone adds a step, and it goes
     * stale SILENTLY. The convention it reads is the one already written down
     * in VOCABULARY.md and enforced in the prompt — a pattern with "from"
     * names the value first.
     *
     * Anything this cannot place is not a field step, or not one this class
     * understands; either way it is skipped. Skipping is the safe direction:
     * a false rejection would spend a repair turn "fixing" a correct line.
     */
    private record Roles(int label, int value) {
    }

    private static Roles roles(String pattern, int argumentCount) {
        if (argumentCount < 2) {
            return null;
        }
        if (pattern.contains(" from ")) {
            return new Roles(1, 0);
        }
        if (pattern.startsWith("enter ") || pattern.startsWith("verify the ")) {
            return new Roles(0, 1);
        }
        return null;
    }

    private void checkAgainstCatalog(Step step, Vocabulary vocabulary, CatalogModel catalog,
                                     List<ValidationError> errors) {
        String text = step.getText().trim();
        Vocabulary.Match match = vocabulary.matchWithArguments(text).orElse(null);
        if (match == null) {
            return;
        }
        String pattern = match.step().pattern();
        long line = step.getLocation().getLine();

        // A `click` of the entry ROW. Both steps are valid patterns and the
        // value is a real catalogued one, so neither the grammar check nor the
        // label check has anything to say — and the model emitted BOTH
        // `click "FORD DBS ODEMELERI"` and `select row "FORD DBS ODEMELERI"`.
        // `click` resolves getByRole(BUTTON|LINK); a grid cell is neither, so it
        // spends its full timeout and the run dies on a line that merely
        // repeated the line above it.
        if (pattern.equals("click {string}") && match.arguments().size() == 1
                && !catalog.entryRow().isEmpty()
                && fold(catalog.entryRow()).equals(fold(match.arguments().get(0)))) {
            errors.add(ValidationError.rowNotClickable(line, text, catalog.entryRow()));
            return;
        }

        // "check" is the one field step with a single argument, so the
        // label/value pairing below cannot place it. Its argument is a label,
        // and the only field it may name is a checkbox: ticking a text box is
        // a mistake the browser reports minutes later, if at all.
        if (pattern.startsWith("check ") && match.arguments().size() == 1) {
            String box = match.arguments().get(0);
            CatalogModel.CatalogField ticked = catalog.field(box).orElse(null);
            if (ticked == null) {
                errors.add(ValidationError.unknownLabel(line, text, box, nearestLabels(box, catalog)));
            } else if (!"checkbox".equals(ticked.type())) {
                errors.add(ValidationError.wrongVerb(line, text, box, describeType(ticked),
                        expectedPattern(ticked)));
            }
            return;
        }

        Roles roles = roles(pattern, match.arguments().size());
        if (roles == null) {
            return;
        }

        String label = match.arguments().get(roles.label());
        String value = match.arguments().get(roles.value());

        CatalogModel.CatalogField field = catalog.field(label).orElse(null);
        if (field == null) {
            errors.add(ValidationError.unknownLabel(line, text, label, nearestLabels(label, catalog)));
            return;
        }

        if (!pattern.startsWith("verify ")) {
            String expected = expectedPattern(field);
            if (expected != null && !pattern.equals(expected)) {
                errors.add(ValidationError.wrongVerb(line, text, label, describeType(field), expected));
                return;
            }
            checkValue(line, text, field, value, errors);
            return;
        }

        // Reading used to be unchecked — every type can be read, so no verb
        // could be wrong. A date broke that. It is written as an OFFSET
        // ("+3 gün") and only `verify the {} has date {}` resolves one; the
        // plain equals compares text exactly and would look at a screen showing
        // 11/10/2026 for the literal string "+3 gün". A field set perfectly
        // then fails its own assertion, minutes into a browser run, which is
        // precisely the silent-wrong this validator exists to catch early.
        String expectedVerify = expectedVerifyPattern(field);
        if (expectedVerify != null && !pattern.equals(expectedVerify)) {
            errors.add(ValidationError.wrongVerb(line, text, label, describeType(field), expectedVerify));
        }
    }

    /**
     * The one step that reads this field, or null when any of them will do.
     *
     * Null for everything except a date, deliberately. Text, amounts and
     * dropdowns all render as text on a confirm screen and the plain equals
     * reads them all; inventing a rule there would reject correct scenarios for
     * tidiness. A date is the exception because its WRITTEN form is not its
     * DISPLAYED form.
     */
    private static String expectedVerifyPattern(CatalogModel.CatalogField field) {
        return "date".equals(field.type()) ? "verify the {string} has date {string}" : null;
    }

    /**
     * The one step that drives this field, or null when the catalog does not
     * say enough to be sure.
     */
    private static String expectedPattern(CatalogModel.CatalogField field) {
        if (field.readonly()) {
            // Not a typing mistake but the same shape of one: the application
            // owns this value, so no writing step is correct here.
            return "verify the {string} field equals {string}";
        }
        return switch (field.type()) {
            case "text" -> "enter {string} {string}";
            // `enter` would also set a date and the browser would not complain.
            // What it cannot do is keep working: a literal date freezes the day
            // the scenario was generated, and a maturity date behind today is
            // refused by the form. `enter date` resolves "+3 gün" at run time,
            // so the scenario stays runnable for as long as it exists.
            case "date" -> "enter date {string} {string}";
            case "amount" -> "enter amount {string} {string}";
            case "dropdown" -> "select {string} from {string}";
            case "account_picker" -> "select account {string} from {string}";
            case "checkbox" -> "check {string}";
            default -> null;
        };
    }

    private static String describeType(CatalogModel.CatalogField field) {
        return field.readonly() ? "readonly (uygulama dolduruyor)" : field.type();
    }

    /**
     * Whether the field offers this value at all.
     *
     * Silent when the catalog lists nothing: an empty "options" means the
     * catalog did not say, which really happens — discovery cannot read a
     * dependent dropdown's list before its parent is chosen. Treating that as
     * "nothing is allowed" would reject every correct scenario for such a
     * field.
     */
    private static void checkValue(long line, String text, CatalogModel.CatalogField field, String value,
                                   List<ValidationError> errors) {
        List<String> allowed = field.options();
        if (allowed.isEmpty()) {
            return;
        }
        if ("account_picker".equals(field.type())) {
            // Matched the way selectAccount matches: on digits, so the
            // catalog's spacing and the scenario's need not agree.
            String wanted = digits(value);
            boolean known = !wanted.isEmpty()
                    && allowed.stream().anyMatch(account -> digits(account).contains(wanted));
            if (!known) {
                errors.add(ValidationError.unknownOption(line, text, field.label(), value, allowed));
            }
            return;
        }
        if (!allowed.contains(value.trim())) {
            errors.add(ValidationError.unknownOption(line, text, field.label(), value, allowed));
        }
    }

    private static String digits(String text) {
        return text.replaceAll("\\D", "");
    }

    /** Closest catalogued labels, so "Toplam Tutarı" answers with "Toplam Tutar". */
    private static List<String> nearestLabels(String label, CatalogModel catalog) {
        return catalog.labels().stream()
                .sorted(Comparator.comparingInt(candidate -> distance(fold(label), fold(candidate))))
                .limit(MAX_SUGGESTIONS)
                .toList();
    }

    private static String fold(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /** Plain Levenshtein; the label lists here are tens of entries at most. */
    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private static ValidationError toSyntaxError(ParseError error) {
        long line = error.getSource().getLocation().map(l -> l.getLine()).orElse(0L);
        return ValidationError.syntax(line, error.getMessage());
    }

    /** Background and scenario steps alike — both run, so both must be defined. */
    private static void collectSteps(GherkinDocument document, List<Step> steps) {
        document.getFeature().ifPresent(feature -> collectFromFeature(feature, steps));
    }

    private static void collectFromFeature(Feature feature, List<Step> steps) {
        for (FeatureChild child : feature.getChildren()) {
            child.getBackground().ifPresent(background -> steps.addAll(background.getSteps()));
            child.getScenario().ifPresent(scenario -> steps.addAll(scenario.getSteps()));
            child.getRule().ifPresent(rule -> {
                for (var ruleChild : rule.getChildren()) {
                    ruleChild.getBackground().ifPresent(background -> steps.addAll(background.getSteps()));
                    ruleChild.getScenario().ifPresent(scenario -> steps.addAll(scenario.getSteps()));
                }
            });
        }
    }
}
