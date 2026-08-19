package com.ykb.uplift.uptest.playwright.common;

import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;

import java.nio.file.Paths;

public class Hooks {

    private final TestContext ctx;

    public Hooks(TestContext ctx) {
        this.ctx = ctx;
    }

    @Before
    public void setUp() {
        ctx.playwright = Playwright.create();
        ctx.browser = ctx.playwright.chromium()
                .launch(new BrowserType.LaunchOptions().setHeadless(false)
                        .setChannel("msedge").setSlowMo(50));
        ctx.context = ctx.browser.newContext();
        ctx.context.tracing().start(new Tracing.StartOptions()
                .setScreenshots(true)
                .setSnapshots(true)
                .setSources(true));
        ctx.page = ctx.context.newPage();
    }

    @After
    public void tearDown(Scenario scenario) {
        if (ctx.context != null) {
            // scenario name might have '/' and special chars
            String safeName = scenario.getName().replaceAll("[^a-zA-Z0-9-_]", "_");
            ctx.context.tracing().stop(new Tracing.StopOptions()
                    .setPath(Paths.get("target/traces/" + safeName + ".zip")));
            ctx.context.close();
        }
        if (ctx.browser != null) ctx.browser.close();
        if (ctx.playwright != null) ctx.playwright.close();
    }
}
