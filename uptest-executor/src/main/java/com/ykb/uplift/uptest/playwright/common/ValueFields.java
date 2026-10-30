package com.ykb.uplift.uptest.playwright.common;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.util.regex.Pattern;

/**
 * From a label to the element that holds its value, in the shapes this
 * application renders, and the amount comparison the verifies share.
 *
 * Split out of {@link CommonActions} unchanged.
 */
final class ValueFields {

    private final Page page;
    private final ErrorDialogs dialogs;

    ValueFields(Page page, ErrorDialogs dialogs) {
        this.page = page;
        this.dialogs = dialogs;
    }

    // ========================= HELPERS ===================================

    /**
     * The element inside a value container that actually carries the text. The
     * app's own forms wrap it in a value span; a plain confirmation table has
     * no span at all and the cell holds the text directly.
     */
    /**
     * The expected value, allowing the field's own label to be glued in front
     * of it — which is how the DBS confirmation screen renders: label and value
     * share one container, so the text reads "Bayi Kodu93002".
     *
     * Still an exact match on the value itself (the two-day normalize bug is
     * not being reopened): the label is the ONLY thing tolerated, and only at
     * the start.
     *
     * Escaped by hand rather than with Pattern.quote, because Playwright hands
     * the pattern to the BROWSER — Java's \Q...\E means nothing to JavaScript
     * and would match no text at all.
     */
    static Pattern labelThenValue(String label, String value) {
        return Pattern.compile("^\\s*(?:" + escapeRegex(label.trim()) + "\\s*)?"
                + escapeRegex(value) + "\\s*$");
    }

    private static String escapeRegex(String text) {
        return text.replaceAll("([\\\\^$.|?*+()\\[\\]{}/])", "\\\\$1");
    }

    /** Drops a label glued to the front of a value, when it is there. */
    static String withoutLabel(String label, String text) {
        String trimmed = text.trim();
        String prefix = label.trim();
        return trimmed.startsWith(prefix) ? trimmed.substring(prefix.length()).trim() : trimmed;
    }

    /**
     * The element holding the value, when it can be told apart from the label.
     *
     * ONE value span is the ordinary case and it is the value. SEVERAL means
     * the label is wearing the same class as its value — the open issue on the
     * DBS Firma Fatura summary — and then {@code .first()} is the LABEL, so a
     * verify compared "Bayi Kodu" against the expected value and reported a
     * mismatch that said nothing about the real shape of the screen.
     *
     * The fix is to stop choosing. With more than one candidate the container
     * is returned whole, and its text reads "Bayi Kodu81234567" — which
     * labelThenValue and withoutLabel already accept, both written for exactly
     * this run-together rendering. Guessing is what was wrong; the machinery
     * for not guessing was already here.
     */
    static Locator valueHolder(Locator container) {
        Locator span = container.locator("span.elem-span-infos");
        return span.count() == 1 ? span.first() : container.first();
    }

    // Returns the input (if entry field) or the value span (if read-only display)
    // that belongs EXACTLY to this label. Auto-waits handled by the callers.
    Locator findValueElement(String label) {
        return resolveFrom(page.getByText(label, new Page.GetByTextOptions().setExact(true)).first());
    }

    /**
     * The element a VERIFY should read, which is not always the one an action
     * should drive.
     *
     * Two things the action path must NOT inherit, which is why this is a
     * separate entry point rather than a change to findValueElement:
     *
     * - A visible modal narrows the search. "Bayi Kodu" is on the Firma Fatura
     *   screen TWICE — once as a sortable column header, once inside the
     *   "Fatura Bilgileri" dialog — and the header comes first in document
     *   order, so getByText(...).first() lands on the header, which is a <th>
     *   with no fieldcontainer and no following cell. Measured on a recorded
     *   agent walk: the header is ref e587, the dialog's label is e668.
     *   visibleDialog()'s own javadoc already offered this ("so callers can
     *   scope a search to it"); nothing had taken it up.
     *
     * - A label and its value can share one parent. The last rung below
     *   returns that parent, whose text reads "Bayi Kodu81234567" — which
     *   labelThenValue and withoutLabel were already written to accept. It is
     *   reached ONLY when the three existing shapes all resolved to nothing,
     *   so no currently-passing screen changes behaviour.
     *
     * Deliberately not wired into select()/selectAccount(): returning a
     * container one level up from the label was tried on the ACTION path and
     * reverted, because a selectOption on a <span> that holds no <select>
     * spends the full timeout instead of failing clearly. Reading has no such
     * trap — the worst case is an assertion that compares the wrong text and
     * says so.
     */
    Locator readableValue(String label) {
        Locator labelEl = labelFor(label);
        Locator resolved = resolveFrom(labelEl);
        if (resolved.count() > 0) {
            return resolved;
        }
        Locator shared = labelEl.locator("xpath=parent::*");
        return shared.count() > 0 ? shared.first() : resolved;
    }

