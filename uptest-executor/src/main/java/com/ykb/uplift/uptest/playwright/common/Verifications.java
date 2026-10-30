package com.ykb.uplift.uptest.playwright.common;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.LocatorAssertions;

import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static com.ykb.uplift.uptest.playwright.common.ValueFields.labelThenValue;
import static com.ykb.uplift.uptest.playwright.common.ValueFields.valueHolder;
import static com.ykb.uplift.uptest.playwright.common.ValueFields.withoutLabel;

/**
 * The four verify steps: text on screen, a field's text, its amount, and
 * whether the application is still masking it.
 *
 * Split out of {@link CommonActions} unchanged; CommonActions keeps the public
 * steps and hands them here.
 */
final class Verifications {

    private final Page page;
    private final ValueFields fields;
    private final FailureExplanations explain;

    Verifications(Page page, ValueFields fields, FailureExplanations explain) {
        this.page = page;
        this.fields = fields;
        this.explain = explain;
    }

    /**
     * How long a verification waits for what it is looking for.
     *
     * Playwright's default is 5 s, which is shorter than this bank's submit: a
     * scenario ending in {@code click "Onay"} then
     * {@code verify "Tamam" text is displayed} failed while the result screen
     * was still on its way, with nothing actually wrong. A verification is
     * often the first step to run after a submit, so all three wait this long.
     *
     * Still an auto-wait, not a sleep: it returns the moment the assertion
     * holds, and only a real failure spends the whole budget.
     */
    private static final double VERIFY_TIMEOUT_MS = 15000;

