package com.ykb.uplift.uptest.playwright.common;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.options.WaitForSelectorState;

import java.util.List;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Select2 dropdowns: telling one apart, opening it, choosing from it, and the
 * search box its open list carries.
 *
 * Split out of {@link CommonActions} unchanged.
 */
final class Dropdowns {

    private final Page page;
    private final ValueFields fields;

    Dropdowns(Page page, ValueFields fields) {
        this.page = page;
        this.fields = fields;
    }

    /**
     * How long the widget gets to show the value that was just chosen. Doubles
     * as the pause the application needs to react to the choice.
     */
    static final double SELECT_CONFIRM_MS = 10000;

    /** Opens the widget, clicks the matching option, and checks it took. */
    void selectFromSelect2(String optionText, String label) {
        openSelect2(label);
        String want = optionText.trim();

        List<Locator> options = page.locator("li.select2-results__option").all();
        Locator chosen = null;
        for (Locator option : options) {
            if (option.innerText().trim().equals(want)) {
                chosen = option;
                break;
            }
        }
        if (chosen == null) {
            // A contained match before giving up: an option often prints more
            // than the thing being chosen ("93002 - FORD BAYİ").
            for (Locator option : options) {
                if (option.innerText().contains(want)) {
                    chosen = option;
                    break;
                }
            }
        }
        if (chosen == null) {
            throw new AssertionError("'" + label + "' icin '" + optionText
                    + "' secenegi yok. Listelenen: " + String.join(" | ", page
                    .locator("li.select2-results__option").allInnerTexts()));
        }

        chosen.click();

        // The click is not the point — the value landing is. A selection that
        // silently fails to stick has cost three separate debugging rounds on
        // this app: the step went green and the damage surfaced fields later,
        // on whatever the app was supposed to load in response. Auto-waiting
        // here also gives that reaction time to happen before the next field.
        assertThat(findValueElement(label).locator(".select2-selection"))
                .containsText(want,
                        new LocatorAssertions.ContainsTextOptions().setTimeout(SELECT_CONFIRM_MS));
    }

    // Opens the Select2 v4 widget belonging to a field, by its label. Shared by
    // the account picker and by ordinary dropdowns: both are the same widget,
    // they differ only in how the caller then matches an option (digits for an
    // account, text for a dropdown). The list renders at <body> level, so
    // callers read the options page-wide, not inside the field container.
    void openSelect2(String label) {
        Locator container = findValueElement(label);
        container.locator(".select2-selection").click();
        page.waitForSelector("li.select2-results__option");
    }

    /** How long a click waits for its button, or its dropdown's label, to arrive. */
    private static final double CLICK_DECIDE_MS = 15000;

    /** How long a found label's Select2 widget gets to be built by the page's script. */
    private static final double SELECT2_INIT_MS = 5000;

    /** Waits until a button/link with this name OR this label's text is on the page. */
    void awaitButtonOrLabel(String label, Locator target) {
        try {
            target.or(page.getByText(label, new Page.GetByTextOptions().setExact(true))).first()
                    .waitFor(new Locator.WaitForOptions()
                            .setState(WaitForSelectorState.ATTACHED).setTimeout(CLICK_DECIDE_MS));
        } catch (RuntimeException neitherArrived) {
            // Decided below exactly as before: the ordinary click then reports
            // the missing button with the screen's own candidates.
        }
    }

    /**
     * Whether this label's field is a Select2 dropdown, giving the widget a
     * moment to be built: the label can be on the page before the script
     * that wraps the native select has run.
     */
    boolean awaitSelect2Field(String label) {
        try {
            findValueElement(label).locator(".select2-selection").first()
                    .waitFor(new Locator.WaitForOptions()
                            .setState(WaitForSelectorState.ATTACHED).setTimeout(SELECT2_INIT_MS));
            return true;
        } catch (RuntimeException notADropdown) {
            return false;
        }
    }

    /** Whether this label belongs to a Select2 v4 dropdown. Does not wait. */
    boolean isSelect2Field(String label) {
        try {
            return findValueElement(label).locator(".select2-selection").count() > 0;
        } catch (RuntimeException notAField) {
            return false;
        }
    }

    /**
     * The search box of this label's dropdown when its list is OPEN, else
     * null. Select2 v4 renders the open list at body level and marks its
     * container "--open"; one list is open at a time.
     */
    Locator openSelect2Search(String label) {
        if (!isSelect2Field(label)) {
            return null;
        }
        Locator search = page.locator(".select2-container--open .select2-search__field");
        return (search.count() > 0 && search.first().isVisible()) ? search.first() : null;
    }

    /**
     * Whether `select` must drive this field as a Select2 — decided only once
     * the field is on the page.
     *
     * `select` used to ask with count(), which does not wait, and it can run
     * straight after `navigate to` has clicked a menu link and returned. Asked
     * before the page arrived, it answered "not a Select2" and took the
     * native branch: a forced selectOption on the hidden select, by EXACT
     * label. On Firma Fatura İşlemleri that turned "APPLE A.Ş" into 30 s of
     * "did not find some options" — the Select2 branch, which also accepts a
     * contained match, would have chosen "APPLE A.Ş." at once.
     *
     * Three signs, cheapest first: the widget itself; the marks Select2 puts
     * on the native select it wraps (measured on this app:
     * {@code class="select2-hidden-accessible" data-select2-id="firmIdx"});
     * and, for a select seen before the script ran, a few seconds for the
     * widget to appear. Only a plain native select pays that last wait.
     */
    boolean awaitIsSelect2(String label) {
        Locator container = findValueElement(label);
        try {
            container.locator("select, .select2-selection").first()
                    .waitFor(new Locator.WaitForOptions()
                            .setState(WaitForSelectorState.ATTACHED).setTimeout(CLICK_DECIDE_MS));
        } catch (RuntimeException neverArrived) {
            return false;
        }
        if (container.locator(".select2-selection").count() > 0
                || container.locator("select.select2-hidden-accessible, select[data-select2-id]").count() > 0) {
            return true;
        }
        return awaitSelect2Field(label);
    }

    private Locator findValueElement(String label) {
        return fields.findValueElement(label);
    }
}