    /** The label element, preferring the copy inside an open modal. */
    private Locator labelFor(String label) {
        Locator box = visibleDialogBox();
        if (box != null) {
            Locator inDialog = box.getByText(label, new Locator.GetByTextOptions().setExact(true));
            if (inDialog.count() > 0) {
                // Printed only when the narrowing actually happens, which is
                // rare and always worth knowing: it is the difference between
                // reading the dialog and reading the grid header behind it.
                System.out.println("[uptest] verify: '" + label + "' acik diyalogun icinde arandi ("
                        + page.getByText(label, new Page.GetByTextOptions().setExact(true)).count()
                        + " aday vardi, " + inDialog.count() + " tanesi diyalogda)");
                return inDialog.first();
            }
        }
        return page.getByText(label, new Page.GetByTextOptions().setExact(true)).first();
    }

    /** The three container shapes this application renders, in order. */
    private Locator resolveFrom(Locator labelEl) {
        Locator container = labelEl.locator("xpath=ancestor::div[contains(@class,'fieldcontainer')][1]");

        // Plain confirmation table (DBS Fatura Oluştur "Onay" step): there is no
        // fieldcontainer anywhere above the label — it is an ordinary two-cell
        // row, label left, value right. Without this the chain below resolved to
        // nothing and every verify on that screen reported "element(s) not
        // found" instead of a value mismatch.
        //
        // Falls back to `container` — NOT to the label element — when there is
        // no such cell either. Returning the label was tried and reverted: on
        // the entry screen it turned a clear "element not found" into a 30 s
        // selectOption timeout hunting for a <select> inside a <span>.
        if (container.count() == 0) {
            Locator nextCell = labelEl.locator("xpath=ancestor::td[1]/following-sibling::td[1]");
            if (nextCell.count() > 0) {
                return nextCell.first();
            }
        }

        // modularFormTable layout: label and value are in SEPARATE <td> fieldcontainers.
        // The label cell's fieldcontainer holds only the label span, so look at the value
        // cell in the next <td>. Banko keeps using the single-container path above.
        Locator siblingValueCell = container
                .locator("xpath=ancestor::td[1]/following-sibling::td[1]//div[contains(@class,'fieldcontainer')]");
        if (siblingValueCell.count() > 0) {
            return siblingValueCell.first();
        }
        return container;
    }

    // Works for Turkish format (e.g 120,55 1.250,55)
    private String normalizeAmount(String raw) {
        if (raw == null) return "";
        // Strip everything except digits, dots and commas (removes TL, spaces, letters...)
        String cleaned = raw.replaceAll("[^0-9.,]", "");
        // Remove thousands' separator dots
        cleaned = cleaned.replace(".", "");
        // Convert the decimal comma to a dot
        cleaned = cleaned.replace(",", ".");
        if (cleaned.isEmpty()) return "";
        try {
            // Parse to a number so that 250 and 250.00 are treated as equal
            double value = Double.parseDouble(cleaned);
            return String.valueOf(value);
        } catch (NumberFormatException ex) {
            // Not a number: fall back to the cleaned raw string
            return cleaned;
        }
    }

    void assertAmountEquals(String expected, String actual) {
        String e = normalizeAmount(expected);
        String a = normalizeAmount(actual);
        if (!e.equals(a)) {
            throw new AssertionError(
                    "Expected value did not match the screen. Expected: '" + expected
                            + "' (normalized: " + e + "), On screen: '" + actual + "' (normalized: " + a + ")");
        }
    }

    private Locator visibleDialogBox() {
        return dialogs.visibleDialogBox();
    }
}
