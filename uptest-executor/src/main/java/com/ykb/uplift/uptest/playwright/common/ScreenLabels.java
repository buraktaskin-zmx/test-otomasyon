package com.ykb.uplift.uptest.playwright.common;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What is actually on the screen right now, so a step that could not find its
 * label can say what it DID find.
 *
 * A label-based locator that matches nothing costs Playwright's full 30 s and
 * then reports "element(s) not found" - a sentence that names neither the
 * cause nor the fix. The analyst's real question at that moment is "then what
 * IS on this screen?", and the page can answer it for free: no model, no
 * network, no token.
 *
 * Deliberately read-only, and only ever called on the failure path. Nothing
 * here may change what a passing run does.
 *
 * NOTE: this covers the same ground CatalogDiscovery does for the catalog
 * draft (fieldcontainer labels, plus button/link names off the accessibility
 * tree). It is reimplemented rather than shared because that class is a
 * stateful scan in the discovery package, which the vocabulary deliberately
 * excludes. If a third caller appears, move the enumeration here and let
 * discovery call it.
 */
public final class ScreenLabels {

    /** Nobody reads a list of forty; three is what a suggestion is for. */
    private static final int MAX_SUGGESTIONS = 3;

    /**
     * A line of the accessibility tree naming something {@code click} could
     * press: {@code - button "Devam"}, {@code - link "Geri":}, optionally with
     * a state flag such as {@code [disabled]} after the name.
     *
     * Read from the tree rather than from innerText because that is what
     * {@code click} queries: getByRole(BUTTON|LINK).setName(...) matches the
     * ACCESSIBLE name, and {@code <input type="submit" value="Devam">} is
     * named "Devam" while its innerText is empty. A text-based collection
     * drops exactly the buttons an old form app is most likely to use.
     */
    private static final Pattern CLICKABLE =
            Pattern.compile("^\\s*-\\s+(?:button|link)\\s+\"((?:[^\"\\\\]|\\\\.)*)\"", Pattern.MULTILINE);

    /**
     * The same, for the controls a value-bearing step addresses. {@code enter}
     * resolves through getByLabel, which also matches the accessible name, so
     * the tree is the right source here too.
     */
    private static final Pattern FORM_CONTROL = Pattern.compile(
            "^\\s*-\\s+(?:textbox|combobox|checkbox|radio|spinbutton|searchbox|slider)"
                    + "\\s+\"((?:[^\"\\\\]|\\\\.)*)\"", Pattern.MULTILINE);

    private final Page page;

    public ScreenLabels(Page page) {
        this.page = page;
    }

    /**
     * Labels of the value-bearing fields on screen.
     *
     * Two sources, because neither alone is complete: the accessibility tree
     * knows a control's accessible name (what getByLabel matches), and the
     * fieldcontainer scan sees a label this application renders as a plain
     * span with no programmatic association to its input.
     */
    public List<String> fieldLabels() {
        Set<String> names = new LinkedHashSet<>(namesIn(FORM_CONTROL));
        try {
            for (Locator container : page.locator("div[class*='fieldcontainer']").all()) {
                String label = labelOf(container);
                if (!label.isBlank()) {
                    names.add(label);
                }
            }
        } catch (RuntimeException unavailable) {
            // Best effort; see snapshot().
        }
        return List.copyOf(names);
    }

    /** Every button and link name the {@code click} step could drive. */
    public List<String> clickableNames() {
        return List.copyOf(new LinkedHashSet<>(namesIn(CLICKABLE)));
    }

    private List<String> namesIn(Pattern pattern) {
        List<String> names = new ArrayList<>();
        Matcher match = pattern.matcher(snapshot());
        while (match.find()) {
            String name = match.group(1).replace("\\\"", "\"").trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * A failing step must never fail for a SECOND reason while explaining the
     * first. Every read here is best effort: a detached page, a navigation in
     * flight, or a snapshot that throws costs us the suggestion, not the real
     * error message.
     */
    private String snapshot() {
        try {
            return page.locator("body").ariaSnapshot();
        } catch (RuntimeException unavailable) {
            return "";
        }
    }

    private static String labelOf(Locator container) {
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

    private static String safeText(Locator locator) {
        try {
            return locator.innerText().replaceAll("\\s+", " ").trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    // ===================== RANKING AND REPORTING =========================

    /** The entries of {@code pool} closest to {@code wanted}, nearest first. */
    public static List<String> nearest(String wanted, List<String> pool) {
        return pool.stream()
                .sorted(Comparator.comparingInt(candidate -> distance(fold(wanted), fold(candidate))))
                .limit(MAX_SUGGESTIONS)
                .toList();
    }

    /**
     * The sentence appended to a "could not find it" failure.
     *
     * Reports the two cases apart, because they call for opposite fixes: an
     * empty screen means the PREVIOUS step did not land, while a screen full
     * of other labels means this step's label is wrong or the catalog has
     * gone stale.
     */
    public static String report(String wanted, List<String> pool, String what) {
        if (pool.isEmpty()) {
            return " Ekranda hic " + what + " bulunamadi"
                    + " - onceki adim beklenen ekrani acmamis olabilir.";
        }
        return " Ekrandaki en yakin " + what + ": "
                + String.join(" | ", nearest(wanted, pool))
                + " (toplam " + pool.size() + ").";
    }

    /**
     * Turkish letters folded to ASCII before measuring, so "Tutari" and
     * "Tutari" with a dotted i rank as the same word rather than one edit
     * apart. The failures this exists to explain are overwhelmingly that shape.
     *
     * Written as \\u escapes on purpose: this file is hand-copied between the
     * VDI and the development machine, and a copy through the wrong encoding
     * would corrupt the table silently - folding nothing, while still
     * compiling and still returning plausible-looking suggestions.
     */
    private static final String TURKISH = "\u00e7\u011f\u0131\u00f6\u015f\u00fc\u00e2\u00ee\u00fb";
    private static final String ASCII = "cgiosuaiu";

    /**
     * Turkish, so that the dotted and dotless I lower-case the way the labels
     * on these screens are actually written. forLanguageTag rather than the
     * Locale constructor: the latter is deprecated from Java 19 and this code
     * should still compile cleanly when the toolchain moves past 17.
     */
    private static final Locale TR = Locale.forLanguageTag("tr-TR");

    private static String fold(String text) {
        String lowered = text.toLowerCase(TR);
        StringBuilder out = new StringBuilder(lowered.length());
        for (char c : lowered.toCharArray()) {
            int index = TURKISH.indexOf(c);
            out.append(index >= 0 ? ASCII.charAt(index) : c);
        }
        return out.toString().replaceAll("\\s+", " ").trim();
    }

    /** Plain Levenshtein; these lists are tens of entries at most. */
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
}
