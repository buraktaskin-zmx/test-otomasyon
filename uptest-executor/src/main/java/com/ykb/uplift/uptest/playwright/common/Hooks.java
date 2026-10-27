package com.ykb.uplift.uptest.playwright.common;

import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Hooks {

    /**
     * Browser settings are read from the environment so the same jar runs on
     * the VDI and in a container. Defaults reproduce the VDI setup (headed
     * system Edge, slowMo) so existing local/IDE runs are unchanged; the
     * container image overrides them:
     *
     *   UPTEST_HEADLESS=true
     *   UPTEST_BROWSER_CHANNEL=          (empty -> bundled Chromium)
     *   UPTEST_SLOWMO=0
     */
    private static final String ENV_HEADLESS = "UPTEST_HEADLESS";
    private static final String ENV_CHANNEL = "UPTEST_BROWSER_CHANNEL";
    private static final String ENV_SLOWMO = "UPTEST_SLOWMO";

    private final TestContext ctx;

    public Hooks(TestContext ctx) {
        this.ctx = ctx;
    }

    @Before
    public void setUp(Scenario scenario) {
        ctx.expectedTexts = expectedTexts(scenario);
        ctx.playwright = Playwright.create();
        ctx.browser = ctx.playwright.chromium().launch(launchOptions());
        ctx.context = ctx.browser.newContext();
        ctx.context.tracing().start(new Tracing.StartOptions()
                .setScreenshots(true)
                .setSnapshots(true)
                .setSources(true));
        ctx.page = ctx.context.newPage();
    }

    private BrowserType.LaunchOptions launchOptions() {
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(Boolean.parseBoolean(env(ENV_HEADLESS, "false")))
                .setSlowMo(Double.parseDouble(env(ENV_SLOWMO, "50")));

        // Channel deliberately does NOT use env(): there, blank falls back to
        // the default, which made UPTEST_BROWSER_CHANNEL="" unreachable and sent
        // containers looking for an Edge they do not have. Here UNSET means
        // "VDI default, system Edge" and SET-BUT-EMPTY means "no channel, use
        // Playwright's bundled Chromium".
        String channel = System.getenv(ENV_CHANNEL);
        if (channel == null) {
            channel = "msedge";
        }
        if (!channel.isBlank()) {
            options.setChannel(channel);
        }
        return options;
    }

    private static final Pattern VERIFY_TEXT = Pattern.compile("verify \"([^\"]+)\" text is displayed");

    /**
     * The texts the scenario's file asserts as displayed. Read from the file
     * because a step cannot see the steps after it, and a click needs to know
     * whether the refusal it just caused is the one the scenario is about to
     * check. Never fails the scenario: no file, no expectations.
     */
    private static Set<String> expectedTexts(Scenario scenario) {
        Set<String> texts = new HashSet<>();
        try {
            URI uri = scenario.getUri();
            if (uri == null || !"file".equalsIgnoreCase(uri.getScheme())) {
                return texts;
            }
            Matcher match = VERIFY_TEXT.matcher(Files.readString(Paths.get(uri), StandardCharsets.UTF_8));
            while (match.find()) {
                texts.add(match.group(1));
            }
        } catch (Exception unreadable) {
            System.out.println("[uptest] senaryo dosyasi okunamadi: " + unreadable.getMessage());
        }
        return texts;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    /**
     * Every close runs even if an earlier step throws.
     *
     * This matters more than it looks: {@code Playwright.create()} starts a
     * node driver process per scenario. If a failing {@code tracing().stop()}
     * skipped the closes — as it did before — that driver and its browser
     * leaked for the lifetime of the JVM, which in a long-lived executor means
     * they accumulate run after run.
     */
    @After
    public void tearDown(Scenario scenario) {
        try {
            stopTracing(scenario);
        } finally {
            close(ctx.context, "context");
            close(ctx.browser, "browser");
            close(ctx.playwright, "playwright");
            ctx.page = null;
            ctx.context = null;
            ctx.browser = null;
            ctx.playwright = null;
        }
    }

    private void stopTracing(Scenario scenario) {
        if (ctx.context == null) {
            return;
        }
        try {
            ctx.context.tracing().stop(new Tracing.StopOptions().setPath(tracePath(scenario)));
        } catch (RuntimeException e) {
            // A trace is diagnostics, not the test result: never let it decide
            // whether the browser gets closed.
            System.err.println("[uptest] tracing stop failed: " + e);
        }
    }

    /**
     * Traces belong to the run directory the server created, so results need no
     * extra transfer hop. Falls back to target/traces when Cucumber was started
     * outside the server (mvn test), which is where they used to always go.
     */
    private Path tracePath(Scenario scenario) {
        // Scenario names may contain '/' and other path-hostile characters.
        String safeName = scenario.getName().replaceAll("[^a-zA-Z0-9-_]", "_");
        Path runDir = RunContext.get();
        Path tracesDir = (runDir != null) ? runDir.resolve("traces") : Paths.get("target/traces");
        return tracesDir.resolve(safeName + ".zip");
    }

    private static void close(AutoCloseable resource, String what) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception e) {
            System.err.println("[uptest] closing " + what + " failed: " + e);
        }
    }
}
