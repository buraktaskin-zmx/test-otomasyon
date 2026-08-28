package com.ykb.uplift.uptest.playwright.common;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.SelectOption;

import java.util.List;
import java.util.Optional;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
  Application-independent Playwright logic shared by NGC and NGI.

  Everything here derives locators from labels/attributes/roles/text, no
  specific element ids or login flows. The page-object classes (NGC, NGI)
  reuse this for their internal generic actions (click, navigate); the
  generic step definitions live in {@link CommonSteps}.

  NOTE: findValueElement is currently the NGC structure
  (fieldcontainer / elem-span-infos)
 */
public class CommonActions {

    private final Page page;

    public CommonActions(Page page) {
        this.page = page;
    }

    // ======================= GENERIC ACTIONS ===============================

    public CommonActions click(String label) {
        Locator target = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(label))
                .or(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(label)));
        target.first().click();
        return this;
    }

    public CommonActions navigateToUrl(String url) {
        Page.NavigateOptions options = new Page.NavigateOptions();
        options.setTimeout(1000);
        try {
            page.navigate(url, options);
        } catch (Exception e) {

        }
        return this;
    }

    public CommonActions enter(String label, String value) {
        page.getByLabel(label).fill(value);
        return this;
    }

    // Top menu navigation: open the parent menu item, then click the child link.
    // NOTE: bound to the #headerMenuUsername / .main-nav header structure —
    // shared on the assumption NGC and NGI render the same menu markup.
    public CommonActions navigateTo(String parentMenuText, String child) {
        page.waitForSelector("#headerMenuUsername");
        List<Locator> links = page.locator("i").all();
        Optional<Locator> element = links.stream().filter(x -> x.innerText().equals(parentMenuText)).findFirst();
        element.get().click();

        page.locator(".main-nav a").all().stream().filter(x -> x.innerText().equals(child)).findFirst().get().click();

        return this;
    }

    public CommonActions enterAmount(String label, String value) {
        String wholePart;
        String fractionPart;
        if (value.contains(",")) {
            String[] parts = value.split(",");
            wholePart = parts[0];
            fractionPart = parts.length > 1 ? parts[1] : "00";
        } else {
            wholePart = value;
            fractionPart = "00";
        }

        Locator wholeField = page.getByLabel(label);
        wholeField.click();
        wholeField.fill("");
        wholeField.pressSequentially(wholePart);

        String wholeId = wholeField.getAttribute("id");  // e.g. "amount-integer"
        String fractionId = wholeId.replaceAll("-integer", "-decimal");
        Locator fractionField = page.locator("#" + fractionId);
        fractionField.click();
        fractionField.fill("");
        fractionField.pressSequentially(fractionPart);

        fractionField.blur();

        return this;
    }

    public CommonActions select(String optionText, String label) {
        Locator container = findValueElement(label);
        Locator select = container.locator("select");

        select.selectOption(new SelectOption().setLabel(optionText),
                new Locator.SelectOptionOptions().setForce(true));

        return this;
    }

    // Account picker rendered as a Select2 v4 combobox (rich rows:
    // text / branch / accountnumber), NOT a native <select>: the real
    // #...Idx select is aria-hidden, so the generic `select` step cannot drive
    // it. Open the widget via its field label, then click the option whose
    // digits contain the wanted account number (spacing/formatting ignored).
    public CommonActions selectAccount(String accountNumber, String label) {
        openAccountDropdown(label);
        String want = accountNumber.replaceAll("\\D", "");
        List<Locator> options = page.locator("li.select2-results__option").all();
        for (Locator option : options) {
            if (option.innerText().replaceAll("\\D", "").contains(want)) {
                option.click();
                return this;
            }
        }
        throw new AssertionError("No account option matching '" + accountNumber
                + "' (" + options.size() + " options listed)");
    }

    // Same picker, but choose by 0-based index over the listed options.
    public CommonActions selectAccount(int index, String label) {
        openAccountDropdown(label);
        page.locator("li.select2-results__option").nth(index).click();
        return this;
    }

    // Opens the Select2 v4 dropdown for the account field identified by its
    // label. The dropdown itself renders at <body> level, so callers read the
    // options page-wide, not inside the field container.
    private void openAccountDropdown(String label) {
        Locator container = findValueElement(label);
        container.locator(".select2-selection").click();
        page.waitForSelector("li.select2-results__option");
    }

    // Result grids pick a row with a radio button, not a <select>: there is no
    // fieldcontainer and no label-to-field binding, so the generic `select` step
    // cannot reach them. Match the row by the text next to its radio (the radio's
    // own cell first, then the whole row, so a value in any column still matches).
    public CommonActions selectRow(String rowLabel) {
        page.waitForSelector("input[type='radio']");
        String want = rowLabel.trim();
        List<Locator> radios = page.locator("input[type='radio']").all();

        for (Locator radio : radios) {
            if (rowText(radio, "td").contains(want)) {
                clickRadio(radio);
                return this;
            }
        }
        StringBuilder seen = new StringBuilder();
        for (Locator radio : radios) {
            String text = rowText(radio, "tr");
            seen.append("'").append(text.replace("\n", " | ")).append("' ");
            if (text.contains(want)) {
                clickRadio(radio);
                return this;
            }
        }
        throw new AssertionError("No selectable row matching '" + rowLabel
                + "' (" + radios.size() + " rows listed): " + seen);
    }

    // Same picker, but choose by 0-based index over the radio buttons on the page.
    public CommonActions selectRow(int index) {
        page.waitForSelector("input[type='radio']");
        clickRadio(page.locator("input[type='radio']").nth(index));
        return this;
    }

    // Text belonging to a radio: its own cell (scope "td") or its row (scope "tr").
    private String rowText(Locator radio, String scope) {
        Locator container = radio.locator("xpath=ancestor::" + scope + "[1]");
        return container.count() > 0 ? container.innerText().trim() : "";
    }

    // A plain radio can be checked directly. A custom-styled one is visually
    // hidden behind its <label> (the drawn circle IS the label), and Playwright
    // refuses to click a hidden input — so click the label, like a user does.
    private void clickRadio(Locator radio) {
        if (radio.isVisible()) {
            radio.check();
        } else {
            radio.locator("xpath=ancestor::td[1]").locator("label").first().click();
        }
    }

    public CommonActions verifyTextIsDisplayed(String expectedText) {
        Locator textLocator = page.getByText(expectedText, new Page.GetByTextOptions().setExact(false)).first();
        assertThat(textLocator).isVisible();
        return this;
    }

    // Amount fields: normalize then compare numerically
    public CommonActions verifyFieldHasAmount(String label, String expectedAmount) {
        Locator container = findValueElement(label);
        // Confirm screen: read-only span filled by JS. Auto-wait until non-empty.
        Locator valueSpan = container.locator("span.elem-span-infos").first();
        assertThat(valueSpan).containsText("TL"); // TL is appended at last, waits until fully rendered
        String actual = valueSpan.innerText();

        assertAmountEquals(expectedAmount, actual);

        return this;
    }

    // Text / date fields: EXACT match, NO normalization
    public CommonActions verifyFieldEquals(String label, String expectedText) {
        Locator container = findValueElement(label);
        Locator inputFields = container.locator("input");

        if (inputFields.count() > 0) {
            // Entry screen: input field. Auto-wait until non-empty, then read.
            assertThat(inputFields.first()).hasValue(expectedText.trim());
        } else {
            // Confirm screen: read-only value span. Auto-wait until non-empty.
            assertThat(container.locator("span.elem-span-infos").first())
                    .hasText(expectedText.trim()); // auto waits + exact text check
        }

        return this;
    }

    // ========================= HELPERS ===================================

    // Returns the input (if entry field) or the value span (if read-only display)
    // that belongs EXACTLY to this label. Auto-waits handled by the callers.
    private Locator findValueElement(String label) {
        Locator labelEl = page.getByText(label, new Page.GetByTextOptions().setExact(true)).first();
        Locator container = labelEl.locator("xpath=ancestor::div[contains(@class,'fieldcontainer')][1]");

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

    private void assertAmountEquals(String expected, String actual) {
        String e = normalizeAmount(expected);
        String a = normalizeAmount(actual);
        if (!e.equals(a)) {
            throw new AssertionError(
                    "Expected value did not match the screen. Expected: '" + expected
                            + "' (normalized: " + e + "), On screen: '" + actual + "' (normalized: " + a + ")");
        }
    }
}
