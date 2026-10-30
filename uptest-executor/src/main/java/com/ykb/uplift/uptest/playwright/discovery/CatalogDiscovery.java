package com.ykb.uplift.uptest.playwright.discovery;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.ykb.uplift.uptest.playwright.common.CommonActions;
import com.ykb.uplift.uptest.playwright.discovery.Discovered.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.ykb.uplift.uptest.playwright.discovery.ControlTraits.*;
import static com.ykb.uplift.uptest.playwright.discovery.Discovered.describe;

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

    private final ScanDiagnostics diagnostics;

    public CatalogDiscovery(Page page) {
        this.page = page;
        this.actions = new CommonActions(page);
        this.diagnostics = new ScanDiagnostics(page, debugLog);
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
                    .filter(ControlTraits::carriesAccountNumber)
                    .map(ControlTraits::accountIdentifier)
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

    /** The draft so far, as YAML. See {@link CatalogYaml#toYaml()}. */
    public String toYaml() {
        return new CatalogYaml(catalog, effects, unrecognised, buttonScreens, screensScanned, debugLog)
                .toYaml();
    }

    /** See {@link ScanDiagnostics#recordDiagnostics(int)}. */
    private void recordDiagnostics() {
        diagnostics.recordDiagnostics(screensScanned);
    }
}
