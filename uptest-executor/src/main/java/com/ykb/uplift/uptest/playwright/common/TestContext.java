package com.ykb.uplift.uptest.playwright.common;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/**
 * Scenario state shared between every step class via PicoContainer DI.
 * Cucumber creates ONE instance per scenario and injects the same instance
 * into Hooks and all step classes that declare it as a constructor argument.
 *
 * The hook (@Before) fills these fields; step classes read {@link #page}.
 * NOTE: constructors run BEFORE @Before, so {@link #page} is null at
 * construction time — build page objects lazily (on first use), never in a
 * step class constructor.
 */
public class TestContext {
    public Playwright playwright;
    public Browser browser;
    public BrowserContext context;
    public Page page;
}
