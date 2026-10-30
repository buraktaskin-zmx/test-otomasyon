package com.ykb.uplift.uptest.playwright.common;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.options.AriaRole;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Attaching a file, through a real file input or through the chooser the form's
 * own button opens.
 *
 * Split out of {@link CommonActions} unchanged; CommonActions keeps the public
 * step and hands it here.
 */
final class Uploads {

    private final Page page;
    private final ErrorDialogs dialogs;

    Uploads(Page page, ErrorDialogs dialogs) {
        this.page = page;
        this.dialogs = dialogs;
    }

    /** Where a bare file name is looked up. */
    private static final String ENV_TESTDATA = "UPTEST_TESTDATA_DIR";

    /** How long the file name is given to appear, on the chooser path. */
    private static final double UPLOAD_CONFIRM_MS = 10000;

    /**
     * Attaches a file to an upload control.
     *
     * The gap this closes: DBS Fatura Oluştur forks into "Bilgi Girişi" and
     * "Dosya Yükleme", {@code choose} can take the second branch, and then
     * nothing could drive the screen it lands on. The whole branch was
     * untestable.
     *
     * TWO PATHS, because these forms use both shapes and only one of them is
     * the textbook case:
     *
     *   - a real {@code <input type="file">} reachable from the label. Driven
     *     with setInputFiles, which deliberately does NOT need the input to be
     *     visible — and that matters here, because an application that draws
     *     its own "Gözat" button hides the real input behind it. A step that
     *     insisted on visibility would fail on exactly the forms this exists
     *     for.
     *   - no such input: the visible control opens the OS file dialog, which
     *     Playwright can only intercept. waitForFileChooser arms the
     *     interception BEFORE the click, because the event fires during it.
     *
     * Worth knowing where this leaves recording-based approaches: codegen
     * writes setInputFiles for the first shape and cannot record the second at
     * all — the file-chooser handling is written by hand there too. The branch
     * that is hardest for a human to script is also the one a recorder cannot
     * capture, which is the argument for a step rather than a cached trace.
     */
    void upload(String label, String fileName) {
        failFastOnErrorDialog("upload");
        Path file = testDataFile(label, fileName);

        Locator input = fileInputFor(label);
        if (input != null) {
            input.setInputFiles(file);
        } else {
            // Armed first, then the click: the chooser opens DURING the click
            // and an interception set up afterwards has already missed it.
            page.waitForFileChooser(() -> clickUploadControl(label)).setFiles(file);
            input = fileInputFor(label);
        }

        assertFileTook(label, input, file);
        failFastOnErrorDialog("upload (sonrasi)");
        return;
    }

    /**
     * The file to attach, as an absolute path that exists.
     *
     * A bare name is resolved under {@code UPTEST_TESTDATA_DIR} (default
     * {@code ./testdata}) so a scenario an analyst wrote stays free of machine
     * paths — the same reason a step takes a label instead of a selector. An
     * absolute path is honoured as given, for the case where the fixture lives
     * somewhere else.
     *
     * Checked HERE rather than left to Playwright. A missing file reaches the
     * browser as a failure thirty seconds later with the file name buried in a
     * driver message; failing now names the path we actually looked at, which
     * is the only thing that tells a missing fixture apart from a wrong
     * directory.
     */
    private static Path testDataFile(String label, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new AssertionError("'" + label + "' icin dosya adi verilmedi.");
        }
        String configured = System.getenv(ENV_TESTDATA);
        Path root = Paths.get(configured == null || configured.isBlank() ? "./testdata" : configured);

        Path given = Paths.get(fileName.trim());
        Path file = (given.isAbsolute() ? given : root.resolve(given)).toAbsolutePath().normalize();

        if (!Files.isRegularFile(file)) {
            throw new AssertionError("'" + label + "' icin yuklenecek dosya yok: " + file
                    + " — senaryoda yalnizca dosya ADI yazilir ve " + ENV_TESTDATA
                    + " (varsayilan ./testdata) altinda aranir. Dosyayi oraya koyun ya da "
                    + "senaryoda tam yol yazin.");
        }
        return file;
    }

    /**
     * The file input this label owns, or null when the form hides it behind a
     * control of its own.
     *
     * Same ladder as findValueElement and for the same reason: getByLabel only
     * works when the markup associates them, and on these screens it often
     * does not.
     */
    private Locator fileInputFor(String label) {
        Locator byLabel = page.getByLabel(label).locator("xpath=self::input[@type='file']");
        if (byLabel.count() > 0) {
            return byLabel.first();
        }
        Locator inContainer = labelContainer(label).locator("input[type='file']");
        if (inContainer.count() > 0) {
            return inContainer.first();
        }
        // Last resort, and only when it cannot be ambiguous: a form with
        // exactly one file input on the whole page has no other candidate.
        Locator onPage = page.locator("input[type='file']");
        return onPage.count() == 1 ? onPage.first() : null;
    }

    /** The fieldcontainer a label sits in, for scoping a search to its field. */
    private Locator labelContainer(String label) {
        return page.getByText(label, new Page.GetByTextOptions().setExact(true)).first()
                .locator("xpath=ancestor::div[contains(@class,'fieldcontainer')][1]");
    }

    /** Presses whatever opens the file dialog for this label. */
    private void clickUploadControl(String label) {
        Locator container = labelContainer(label);
        Locator button = container.getByRole(AriaRole.BUTTON).or(container.getByRole(AriaRole.LINK));
        if (button.count() > 0) {
            button.first().click();
            return;
        }
        page.getByText(label, new Page.GetByTextOptions().setExact(true)).first().click();
    }

    /**
     * Reads the attachment back, because an upload that silently attached
     * nothing is the worst shape this codebase knows: the form submits, the
     * application rejects an empty file, and the step that caused it was green.
     *
     * A file input's value is {@code C:\fakepath\<name>} — the browser hides
     * the directory, never the name, so the name is what gets compared. When
     * there is no input to read (the control is entirely custom), the file name
     * on screen is the only evidence available, and if neither exists the step
     * says it could not confirm rather than passing.
     */
    private void assertFileTook(String label, Locator input, Path file) {
        String wanted = file.getFileName().toString();

        if (input != null) {
            try {
                String value = input.inputValue();
                if (value != null && value.endsWith(wanted)) {
                    return;
                }
                throw new AssertionError("'" + label + "' alanina '" + wanted
                        + "' eklenmedi: kutu '" + value + "' diyor.");
            } catch (com.microsoft.playwright.PlaywrightException unreadable) {
                // Fall through to the on-screen check below.
            }
        }

        try {
            assertThat(page.getByText(wanted).first())
                    .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(UPLOAD_CONFIRM_MS));
        } catch (AssertionError notShown) {
            throw new AssertionError("'" + label + "' icin '" + wanted + "' secildi ama dogrulanamadi:"
                    + " okunabilir bir dosya girdisi yok ve dosya adi ekranda da gorunmuyor."
                    + " Dosyanin gercekten eklendigi KANITLANAMADI.", notShown);
        }
    }

    private void failFastOnErrorDialog(String action) {
        dialogs.failFastOnErrorDialog(action);
    }
}
