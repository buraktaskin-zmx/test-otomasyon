package com.ykb.uplift.uptest.playwright.discovery;

import com.microsoft.playwright.Locator;
import com.ykb.uplift.uptest.playwright.discovery.Discovered.*;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What one field container is, read off its DOM: its label, the control it
 * holds, the options that control offers and the limits its attributes state.
 * Nothing here holds state; every method answers from the container it is
 * given.
 *
 * Split out of {@link CatalogDiscovery} unchanged.
 */
final class ControlTraits {

    private ControlTraits() {
    }

    /** A Select2 widget the app has not switched off. */
    static final String ENABLED_SELECT2 = ".select2-selection:not([aria-disabled='true'])";

    /**
     * Digits in an option before it counts as carrying an account number. A
     * local account number is nine, an IBAN twenty-four; the longest business
     * option on these screens has none at all, so the gap is wide.
     */
    private static final int ACCOUNT_DIGITS = 9;

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
    static Limits limitsOf(Locator container, String selector) {
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

    static String unrecognisedControl(Locator container) {
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
    static String enabled(String... selectors) {
        return String.join(", ", Arrays.stream(selectors)
                .map(selector -> selector + ":not([disabled]):not([readonly])")
                .toList());
    }

    /**
     * An account number is a long run of digits; no business option text
     * ("Vadesinde Peşin Ödensin", "Seçiniz") comes close. Counted across the
     * whole option because the app prints IBANs in spaced groups.
     */
    static boolean carriesAccountNumber(String option) {
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
    static String accountIdentifier(String option) {
        Matcher iban = IBAN.matcher(option);
        return iban.find() ? iban.group().replaceAll("\\s+", " ").trim() : option;
    }

    static List<String> nativeOptions(Locator container) {
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
    static List<String> selectableOptions(Locator container) {
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
     * Option texts arrive with the newlines the app lays them out with — an
     * account row is six lines of type, branch, IBAN and three balances. Left
     * as they are they break the draft: a double-quoted YAML scalar cannot
     * contain a raw newline.
     */
    static List<String> normalize(List<String> texts) {
        return texts.stream()
                .map(text -> text.replaceAll("\\s+", " ").trim())
                .filter(text -> !text.isEmpty())
                .toList();
    }

    static String extractLabel(Locator container) {
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

    static String safeText(Locator locator) {
        try {
            return locator.innerText().replaceAll("\\s+", " ").trim();
        } catch (RuntimeException e) {
            return "";
        }
    }
}
