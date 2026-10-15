package com.ykb.uplift.uptest.playwright.discovery;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.ykb.uplift.uptest.playwright.common.CommonActions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Walks a flow screen by screen and builds one catalogs/SCHEMA.md draft out of
 * everything it saw.
 *
 * The division of labour is the point: the ANALYST supplies business values
 * ("Ödeme Tutarı = 1250,55"), and this class works out HOW to type each one.
 * Whether a label needs {@code enter}, {@code enter amount}, {@code select} or
 * {@code select account} is read off the DOM, never asked of the analyst —
 * that mapping is exactly what a catalog exists to record.
 *
 * Alongside the verb it records what each box ACCEPTS — maxlength, a number
 * input's range, whether only digits go in (see {@link Limits}). That is for the
 * generator rather than for the run: producing five scenarios with five
 * different values is only honest if something knows where the boundaries are,
 * and these attributes are the only place on the page that says.
 *
 * v3 vs v4 Select2 render IDENTICALLY on screen (see
 * CommonActions.selectAccount vs components/selectbox/account/AccountSelectBox)
 * so no screenshot can separate them. The DOM can: v4's closed widget carries
 * ".select2-selection", v3's carries ".select2-container" without it. Only v4
 * has a vocabulary step, so a v3 field is flagged UNSUPPORTED_V3 rather than
 * guessed into a type nothing can drive.
 *
 * State accumulates across {@link #scan()} calls for the whole scenario:
 * a label seen again on a later screen is not a second field, it is the same
 * field appearing on a confirm/summary screen — which is precisely what
 * SCHEMA.md's {@code verifiable} flag means. Each field remembers the screen it
 * first appeared on, so the flat field list SCHEMA.md requires can still be
 * grouped by screen with a comment.
 *
 * Label extraction is a first-pass heuristic: unlike findValueElement in
 * CommonActions (label -> fieldcontainer, confirmed on real screens), the
 * fieldcontainer -> label direction has no confirmed pattern yet. Read a draft
 * against a real screen and adjust extractLabel() before trusting it.
 */
public class CatalogDiscovery {

    /**
     * How long a clicked-for screen gets to arrive. Generous because the wait
     * ends the moment the screen changes; only a flow that never advances pays
     * the whole budget.
     */
    private static final double SETTLE_MS = 15000;

    private static final double POLL_MS = 300;

    /**
     * Minimum wait before any scan. The screen-changed check cannot cover the
     * first scan — there is nothing to compare it with — and that scan still
     * follows the entry-button click.
     *
     * Four seconds rather than two because this app keeps loading after the
     * form is on screen: on DBS Fatura Oluştur the "Bayi Kodu" dropdown is not
     * usable the moment the form appears, so a scan that starts too early
     * reads a form that is still assembling itself.
     */
    private static final double MIN_SETTLE_MS = 4000;

    /**
     * How long a Select2 list gets to render before the scan gives up on it.
     * A dependent dropdown never renders one at all, and Playwright's default
     * 30 s was being spent on each of them.
     */
    private static final double OPTION_TIMEOUT_MS = 3000;

    /** A Select2 widget the app has not switched off. */
    private static final String ENABLED_SELECT2 = ".select2-selection:not([aria-disabled='true'])";

    /** Enough diagnostic rows to explain a screen, few enough to stay readable. */
    private static final int DEBUG_LIMIT = 40;
    private static final int DEBUG_TEXT = 70;
    private static final int DEBUG_OPTIONS = 6;

    /**
     * Digits in an option before it counts as carrying an account number. A
     * local account number is nine, an IBAN twenty-four; the longest business
     * option on these screens has none at all, so the gap is wide.
     */
    private static final int ACCOUNT_DIGITS = 9;

    /** Diagnostics are for calibrating the scan, not for the analyst's draft. */
    private static final boolean DEBUG =
            "true".equalsIgnoreCase(System.getenv("UPTEST_DISCOVERY_DEBUG"));

    private final Page page;
    private final CommonActions actions;

    /** Everything seen so far in this scenario, in first-seen order. */
    private final Map<String, Field> catalog = new LinkedHashMap<>();

    /** Just the current screen, so filling never reaches for a field that is no longer shown. */
    private final Map<String, Field> currentScreen = new LinkedHashMap<>();

    /**
     * How many screens each button appeared on. One that shows up on EVERY
     * screen is the site's own chrome (the top menu), not part of this flow —
     * counting is what separates them, so no hard-coded list of menu names has
     * to be kept in step with the app.
     */
    private final Map<String, Integer> buttonScreens = new LinkedHashMap<>();

    /**
     * Diagnostics captured as each screen is scanned.
     *
     * Not re-queried at output time: by then the browser is standing on the
     * LAST screen, so a live query described that one and silently claimed to
     * describe the others.
     */
    private final StringBuilder debugLog = new StringBuilder();

    /** What the previous scan saw, so the next one can wait for it to change. */
    private String lastFingerprint;

    private int screensScanned;

    public CatalogDiscovery(Page page) {
        this.page = page;
        this.actions = new CommonActions(page);
    }

    private enum Type {
        TEXT("text"),
        AMOUNT("amount"),
        DATE("date"),
        DROPDOWN("dropdown"),
        ACCOUNT_PICKER("account_picker"),
        CHECKBOX("checkbox"),
        UNSUPPORTED_V3("UNSUPPORTED_V3"),
        SKIP("");

        final String schemaName;

        Type(String schemaName) {
            this.schemaName = schemaName;
        }
    }

    /**
     * What the box itself says it will accept, read straight off its attributes.
     *
     * This exists for the generator rather than for the run: VariationPlanner
     * has to invent a DIFFERENT value for each of N scenarios, and the only
     * honest way to pick a boundary value is to know where the boundary is. A
     * 16-character invoice number typed into a 3-character box is silently
     * truncated by the browser, so the form then validates a value nobody chose.
     *
     * A null component means the DOM did not say — never "unbounded". The
     * difference is the whole point: the planner falls back to a conservative
     * ladder AND reports that it had to, instead of guessing confidently.
     *
     * Known blind spot: a jQuery-masked field enforces its shape in JavaScript,
     * not in attributes, so a mask that permits only digits is invisible here
     * unless the markup also says so. Read off attributes only, deliberately —
     * guessing "numeric" from a label's wording is how "Fatura No" becomes a
     * number field on a screen that accepts "FTR-2026-0001".
     */
    private record Limits(Integer maxLength, Integer min, Integer max, boolean numeric) {

        static final Limits NONE = new Limits(null, null, null, false);

        boolean known() {
            return maxLength != null || min != null || max != null || numeric;
        }
    }

    private static final class Field {
        final String label;
        final Type type;
        /**
         * Not final: a dependent dropdown is empty when the scan reads it and
         * only fills in once its parent is chosen, so the list is replaced the
         * moment the screen is seen to load one.
         */
        List<String> choices;
        final Limits limits;
        final int firstSeenScreen;
        boolean verifiable;
        /** The application owns this value: a scenario verifies it, never types it. */
        boolean readonly;

        Field(String label, Type type, List<String> choices, Limits limits, int firstSeenScreen) {
            this.label = label;
            this.type = type;
            this.choices = choices;
            this.limits = limits;
            this.firstSeenScreen = firstSeenScreen;
        }
    }

    /**
     * What one field looked like at one moment. Compared before and after a
     * fill to see what that fill set off elsewhere on the screen.
     */
    private record FieldState(boolean enabled, String value, int optionCount) {
    }

    /** What filling one field did to another. */
    private record Effect(String label, String what) {
    }

    /** Effects keyed by the field that caused them, in the order they were filled. */
    private final Map<String, List<Effect>> effects = new LinkedHashMap<>();

    /** Label -> the control that was found but not understood. */
    private final Map<String, String> unrecognised = new LinkedHashMap<>();

    /* ============================== scanning ============================== */

    /** Reads the screen that is showing right now and merges it into the draft. */
    public void scan() {
        actions.assertNoErrorDialog("discover screen catalog");
        settle();
        currentScreen.clear();
        screensScanned++;

        for (Locator container : page.locator("div[class*='fieldcontainer']").all()) {
            if (!container.isVisible()) {
                continue;
            }
            String label = extractLabel(container);
            if (label.isBlank()) {
                continue;
            }
            Probe probe = inspect(container);
            if (probe.type() == Type.SKIP) {
                noteDisplayOnly(label, container);
                continue;
            }

            Field field = catalog.get(label);
            if (field == null) {
                field = new Field(label, probe.type(), probe.choices(), probe.limits(), screensScanned);
                catalog.put(label, field);
            }
            // Only fields with a real editable control land here, so a confirm
            // screen's read-only echo can never be typed into a second time.
            currentScreen.put(label, field);
        }

        clickableNames().forEach(name -> buttonScreens.merge(name, 1, Integer::sum));
        recordDiagnostics();
        lastFingerprint = fingerprint();
    }

    /**
     * Waits for the screen to actually become a different one.
     *
     * The previous version waited for networkidle, which this app never
     * reaches — the log said so on every scan, which made it a blind five
     * second sleep. It showed: a submit slower than that got scanned as the
     * screen before it, and the draft recorded the confirm screen twice.
     *
     * Comparing what the scan is about to read is both the cheapest and the
     * most direct signal, and it returns the instant the screen changes rather
     * than always spending the full budget. The first screen has nothing to
     * differ from, so it is scanned straight away.
     */
    private void settle() {
        // A floor, because the FIRST scan has no earlier screen to differ from
        // and still follows a click: without it the entry-button click was
        // scanned before the form replaced the menu page, and the draft came
        // back with no fields at all.
        page.waitForTimeout(MIN_SETTLE_MS);

        long deadline = System.currentTimeMillis() + (long) SETTLE_MS;
        String previous = null;

        while (System.currentTimeMillis() < deadline) {
            String current = fingerprint();
            // Held still, and — when there is one — different from the screen
            // the last scan read. A screen swap passes through a state where
            // the old fields are gone and the new ones have not arrived, and
            // scanning that moment invented a field ("Oluşturma Tipi" as a
            // confirm-screen value) that exists on neither screen.
            boolean stable = current.equals(previous);
            boolean isNewScreen = lastFingerprint == null || !current.equals(lastFingerprint);
            if (stable && isNewScreen) {
                return;
            }
            previous = current;
            page.waitForTimeout(POLL_MS);
        }
        System.out.println("[uptest] scan: ekran " + (long) SETTLE_MS
                + " ms icinde durulmadi, mevcut DOM taraniyor");
    }

    /**
     * Exactly what the scan reads, so the wait cannot be fooled by a clock or
     * a banner ticking somewhere else on the page.
     */
    private String fingerprint() {
        return String.join("|", page.locator("div[class*='fieldcontainer']").allInnerTexts());
    }

    /**
     * A container with a label but no editable control is a confirm/summary
     * value — which is exactly what SCHEMA.md's {@code verifiable} flag marks.
     * Ignored on the entry screen, where such containers are page furniture
     * rather than results.
     */
    private void noteDisplayOnly(String label, Locator container) {
        Field known = catalog.get(label);
        if (known != null) {
            known.verifiable = true;
            return;
        }

        // Something actionable that classify did not recognise. Reported, not
        // catalogued, and not treated as a confirm-screen value either — it is
        // neither, and calling it either would be a guess.
        String control = unrecognisedControl(container);
        if (control != null) {
            unrecognised.putIfAbsent(label, control);
            return;
        }

        // A container that HAS a control but every one of them is switched off
        // is not page furniture — it is a field the application fills in
        // itself. "Bayi Ünvanı" is exactly this, and dropping it left the
        // draft silent about a field that then had to be added by hand.
        // Catalogued read-only, on the entry screen too.
        boolean hasDisabledControl = container.locator("input, select").count() > 0;
        if (screensScanned == 1 && !hasDisabledControl) {
            return;
        }

        // No limits for a display-only value: nothing will be typed into it, so
        // what it would have accepted is not a question anyone asks.
        Field display = new Field(label, displayType(container), List.of(), Limits.NONE, screensScanned);
        display.verifiable = true;
        display.readonly = hasDisabledControl;
        catalog.put(label, display);
    }

    /**
     * How a display-only value gets verified: money takes
     * {@code verify the {} has amount {}}, everything else
     * {@code verify the {} field equals {}}. CLAUDE.md records that
     * confirm-screen amount values carry a "TL" suffix in the value span, so
     * that suffix is the signal — not the label's wording.
     */
    private Type displayType(Locator container) {
        return safeText(container).contains("TL") ? Type.AMOUNT : Type.TEXT;
    }

    private record Probe(Type type, List<String> choices, Limits limits) {

        Probe(Type type, List<String> choices) {
            this(type, choices, Limits.NONE);
        }
    }

    /**
     * What this container is, and what it offers — decided together, because on
     * this app the two questions cannot be separated.
     *
     * An account picker and an ordinary business dropdown have the SAME markup
     * here: both are a native {@code <select>} wrapped in Select2 v4, so both
     * show {@code .select2-selection}. "Alıcı Hesabı" and "Ödeme Talebi Türü"
     * were indistinguishable by class alone and the latter was being typed as
     * an account picker.
     *
     * What separates them is what the list holds: an account option carries an
     * account number, and {@code selectAccount} matches on exactly those
     * digits, while {@code select} matches an option's text. So the options
     * decide the type — and the same test drops the picker's column-header row
     * ("Güncel Bakiye / Esnek Hesap Limiti / ..."), which carries no digits and
     * is not an account.
     */
    private Probe inspect(Locator container) {
        boolean select2 = container.locator(".select2-selection").count() > 0;
        if (!select2 && container.locator(".select2-container").count() > 0) {
            return new Probe(Type.UNSUPPORTED_V3, List.of());
        }

        if (select2 || container.locator("select").count() > 0) {
            // A widget switched off right now is still a dropdown. "Döviz
            // Cinsi" carries aria-disabled="true" until "Bayi Kodu" is chosen,
            // and treating that as "not a field" dropped it from the catalog
            // altogether — it IS settable, just not yet. What the disabled
            // state does mean is that opening the widget is pointless, so its
            // options are read the cheap way; the fill step reads them again
            // when it gets there, by which time the app has filled them in.
            //
            // Note this is NOT symmetric with disabled inputs. "Bayi Ünvanı"
            // stayed disabled for a full 30 s because the app owns its value,
            // so that one really is display-only. The difference is observed,
            // not assumed.
            boolean canOpen = select2 && container.locator(ENABLED_SELECT2).count() > 0;
            List<String> options = canOpen ? openAndReadOptions(container) : nativeOptions(container);
            List<String> accounts = options.stream()
                    .filter(CatalogDiscovery::carriesAccountNumber)
                    .map(CatalogDiscovery::accountIdentifier)
                    .toList();
            if (!accounts.isEmpty()) {
                return new Probe(Type.ACCOUNT_PICKER, accounts);
            }

            // A dropdown is driven through the native <select> by option TEXT
            // (CommonActions.select -> selectOption), so the catalog has to
            // carry those texts rather than Select2's rendering of them.
            List<String> selectable = selectableOptions(container);
            return new Probe(Type.DROPDOWN, selectable.isEmpty() ? options : selectable);
        }

        // Before the plain-input check below, which would otherwise claim a
        // checkbox as "text" — input:not([type]) does not match it, but a
        // container holding both a box and a text input would resolve the
        // wrong way round.
        if (container.locator(enabled("input[type='checkbox']", "[role='checkbox']")).count() > 0) {
            return new Probe(Type.CHECKBOX, List.of());
        }
        if (container.locator(enabled("input[id$='-integer']", "input[id$='-decimal']")).count() > 0) {
            // The INTEGER box only: an amount is two inputs and the lira part is
            // what bounds the amount. Reading the kuruş box instead would report
            // a two-digit ceiling for a field that accepts millions.
            return new Probe(Type.AMOUNT, List.of(), limitsOf(container, enabled("input[id$='-integer']")));
        }
        if (container.locator(enabled("input.hasDatepicker", "input[type='date']")).count() > 0) {
            // No limits read for a date: the shape is fixed at gg/aa/yyyy and a
            // maxlength of 10 says nothing a planner can use.
            return new Probe(Type.DATE, List.of());
        }
        String plainInput = enabled("input[type='text']", "input:not([type])", "input[type='number']",
                "input[type='tel']");
        if (container.locator(plainInput).count() > 0) {
            return new Probe(Type.TEXT, List.of(), limitsOf(container, plainInput));
        }
        return new Probe(Type.SKIP, List.of());
    }

    /**
     * Attribute names that mean "digits only", with the values that count.
     *
     * {@code inputmode} is the modern spelling and {@code type} the old one; both
     * appear on this application. A {@code pattern} is deliberately NOT read:
     * deciding whether an arbitrary regex permits only digits is a parsing job
     * with a wrong answer in both directions, and a wrong "numeric" sends digits
     * into a box that wanted "FTR-2026-0001".
     */
    private static final List<String> NUMERIC_INPUTMODES = List.of("numeric", "decimal", "tel");

    /**
     * What the first matching input in this container will accept.
     *
     * Everything here is a read of an attribute that is either present or not,
     * which is why it can run on every field of every screen without costing
     * anything. Absent attributes come back null and that is a real answer —
     * see {@link Limits}.
     */
    private static Limits limitsOf(Locator container, String selector) {
        Locator inputs = container.locator(selector);
        if (inputs.count() == 0) {
            return Limits.NONE;
        }
        Locator input = inputs.first();
        try {
            String type = lower(input.getAttribute("type"));
            String inputMode = lower(input.getAttribute("inputmode"));
            return new Limits(
                    positive(input.getAttribute("maxlength")),
                    number(input.getAttribute("min")),
                    number(input.getAttribute("max")),
                    "number".equals(type) || NUMERIC_INPUTMODES.contains(inputMode));
        } catch (RuntimeException gone) {
            // The element can be replaced between the count and the read on a
            // screen that is still assembling itself. Nothing to report is
            // better than failing a whole discovery run over an attribute.
            return Limits.NONE;
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    /** A maxlength of 0 or less is not a limit anyone meant; treat it as absent. */
    private static Integer positive(String value) {
        Integer parsed = number(value);
        return (parsed == null || parsed <= 0) ? null : parsed;
    }

    private static Integer number(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            // Truncating rather than refusing: min="0.01" on a money input is a
            // real thing and its integer part is still a usable floor.
            return (int) Double.parseDouble(value.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * Anything a user could act on that the rules above did not recognise —
     * a radio, a textarea, a custom widget.
     *
     * Such a container used to fall through to noteDisplayOnly and, on the
     * entry screen, vanish without a trace: the draft was quietly incomplete
     * and the gap only surfaced minutes later as a failing run. Saying "I saw
     * something here and do not know what it is" costs one comment line and
     * turns a silent hole into a question someone can answer.
     *
     * Deliberately NOT catalogued as a field: no vocabulary step drives these,
     * so a catalogued one would produce a scenario that cannot run. Same
     * reasoning as UNSUPPORTED_V3.
     */
    private static final String[] UNRECOGNISED = {
            "input[type='radio']", "textarea",
            "[contenteditable='true']", "[role='radio']", "input"
    };

    private static String unrecognisedControl(Locator container) {
        for (String selector : UNRECOGNISED) {
            // Enabled only: a switched-off control is the readonly case below,
            // which the draft already describes properly.
            if (container.locator(enabled(selector)).count() > 0) {
                return selector;
            }
        }
        return null;
    }

    /**
     * The given input selectors, narrowed to the ones a scenario could actually
     * type into.
     *
     * A disabled input is not a field the catalog may offer: "Bayi Ünvanı" is
     * filled in by the app once "Bayi Kodu" is chosen, and cataloguing it as
     * type text sent fill() into a 30 s retry on an element that reports
     * "not enabled" and never becomes enabled. Such a container falls through
     * to noteDisplayOnly, which is the honest description — a value to verify,
     * not one to enter.
     */
    private static String enabled(String... selectors) {
        return String.join(", ", Arrays.stream(selectors)
                .map(selector -> selector + ":not([disabled]):not([readonly])")
                .toList());
    }

    /**
     * An account number is a long run of digits; no business option text
     * ("Vadesinde Peşin Ödensin", "Seçiniz") comes close. Counted across the
     * whole option because the app prints IBANs in spaced groups.
     */
    private static boolean carriesAccountNumber(String option) {
        return option.replaceAll("\\D", "").length() >= ACCOUNT_DIGITS;
    }

    /** A Turkish IBAN: TR and 24 digits, however the app spaces them out. */
    private static final Pattern IBAN = Pattern.compile("TR(?:\\s*\\d){24}");

    /**
     * The account number on its own, not the row it was printed in.
     *
     * A picker row carries the branch and three balances alongside the IBAN,
     * and balances change with every payment — a catalog holding the whole row
     * matches today and silently stops matching once the balance moves.
     * {@code selectAccount} compares digits and an option's digits still
     * contain the IBAN's, so the short form keeps working and stops rotting.
     */
    private static String accountIdentifier(String option) {
        Matcher iban = IBAN.matcher(option);
        return iban.find() ? iban.group().replaceAll("\\s+", " ").trim() : option;
    }

    private List<String> nativeOptions(Locator container) {
        return normalize(container.locator("select option").allInnerTexts());
    }

    /**
     * The options a scenario could actually choose, placeholder excluded.
     *
     * An option with an empty value submits nothing — that IS the definition of
     * a "Seçiniz" placeholder, and matching on the empty value rather than on
     * the Turkish word keeps this working on a screen that says "Select" or
     * "Seçim yapınız" instead.
     *
     * Empty when the widget has no native select behind it at all; the caller
     * falls back to what it already read.
     */
    private List<String> selectableOptions(Locator container) {
        // Three ways an option can be unpickable, all of them attributes rather
        // than words: no value to submit, switched off, or hidden. Matching on
        // attributes keeps this working on a screen that says "Select" instead
        // of "Seçiniz". NOTE: this app was seen to give its "Seçiniz" a real
        // value, so these three may not catch it — if a placeholder still
        // reaches a draft, run with UPTEST_DISCOVERY_DEBUG=true and read the
        // nativeOptions=[value=text] dump to find out how it IS marked.
        return normalize(container.locator(
                "select option:not([value='']):not([disabled]):not([hidden])").allInnerTexts());
    }

    /**
     * Opens the v4 list only to read it, and always closes it again.
     *
     * Returns nothing when the list never renders — which happens for a
     * dropdown that depends on a field nobody has filled yet. Saying so
     * honestly matters: the old code put a "# okunamadi" line in the options
     * instead, and that fake entry then made the fill step reject a perfectly
     * valid value for not being on a list that was never read.
     *
     * Bounded too. Playwright's default 30 s was spent on every unreadable
     * dropdown, and a list that has not rendered in three seconds is not on
     * its way.
     */
    private List<String> openAndReadOptions(Locator container) {
        try {
            // Bounded too: an unclickable widget otherwise burns Playwright's
            // default 30 s here before the wait below even begins.
            container.locator(".select2-selection").first()
                    .click(new Locator.ClickOptions().setTimeout(OPTION_TIMEOUT_MS));
            page.waitForSelector("li.select2-results__option",
                    new Page.WaitForSelectorOptions().setTimeout(OPTION_TIMEOUT_MS));
            return normalize(page.locator("li.select2-results__option").allInnerTexts());
        } catch (RuntimeException e) {
            System.out.println("[uptest] secenekler okunamadi (" + e.getClass().getSimpleName()
                    + "); alan seceneksiz kataloglaniyor");
            return List.of();
        } finally {
            page.keyboard().press("Escape");
        }
    }

    /**
     * Option texts arrive with the newlines the app lays them out with — an
     * account row is six lines of type, branch, IBAN and three balances. Left
     * as they are they break the draft: a double-quoted YAML scalar cannot
     * contain a raw newline.
     */
    private static List<String> normalize(List<String> texts) {
        return texts.stream()
                .map(text -> text.replaceAll("\\s+", " ").trim())
                .filter(text -> !text.isEmpty())
                .toList();
    }

    private String extractLabel(Locator container) {
        Locator label = container.locator("label");
        if (label.count() > 0) {
            String text = safeText(label.first());
            if (!text.isBlank()) {
                return text;
            }
        }
        Locator spans = container.locator("span");
        for (int i = 0; i < spans.count(); i++) {
            String text = safeText(spans.nth(i));
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    /**
     * A line of the accessibility tree that CommonActions.click could press:
     * {@code - button "Devam"}, {@code - link "Geri":}, with an optional state
     * flag such as {@code [disabled]} after the name.
     */
    private static final Pattern CLICKABLE =
            Pattern.compile("^\\s*-\\s+(?:button|link)\\s+\"((?:[^\"\\\\]|\\\\.)*)\"", Pattern.MULTILINE);

    /**
     * Every button and link the {@code click} step could actually drive.
     *
     * Read from the accessibility tree rather than by walking elements and
     * taking innerText, because that is the tree CommonActions.click queries:
     * it matches getByRole(BUTTON|LINK).setName(label), and an accessible name
     * is NOT innerText. {@code <input type="submit" value="Devam">} is named
     * "Devam" while its innerText is empty, so the old text-based collection
     * dropped exactly the buttons an old form app is most likely to use.
     *
     * The tree also excludes hidden and aria-hidden nodes, so visibility needs
     * no separate check. Elements the tree gives no button/link role — a
     * {@code <div onclick>} styled as a button, say — are absent on purpose:
     * {@code click} cannot press those either, and listing one would produce a
     * scenario that fails at run time.
     */
    private List<String> clickableNames() {
        Set<String> names = new LinkedHashSet<>();
        Matcher match = CLICKABLE.matcher(page.locator("body").ariaSnapshot());
        while (match.find()) {
            String name = match.group(1).replace("\\\"", "\"").trim();
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return new ArrayList<>(names);
    }

    /* ========================= dependency observing ======================== */

    /**
     * How long the screen gets to react to one fill.
     *
     * An auto-filled value arrives from the server, so it is not there the
     * instant the field is set. A floor, then poll until nothing changes,
     * then give up: a screen that has not reacted in DEP_MAX_MS was not going
     * to. Deliberately small — this runs once per filled field.
     */
    private static final double DEP_MIN_MS = 600;
    private static final double DEP_POLL_MS = 250;
    private static final double DEP_MAX_MS = 2500;

    /**
     * Every visible field's state right now, by label.
     *
     * The same container walk the scan uses, so the two always agree on what
     * counts as a field and what its label is.
     */
    private Map<String, FieldState> snapshotState() {
        Map<String, FieldState> state = new LinkedHashMap<>();
        for (Locator container : page.locator("div[class*='fieldcontainer']").all()) {
            if (!container.isVisible()) {
                continue;
            }
            String label = extractLabel(container);
            if (label.isBlank() || state.containsKey(label)) {
                continue;
            }
            state.put(label, readState(container));
        }
        return state;
    }

    private FieldState readState(Locator container) {
        boolean select2 = container.locator(".select2-selection").count() > 0;
        boolean enabled = select2
                ? container.locator(ENABLED_SELECT2).count() > 0
                : container.locator(enabled("input", "select")).count() > 0;

        // What the user would read off the screen. A Select2 renders its own
        // chosen text, a native select its selected option, an input its value.
        String value;
        if (select2) {
            value = safeText(container.locator(".select2-selection").first());
        } else if (container.locator("select").count() > 0) {
            value = safeText(container.locator("select option:checked").first());
        } else if (container.locator("input").count() > 0) {
            value = inputValue(container.locator("input").first());
        } else {
            value = safeText(container);
        }

        return new FieldState(enabled, value, container.locator("select option:not([value=''])").count());
    }

    private static String inputValue(Locator input) {
        try {
            return input.inputValue().trim();
        } catch (RuntimeException notAnInput) {
            return "";
        }
    }

    /** Waits for the screen to stop reacting, then reports its state. */
    private Map<String, FieldState> settledState() {
        page.waitForTimeout(DEP_MIN_MS);
        long deadline = System.currentTimeMillis() + (long) (DEP_MAX_MS - DEP_MIN_MS);
        Map<String, FieldState> previous = snapshotState();
        while (System.currentTimeMillis() < deadline) {
            page.waitForTimeout(DEP_POLL_MS);
            Map<String, FieldState> current = snapshotState();
            if (current.equals(previous)) {
                return current;
            }
            previous = current;
        }
        return previous;
    }

    /**
     * What changed on the rest of the screen because this field was filled.
     *
     * This is the whole point of the pass: the dependencies that cost this
     * project a night of guessing are invisible in a static DOM and only show
     * up as a difference. "Bayi Kodu" fills "Bayi Ünvanı" in and loads "Döviz
     * Cinsi" with options — neither is written down anywhere, both are
     * observable here for free.
     *
     * A placeholder ("Seçiniz") is not a value, so a widget that merely shows
     * one does not count as auto-filled.
     */
    private void recordEffects(String cause, Map<String, FieldState> before, Map<String, FieldState> after) {
        List<Effect> found = new ArrayList<>();

        after.forEach((label, now) -> {
            if (label.equals(cause)) {
                return;
            }
            FieldState was = before.get(label);
            if (was == null) {
                found.add(new Effect(label, "görünür oldu"));
                return;
            }
            if (!was.enabled() && now.enabled()) {
                found.add(new Effect(label, "aktifleşti"));
            }
            if (was.optionCount() == 0 && now.optionCount() > 0) {
                found.add(new Effect(label, "seçenekleri yüklendi"));
                // Read them NOW, while they exist. The scan saw this dropdown
                // before its parent was chosen, so it recorded an empty list —
                // or worse, only the placeholder, which the draft then offered
                // as the one thing a scenario could choose ("Döviz Cinsi" came
                // back as options: ["Seçiniz"]). This is the one moment the
                // real list is on screen and nobody was looking.
                Field loaded = catalog.get(label);
                if (loaded != null) {
                    List<String> live = liveOptions(label);
                    if (!live.isEmpty()) {
                        loaded.choices = live;
                    }
                }
            }
            if (was.value().isEmpty() && !now.value().isEmpty()) {
                found.add(new Effect(label, "otomatik doldu"));
                Field filled = catalog.get(label);
                if (filled != null) {
                    // The application owns this value. Cataloguing it as
                    // writable is what sent fill() into a 30 s retry on a
                    // disabled "Bayi Ünvanı".
                    filled.readonly = true;
                }
            }
        });

        if (!found.isEmpty()) {
            effects.put(cause, found);
        }
    }

    /** One log line per cause, for the run output. */
    private static String describe(String cause, List<Effect> caused) {
        return "\"" + cause + "\" secilince: " + caused.stream()
                .map(e -> "\"" + e.label() + "\" " + e.what())
                .reduce((a, b) -> a + ", " + b).orElse("");
    }

    /* =============================== filling ============================== */

    /**
     * Fills the current screen from one stage's value table, in the order the
     * screen lists its fields, using the verb the scan derived.
     *
     * A label the screen does not offer is a hard failure, not a skip: the
     * values belong to THIS stage, so an unmatched one is a typo, and the error
     * names what the screen actually has rather than leaving the analyst to
     * wonder why a field stayed empty.
     *
     * @return one human-readable line per field, for the run log
     */
    public List<String> fillCurrentScreen(Map<String, String> values) {
        List<String> unknown = new ArrayList<>();
        for (String label : values.keySet()) {
            if (!currentScreen.containsKey(label)) {
                unknown.add(label);
            }
        }
        if (!unknown.isEmpty()) {
            throw new AssertionError(
                    "Bu ekranda bulunamayan alan(lar): " + String.join(", ", unknown)
                            + " | Ekranda doldurulabilen alanlar: "
                            + (currentScreen.isEmpty() ? "(yok)" : String.join(", ", currentScreen.keySet())));
        }

        List<String> report = new ArrayList<>();
        for (Field field : currentScreen.values()) {
            String value = values.get(field.label);
            if (value == null) {
                continue;
            }
            // Before/after around this one fill: the difference IS the
            // dependency. Cheap because we are already standing here with the
            // browser open and about to act.
            Map<String, FieldState> before = snapshotState();

            String used = value;
            switch (field.type) {
                case TEXT, DATE -> actions.enter(field.label, value);
                case AMOUNT -> actions.enterAmount(field.label, value);
                case DROPDOWN -> {
                    used = resolveOption(field, value, freshOptions(field));
                    actions.select(used, field.label);
                }
                case ACCOUNT_PICKER -> actions.selectAccount(value, field.label);
                default -> throw new AssertionError(
                        "\"" + field.label + "\" tipi " + field.type.schemaName
                                + "; onu surecek bir adim yok. Bu alani deger listesinden cikarin.");
            }
            recordEffects(field.label, before, settledState());

            // The option actually chosen, not just what was asked for: a
            // partial value resolves to a whole option text, and "fatura"
            // quietly matching "E-Fatura" cost a run before the log said so.
            report.add(field.label + " <- " + value
                    + (used.equals(value) ? "" : " => \"" + used + "\"")
                    + "  (" + field.type.schemaName + ")");

            List<Effect> caused = effects.get(field.label);
            if (caused != null) {
                report.add("  -> " + describe(field.label, caused));
            }
        }
        return report;
    }

    /**
     * The option text the {@code select} step needs, from whatever the analyst
     * had to go on.
     *
     * {@code selectOption} matches an option's WHOLE label, but a dropdown
     * prints more than the thing being chosen — a dealer code comes as
     * "93002 - FORD BAYİ". Asking the analyst for that exact string means
     * knowing the screen before discovering it, which is backwards. The scan
     * already read the options, so a unique substring is enough and this
     * resolves it.
     *
     * When nothing matches, the error carries the real options: the next
     * attempt is then a copy, not another guess.
     */
    private static String resolveOption(Field field, String value, List<String> choices) {
        // Nothing readable: the value may still be perfectly good, and the
        // select step can report its own failure better than a guess here.
        if (choices.isEmpty()) {
            return value;
        }
        if (choices.contains(value)) {
            return value;
        }
        for (String option : choices) {
            if (option.toLowerCase().contains(value.toLowerCase())) {
                return option;
            }
        }
        throw new AssertionError(
                "\"" + field.label + "\" icin \"" + value + "\" secenegi yok. Secenekler: "
                        + String.join(" | ", choices));
    }

    /**
     * The field's options as they stand RIGHT NOW, not as the scan saw them.
     *
     * On this app a dropdown can depend on another: "Döviz Cinsi" holds nothing
     * until "Bayi Kodu" is chosen. The scan reads the form before anything is
     * filled, so a dependent list is genuinely empty then and only fills in
     * during this very step — fields are filled in screen order, so whatever a
     * dropdown depends on has already been set by the time we reach it.
     *
     * Falls back to what the scan recorded when the live read comes up empty.
     */
    private List<String> freshOptions(Field field) {
        List<String> live = liveOptions(field.label);
        return live.isEmpty() ? field.choices : live;
    }

    /**
     * What this field offers on screen right now — empty when the field cannot
     * be found or has nothing to offer yet. No fallback here on purpose: the
     * callers differ on what "nothing" should mean.
     */
    private List<String> liveOptions(String label) {
        Locator container = page.locator("div[class*='fieldcontainer']")
                .filter(new Locator.FilterOptions().setHas(
                        page.locator("label:text-is(\"" + label.replace("\"", "\\\"") + "\")")))
                .first();

        return container.count() == 0 ? List.of() : selectableOptions(container);
    }

    /* =============================== output =============================== */

    /**
     * The observed half of a catalog: fields and buttons.
     *
     * No app/flow/navigation header here — this class only ever sees the screen
     * it is standing on, and cannot know which application it logged into or
     * the menu path that got it here. The orchestrator asked for all three and
     * writes them itself (CatalogDiscoveryService), so the analyst is never
     * asked to retype what the form already collected.
     */
    public String toYaml() {
        StringBuilder yaml = new StringBuilder();
        yaml.append("fields:\n");

        // catalog is insertion-ordered and a field is inserted the first time it
        // is seen, so the screen number only ever climbs: a comment whenever it
        // changes is enough to group the flat list SCHEMA.md requires.
        int lastScreen = 0;
        for (Field field : catalog.values()) {
            if (field.firstSeenScreen != lastScreen) {
                lastScreen = field.firstSeenScreen;
                yaml.append("  # --- ekran ").append(lastScreen).append(" ---\n");
            }
            // What this field set off, written above it so the ordering the
            // screen requires is visible to whoever reads the catalog next —
            // analyst or model.
            List<Effect> caused = effects.get(field.label);
            if (caused != null) {
                yaml.append("    # ").append(describe(field.label, caused)).append('\n');
            }

            yaml.append("  - label: \"").append(escape(field.label)).append("\"\n");
            yaml.append("    type: ").append(field.type.schemaName).append('\n');
            if (field.readonly) {
                yaml.append("    readonly: true\n");
            }
            if (field.verifiable) {
                yaml.append("    verifiable: true\n");
            }
            appendLimits(yaml, field);
            if (field.type == Type.DROPDOWN) {
                appendList(yaml, "    ", "options", field.choices);
            } else if (field.type == Type.ACCOUNT_PICKER) {
                appendList(yaml, "    ", "accounts", field.choices);
            } else if (field.type == Type.UNSUPPORTED_V3) {
                yaml.append("    # UNSUPPORTED_V3: Select2 v3 picker, no vocabulary step drives this\n");
            }
        }

        appendButtons(yaml);
        appendUnrecognised(yaml);
        yaml.append(debugLog);
        return yaml.toString();
    }

    /**
     * What the field's own attributes said it accepts.
     *
     * Only what was actually read: an absent key means the screen did not say,
     * and the generator treats that differently from a limit of zero. Writing
     * {@code max_length: 0} for a box with no maxlength would be the kind of
     * confident wrong answer this class exists to avoid.
     *
     * This is the one part of a catalog only the DOM scan can produce. The MCP
     * agent works from an accessibility snapshot, which carries no attributes at
     * all, so an agent-written catalog is silent here and the planner says so
     * rather than inventing a bound. Same shape as the Select2 v3-versus-v4 split
     * — two producers, opposite blind spots, both kept.
     */
    private static void appendLimits(StringBuilder yaml, Field field) {
        if (!field.limits.known()) {
            return;
        }
        if (field.limits.maxLength() != null) {
            yaml.append("    max_length: ").append(field.limits.maxLength()).append('\n');
        }
        if (field.limits.min() != null) {
            yaml.append("    min: ").append(field.limits.min()).append('\n');
        }
        if (field.limits.max() != null) {
            yaml.append("    max: ").append(field.limits.max()).append('\n');
        }
        if (field.limits.numeric()) {
            yaml.append("    numeric: true\n");
        }
    }

    /**
     * Fields the scan saw but could not type. A comment, not a catalog entry:
     * no vocabulary step drives a radio outside a grid or a textarea, so
     * listing one as a field would produce a scenario that fails in the
     * browser. Listing it as a comment tells whoever reads the draft that the
     * screen has more on it than the catalog claims.
     *
     * Checkboxes used to be in here and are not any more: `check {string}`
     * drives them, so they are catalogued as `checkbox` like any other field.
     */
    private void appendUnrecognised(StringBuilder yaml) {
        if (unrecognised.isEmpty()) {
            return;
        }
        yaml.append("\n# taninmayan alanlar - katalogda yok, elle bakin:\n");
        unrecognised.forEach((label, control) -> {
            yaml.append("#   \"").append(escape(label)).append("\" -> ").append(control).append('\n');
            System.out.println("[uptest] taninmayan alan: \"" + label + "\" -> " + control);
        });
    }

    /**
     * Flow buttons in the catalog, the site menu in a comment beside it.
     *
     * A button on every single screen scanned is chrome — the top menu rides
     * along on all of them — while "Devam" and "Onay" belong to one screen
     * each. Sending all 25 to the model would spend prompt on 21 buttons no
     * scenario for this flow will ever press.
     *
     * With only one screen scanned there is nothing to compare, so everything
     * is listed: a wrong guess there would quietly drop the flow's real
     * buttons.
     */
    private void appendButtons(StringBuilder yaml) {
        List<String> flowButtons = new ArrayList<>();
        List<String> chrome = new ArrayList<>();

        buttonScreens.forEach((name, screens) -> {
            boolean onEveryScreen = screensScanned > 1 && screens == screensScanned;
            (onEveryScreen ? chrome : flowButtons).add(name);
        });

        appendList(yaml, "", "buttons", flowButtons);
        if (!chrome.isEmpty()) {
            // To the log, not the catalog: what was excluded is worth seeing
            // once, but the catalog is pasted into every prompt and a comment
            // costs the same tokens as a key.
            System.out.println("[uptest] katalog disi birakilan site menusu: " + String.join(", ", chrome));
        }
    }

    /**
     * Raw counts for the screen showing right now, so an empty {@code fields:}
     * can be explained instead of guessed at: nothing matched the container
     * selector (wrong screen), or containers matched but extractLabel came back
     * blank (the heuristic needs this app's markup), or they held no control
     * classify recognises.
     *
     * CLAUDE.md's rule, applied: print the counts before assuming why a locator
     * failed.
     */
    private void recordDiagnostics() {
        if (!DEBUG) {
            return;
        }
        Locator containers = page.locator("div[class*='fieldcontainer']");
        int total = containers.count();

        StringBuilder out = debugLog;
        out.append("# debug ekran ").append(screensScanned).append(": url=").append(page.url()).append('\n');
        out.append("# debug ekran ").append(screensScanned).append(": ").append(total).append(" fieldcontainer\n");

        for (int i = 0; i < Math.min(total, DEBUG_LIMIT); i++) {
            Locator c = containers.nth(i);
            out.append("#   [").append(i).append("]")
                    .append(" visible=").append(c.isVisible())
                    .append(" input=").append(c.locator("input").count())
                    .append(" select=").append(c.locator("select").count())
                    .append(" label=").append(c.locator("label").count())
                    .append(" span=").append(c.locator("span").count())
                    .append(" s2c=").append(c.locator(".select2-container").count())
                    .append(" s2s=").append(c.locator(".select2-selection").count())
                    .append(" | extractLabel=\"").append(extractLabel(c)).append('"')
                    .append(attributeDump(c))
                    .append(nativeOptionDump(c))
                    .append(" | text=\"").append(truncate(safeText(c))).append("\"\n");
        }
        if (total > DEBUG_LIMIT) {
            out.append("#   ... ").append(total - DEBUG_LIMIT).append(" tane daha\n");
        }
    }

    /**
     * The constraint attributes of every input in this container, raw.
     *
     * The question it answers: when a catalog comes back without a
     * {@code max_length} for a field that clearly has one on screen, is the
     * attribute missing or is {@link #limitsOf} looking at the wrong input? One
     * is an application fact, the other is our bug, and nothing else in the
     * output separates them. CLAUDE.md's rule again — print the counts before
     * assuming why a locator failed.
     */
    private static String attributeDump(Locator container) {
        Locator inputs = container.locator("input");
        int count = inputs.count();
        if (count == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder(" | inputs=");
        for (int i = 0; i < Math.min(count, DEBUG_OPTIONS); i++) {
            Locator input = inputs.nth(i);
            try {
                out.append('[')
                        .append("id=").append(input.getAttribute("id"))
                        .append(" type=").append(input.getAttribute("type"))
                        .append(" maxlength=").append(input.getAttribute("maxlength"))
                        .append(" inputmode=").append(input.getAttribute("inputmode"))
                        .append(" min=").append(input.getAttribute("min"))
                        .append(" max=").append(input.getAttribute("max"))
                        .append(" pattern=").append(input.getAttribute("pattern"))
                        .append(']');
            } catch (RuntimeException gone) {
                out.append("[okunamadi]");
            }
        }
        return out.toString();
    }

    /**
     * A native select's options as value=text pairs.
     *
     * Open question this answers: how the app marks its "Seçiniz" placeholder.
     * The HTML convention is an empty value, and if that holds the placeholder
     * can be filtered out of a dropdown's options without matching on a Turkish
     * word. Measured before it is relied on.
     */
    private String nativeOptionDump(Locator container) {
        Locator options = container.locator("select option");
        int count = options.count();
        if (count == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder(" | nativeOptions=");
        for (int i = 0; i < Math.min(count, DEBUG_OPTIONS); i++) {
            Locator option = options.nth(i);
            out.append('[').append(option.getAttribute("value")).append('=').append(safeText(option)).append(']');
        }
        return out.toString();
    }

    private static String truncate(String text) {
        return text.length() <= DEBUG_TEXT ? text : text.substring(0, DEBUG_TEXT) + "…";
    }

    private static String safeText(Locator locator) {
        try {
            return locator.innerText().replaceAll("\\s+", " ").trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static void appendList(StringBuilder yaml, String indent, String key, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        yaml.append(indent).append(key).append(":\n");
        for (String v : values) {
            yaml.append(indent).append("  - \"").append(escape(v)).append("\"\n");
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
