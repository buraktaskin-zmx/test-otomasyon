package com.ykb.uplift.uptest.playwright.discovery;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import static com.ykb.uplift.uptest.playwright.discovery.ControlTraits.*;

/**
 * The raw counts behind a scan, written into the draft only when
 * UPTEST_DISCOVERY_DEBUG=true.
 *
 * Split out of {@link CatalogDiscovery} unchanged.
 */
final class ScanDiagnostics {

    private final Page page;
    private final StringBuilder debugLog;

    ScanDiagnostics(Page page, StringBuilder debugLog) {
        this.page = page;
        this.debugLog = debugLog;
    }

    /** Enough diagnostic rows to explain a screen, few enough to stay readable. */
    private static final int DEBUG_LIMIT = 40;
    private static final int DEBUG_TEXT = 70;
    private static final int DEBUG_OPTIONS = 6;

    /** Diagnostics are for calibrating the scan, not for the analyst's draft. */
    private static final boolean DEBUG =
            "true".equalsIgnoreCase(System.getenv("UPTEST_DISCOVERY_DEBUG"));

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
    void recordDiagnostics(int screensScanned) {
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
}