    void verifyTextIsDisplayed(String expectedText) {
        // VISIBLE copies only. `.first()` alone bets that the first match in
        // document order is the one meant, and on these screens it is often a
        // hidden duplicate — "Detay" appears eighteen times on Alıcı Listesi
        // and the first sits in a div the application keeps hidden.
        //
        // And in every FRAME, not only the page. A page-level getByText does
        // not look inside an iframe, and the agent's snapshots of Banko
        // İşlemleri carry frame references (f1e…): "Hızlı Tarih Seçimi" was on
        // screen for the agent and "element(s) not found" for this step. One
        // auto-waiting assertion cannot span frames, so the frames are polled
        // within the same timeout; the page is checked first on every pass, so
        // a screen without frames behaves exactly as before.
        long deadline = System.currentTimeMillis() + (long) VERIFY_TIMEOUT_MS;
        while (true) {
            if (visibleCopies(page.mainFrame(), expectedText) > 0) {
                return;
            }
            for (Frame frame : page.frames()) {
                if (frame != page.mainFrame() && visibleCopies(frame, expectedText) > 0) {
                    System.out.println("[uptest] verify text: \"" + expectedText
                            + "\" bir iframe icinde bulundu (" + frame.url() + ")");
                    return;
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new AssertionError(whereIsText(expectedText));
            }
            page.waitForTimeout(250);
        }
    }

    /** Visible copies of the text in one frame; 0 while a frame is still loading. */
    private static int visibleCopies(Frame frame, String text) {
        try {
            return frame.getByText(text, new Frame.GetByTextOptions().setExact(false))
                    .filter(new Locator.FilterOptions().setVisible(true))
                    .count();
        } catch (RuntimeException detachedOrLoading) {
            return 0;
        }
    }

    /**
     * Where the text is, when the step could not see it — counted on the page
     * AND in every frame.
     *
     * "Nowhere", "here but every copy hidden" and "only inside an iframe" need
     * three different fixes, and Playwright's own message says "element(s) not
     * found" for the first and the third alike: a page-level getByText does
     * not look inside frames. Counting is cheap and runs only on failure.
     */
    private String whereIsText(String text) {
        StringBuilder out = new StringBuilder("\"" + text + "\" görünür değil.");
        try {
            Locator onPage = page.getByText(text, new Page.GetByTextOptions().setExact(false));
            out.append(" Sayfada ").append(onPage.count()).append(" eşleşme, ")
                    .append(onPage.filter(new Locator.FilterOptions().setVisible(true)).count())
                    .append(" görünür.");
            int index = 0;
            for (Frame frame : page.frames()) {
                if (frame == page.mainFrame()) {
                    continue;
                }
                index++;
                Locator inFrame = frame.getByText(text, new Frame.GetByTextOptions().setExact(false));
                int count = inFrame.count();
                if (count > 0) {
                    out.append(" iframe #").append(index).append(" (").append(frame.url()).append("): ")
                            .append(count).append(" eşleşme, ")
                            .append(inFrame.filter(new Locator.FilterOptions().setVisible(true)).count())
                            .append(" görünür.");
                }
            }
            out.append(" Sayfada ").append(index).append(" iframe var.");
        } catch (RuntimeException unreadable) {
            out.append(" (sayım yapılamadı: ").append(unreadable.getMessage()).append(')');
        }
        System.out.println("[uptest] verify text: " + out);
        return out.toString();
    }

    // Amount fields: normalize then compare numerically
    void verifyFieldHasAmount(String label, String expectedAmount) {
        Locator container = readableValue(label);
        // Confirm screen: read-only span filled by JS. Auto-wait until non-empty.
        Locator valueSpan = valueHolder(container);
        // TL is appended last, so this waits until the value is fully rendered.
        try {
            assertThat(valueSpan).containsText("TL",
                    new LocatorAssertions.ContainsTextOptions().setTimeout(VERIFY_TIMEOUT_MS));
        } catch (AssertionError miss) {
            throw explainVerify(label, container, miss);
        }
        // Same shared-container case as verifyFieldEquals: the text can arrive
        // as "Toplam Tutar200,00 TL". Harmless today because the label has no
        // digits, but a label that did would corrupt the comparison silently.
        String actual = withoutLabel(label, valueSpan.innerText());

        assertAmountEquals(expectedAmount, actual);

        return;
    }

    // Text / date fields: EXACT match, NO normalization
    void verifyFieldEquals(String label, String expectedText) {
        Locator container = readableValue(label);
        Locator inputFields = container.locator("input");
        // A Select2 dropdown's container holds its SEARCH box -- v3's
        // "select2-input", always empty -- so the input branch below read ""
        // on a field showing "Seçiniz". The value a person reads is the chosen
        // text: ".select2-chosen" on v3, ".select2-selection__rendered" on v4.
        Locator select2Shown = container.locator(".select2-chosen, .select2-selection__rendered");

        try {
            if (select2Shown.count() > 0) {
                assertThat(select2Shown.first()).hasText(expectedText.trim(),
                        new LocatorAssertions.HasTextOptions().setTimeout(VERIFY_TIMEOUT_MS));
            } else if (inputFields.count() > 0) {
                // Entry screen: input field. Auto-wait until non-empty, then read.
                assertThat(inputFields.first()).hasValue(expectedText.trim(),
                        new LocatorAssertions.HasValueOptions().setTimeout(VERIFY_TIMEOUT_MS));
            } else {
                // Confirm screen: read-only value. Three shapes seen on this app —
                // a value span, a plain table cell, and (DBS Fatura Oluştur "Onay")
                // one container holding the label AND the value, which reads back
                // as "Bayi Kodu93002". Rather than guessing which element is the
                // value, accept the text with the label optionally in front of it.
                assertThat(valueHolder(container))
                        .hasText(labelThenValue(label, expectedText.trim()),
                                new LocatorAssertions.HasTextOptions().setTimeout(VERIFY_TIMEOUT_MS));
            }
        } catch (AssertionError miss) {
            throw explainVerify(label, container, miss);
        }

        return;
    }

    /** Any non-whitespace: "the value has arrived", whatever it turns out to be. */
    private static final Pattern ANY_TEXT = Pattern.compile("\\S");

    /**
     * What a masked value looks like on these screens: a run of stars inside
     * the value, as in "20****694".
     *
     * Two or more, so that a stray asterisk — a required-field marker, a
     * footnote — cannot make an unmasked account number pass. Written as a
     * \\u escape for the bullet because this file is hand-copied between
     * machines and a copy through the wrong encoding would silently change
     * what the pattern matches.
     */
    private static final Pattern MASKED = Pattern.compile("[*\\u2022]{2,}");

    /**
     * A field whose value the application deliberately HIDES — "20****694".
     *
     * The existing verifies all compare against a value the scenario supplies,
     * and that is the wrong question here twice over. On a read-only screen the
     * scenario never typed the value, so it cannot know it; and the account
     * number differs per record, so writing it down would tie the test to one
     * row. What the analyst actually wants to assert is not a value but a
     * PROPERTY: that the bank is still hiding the middle of the number.
     *
     * Without this step the model had no way to say that. Asked to "verify the
     * recipient account is masked" it reached for the nearest value-shaped verb
     * and invented {@code has amount "*"}; the validator rightly refused the
     * invented value, and the repair turn then deleted the assertion and called
     * the scenario valid — a test that walks to the screen and checks nothing.
     *
     * Takes no expected value on purpose. That is what makes it earnable on a
     * screen where nothing is ever filled in.
     */
    void verifyFieldIsMasked(String label) {
        Locator container = readableValue(label);
        Locator holder = valueHolder(container);

        try {
            // Auto-wait rather than read-and-hope: a detail screen paints its
            // table after the request settles, and reading too early finds an
            // empty cell — which is indistinguishable from an unmasked blank.
            assertThat(holder).containsText(ANY_TEXT,
                    new LocatorAssertions.ContainsTextOptions().setTimeout(VERIFY_TIMEOUT_MS));
        } catch (AssertionError nothingArrived) {
            throw explainVerify(label, container, nothingArrived);
        }

        String shown = withoutLabel(label, holder.innerText()).trim();
        if (MASKED.matcher(shown).find()) {
            return;
        }
        throw notMasked(label, shown);
    }

    /**
     * Two failures that read alike and need opposite fixes.
     *
     * "The value is not masked" is the application's answer and a real finding.
     * "We read the label back" is OUR locator giving up on a screen whose label
     * and value share one span — the open issue on the DBS Firma Fatura summary,
     * and the shape this Banko detail screen is in (the agent reported its rows
     * as "Alıcı Hesabı 20****694", one run of text). Reporting the second as the
     * first would send someone to the bank to ask why masking had been turned
     * off.
     */
    private AssertionError notMasked(String label, String shown) {
        if (shown.isEmpty() || shown.equalsIgnoreCase(label.trim())) {
            return new AssertionError("'" + label + "' degeri okunamadi: ekrandan '" + shown
                    + "' geldi — yani etiketin kendisi, degeri degil. Bu ekranda etiket ve deger"
                    + " ayni span'i paylasiyor ve findValueElement ikisini ayiramiyor. Alan"
                    + " maskeli OLSA DA bu adim bunu goremez; once deger cozumlemesi duzeltilmeli.");
        }
        return new AssertionError("'" + label + "' maskeli degil: ekranda '" + shown
                + "' yaziyor. Maskeli bir deger en az iki ardisik yildiz tasir (ornek: 20****694).");
    }

    private Locator readableValue(String label) {
        return fields.readableValue(label);
    }

    private void assertAmountEquals(String expected, String actual) {
        fields.assertAmountEquals(expected, actual);
    }

    private AssertionError explainVerify(String label, Locator container, AssertionError miss) {
        return explain.explainVerify(label, container, miss);
    }
}
