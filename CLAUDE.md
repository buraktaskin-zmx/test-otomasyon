# cucumber-uplift-test

AI test automation for Yapı Kredi internet banking. An LLM generates Gherkin
scenarios using a fixed, human-written step vocabulary; it produces only
`.feature` files, never step implementations.

**This file covers the TEST side** — step vocabulary, page objects, Select2
handling, NGC/NGI rules. Two companion documents own the rest; do not duplicate
them here:

- **Server side** (orchestrator + executor API, Docker, deployment):
  [`uptest-executor/src/main/java/server/CLAUDE.md`](uptest-executor/src/main/java/server/CLAUDE.md)
- **Goals, deliverables, roadmap:** [`PROJECT.md`](PROJECT.md)

## Repository shape

Two Maven modules under a `packaging=pom` parent. The split is deliberate: the
orchestrator never opens a browser, so it must not carry the 236 MB Playwright
driver bundle. They talk over HTTP only — there is no Maven dependency between
them.

```
uptest-executor/      runs tests: Cucumber + Playwright + Spring Web
uptest-orchestrator/  UI, validation, dispatch. NEVER runs a test.
```

## Architecture (layers, top to bottom)

- `.feature` files (Gherkin) — readable scenarios. LLM/analyst produces these.
- Step definitions (CommonSteps / NGCSteps / NGISteps) — map Gherkin lines to
  page object methods. FIXED, human-authored.
- Page objects (CommonActions / NGC / NGI) — Playwright logic. Complexity
  hidden here.
- Real page — rendered DOM of the banking app.

Playwright is the execution engine: runs step methods AND interacts with the page.

### Two parallel applications

- **NGC** — corporate internet banking. The original target. Login uses
  CIF + user id. Flows: Banko payment, DBS Firm Invoice Inquiry.
- **NGI** — individual (retail) internet banking. Added later. **Different
  app**: login is customer code + a password field, not CIF + userid. NGC
  vocabulary does NOT transfer one-to-one — NGI has its own page object and
  steps (NGI / NGISteps).

Keep NGC and NGI separate. A step that works on NGC may need a different
implementation on NGI because the HTML structure and login flow differ.

## Key paths

All test-side code lives in `uptest-executor/src/main/java/` under the package
`com.ykb.uplift.uptest.playwright` (prefix `PW` below):

- NGC steps: `PW/ngc/steps/NGCSteps.java` — one step (`login`), nothing else
- NGC page object: `PW/ngc/steps/util/NGC.java`
- NGI steps: `PW/ngi/steps/NGISteps.java` — one step (`login to ngi`)
- NGI page object: `PW/ngi/steps/util/NGI.java`
- Common: `PW/common/` — CommonSteps (14 shared steps), CommonActions (all the
  Playwright logic), Hooks, TestContext, RunContext
- Components: `PW/components/` — grid/Grid+GridElement, selectbox/account,
  end/EndPage

Elsewhere:

- Scenarios (working copies analysts and the LLM look at): `scenarios/*.feature`
- Screen catalogs for LLM prompting: `catalogs/<app>/<flow>.yaml`; schema in
  `catalogs/SCHEMA.md`
- Human-facing vocabulary contract: `VOCABULARY.md`
- `mvn test` feature classpath: `uptest-executor/src/test/resources/` +
  `uptest-executor/src/test/java/.../RunCucumberTest.java`
- `DO_NOT_COMMIT/` — if present, page object and steps variants with hardcoded
  test data (CIF, userid, passwords). **Never read, expose, or commit.** Both
  `.gitignore` and `.dockerignore` guard it; keep it that way.

## Execution model (IMPORTANT — read this)

**Claude does NOT run the tests.** This local copy is for writing and editing
code only — nothing here reaches the real banking environment. Real execution
happens in one of two places, both operated by the user:

- the corporate VDI (IDE / `mvn test`), or
- the Docker stack on the Linux server (orchestrator UI → executor).

The loop:

1. Claude writes or edits the code (step definitions, page object, features).
2. The user runs it on the VDI or the server.
3. The user brings results back as a screenshot or pasted text (console
   output, stack traces, the Cucumber run panel, the UI's step timeline).
4. Claude reads those results and continues — diagnosing failures, fixing,
   or moving on.

So: never assume a test was run, never claim a fix works until the user reports
the result, and don't try to execute Maven/test commands locally. Produce the
code; the user is the bridge to execution.

## Environment notes

**Browser settings come from three env vars** so one jar serves both hosts.
Defaults reproduce the VDI, so existing IDE runs are unchanged; the container
overrides all three (see `Hooks.launchOptions`):

| var | VDI (default) | container |
|---|---|---|
| `UPTEST_HEADLESS` | `false` | `true` |
| `UPTEST_BROWSER_CHANNEL` | `msedge` (system Edge) | *(empty — bundled Chromium)* |
| `UPTEST_SLOWMO` | `50` | `0` |

- The VDI also needs `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` on every run config:
  corporate SSL inspection blocks the Chromium download. The container's
  Playwright base image ships browsers, so it does not.
- `UPTEST_BROWSER_CHANNEL` deliberately does NOT go through the
  blank-means-default helper: *unset* = system Edge, *set-but-empty* = no
  channel. Routing it back through that helper made `""` unreachable and sent
  containers hunting for an Edge they do not have.
- Traces go to the per-run directory the server created
  (`{runDir}/traces/<scenario>.zip`), falling back to `target/traces/` when
  Cucumber was started outside the server (`mvn test`).

## Current vocabulary (16 steps)

**The machine-readable source of truth is generated**, not this list:
`VocabularyExtractor` reads the `@Given/@When/@Then` annotations at build time
into `vocabulary.json`, served at `GET /cucumber/vocabulary` and re-served by
the orchestrator at `GET /api/v1/vocabulary`. If this list and the JSON
disagree, the JSON is right. `VOCABULARY.md` is the analyst/LLM-facing view of
the same data.

Application-specific (NGCSteps / NGISteps):

- `login with cif {string} and userid {string}` (NGC)
- `login to ngi with cif {string}` (NGI — customer code; password/OTP hardcoded
  in the NGI page object, not exposed as step args)

Shared (CommonSteps, work on both apps):

- `go to {string}` (absolute URL; prefer `navigate to`)
- `navigate to {string} {string}` (main menu, then sub-menu)
- `click {string}` (matches button OR link)
- `enter {string} {string}` (text/date input, by label)
- `enter amount {string} {string}` (masked amount field, kuruş-aware: the whole
  and decimal parts are SEPARATE inputs, `-integer` / `-decimal`)
- `select {string} from {string}` (a plain `<select>` inside the label's
  fieldcontainer is driven with `selectOption(...).setForce(true)`; a
  Select2-wrapped one is opened and its option clicked instead — see the
  lesson below)
- `select account {string} from {string}` (Select2 **v4** account combobox, e.g.
  "Alıcı Hesabı": opens via the field label, clicks the option whose digits
  contain the wanted account number — spacing ignored. NOT the native `<select>`,
  which is aria-hidden, so the generic `select` step can't drive it.)
- `select account at index {int} from {string}` (same picker, 0-based over the
  listed `li.select2-results__option` items)
- `select row {string}` (result grid radio button: matches the radio's own `td`
  first, then the whole `tr`, so a value in any column works)
- `select row at index {int}` (0-based over the radios on the page)
- `verify the {string} field equals {string}` (text/date, EXACT match, NO normalize)
- `verify the {string} has amount {string}` (amount, normalized)
- `verify {string} text is displayed`
- `wait {string} seconds` (avoid — prefer auto-wait)

## Hard-won lessons (DO NOT regress these)

- **verifyFieldEquals must NOT normalize text.** Normalizing collapsed "Test"
  → "" and made all text verifies falsely pass ("" == ""). This was a serious
  2-day bug — every test silently passed. Text/date = exact match only.
- **Amount fields: read the value SPAN, not hidden inputs.** Confirm-screen
  amount containers have a hidden input holding the kuruş-less value (1.250);
  reading it gives wrong results. Wait for "TL" (containsText) then read the span.
- **Use auto-wait assertions** (hasText/hasValue/containsText), not
  `wait "N" seconds`. Both branches (input and span) need auto-wait.
- **Label matching:** use `getByText(label, setExact(true))` so "Ad Soyad /
  Unvan" doesn't also match "Alıcı Ad Soyad / Unvan".
- **findValueElement:** getByText exact → `ancestor::div[fieldcontainer]`, then
  a modularFormTable fallback (label and value in SEPARATE `<td>`
  fieldcontainers → look at the next `<td>`; Banko keeps using the single
  container path). Confirmed on BOTH NGC and NGI, so it lives in
  `CommonActions` along with `select`, `verifyFieldEquals`,
  `verifyFieldHasAmount`, which depend on it. Still breaks on screens where
  label and value share a span class — see Open issues.
- **Account pickers ≠ one widget.** Two distinct Select2 variants exist. The
  recipient "Alıcı Hesabı" field is **Select2 v4** (`select2-selection__rendered`,
  `role=combobox`, options = `li.select2-results__option` at body level, 3 spans:
  text/branch/accountnumber). The `components/selectbox/account`
  `AccountSelectBox` is **Select2 v3** (`.select2-drop-active`,
  `.select2-results-dept-0`) and its `Account` parser needs **10 spans with
  balances** — it fits the balance-rich *source*-account dropdown, NOT the v4
  recipient field. Don't route v4 fields through it. v4 selection lives in
  `CommonActions.selectAccount` (open via label, match option by digits, or by
  index).
- **A Select2-wrapped dropdown cannot be set through its native `<select>`.**
  `selectOption(...).setForce(true)` writes the value to the hidden select and
  the step goes GREEN, but the field still reads "Seçiniz" and nothing the app
  loads in response ever happens — on DBS Fatura Oluştur, "Bayi Kodu" stayed
  unset, so "Bayi Ünvanı" was never auto-filled and the dependent "Döviz Cinsi"
  never got any options at all. A silent pass is worse than a failure. So
  `CommonActions.select` checks for `.select2-selection` and, when present,
  drives the widget the way a user does: open it, click the matching option.
  Same shape as `selectAccount`, which matches by digits instead of text.
- **`fill()` does not work on a masked input.** It writes the value in a single
  `input` event; a jQuery-masked field listens for key events and rebuilds the
  value character by character, so it ignores that event and paints the empty
  mask (`__/__/____`) back — while `fill()` reports success and the step goes
  GREEN with the field blank. Hit on DBS Fatura Oluştur: "Başlangıç Vadesi" and
  "Fatura Tarihi". Two layers now: a field carrying jQuery UI's `hasDatepicker`
  class is driven **through its calendar** (year select → month select, 0-based
  → day cell), exactly as a user does and as `select` does for Select2; anything
  else falls back to `fill()`, then to `pressSequentially` (real key events) if
  the value did not take, and fails loudly if it still did not. Same family as
  the Select2 lesson above: a silent pass is worse than a failure.
- **Custom-styled radios are invisible to Playwright.** The drawn circle IS the
  `<label>`; the input is visually hidden and Playwright refuses to click a
  hidden element. `clickRadio` checks visibility and clicks the label instead —
  the way a user does.
- **Hooks must close everything even when tracing fails.** `Playwright.create()`
  starts a node driver process per scenario; a throwing `tracing().stop()` that
  skipped the closes leaked that driver and its browser for the lifetime of the
  JVM — which in a long-lived executor means they accumulate run after run.

## Open issues

- Verifying text inside an invoice table.
- `mvn test` finds no features: they sit in a directory literally named
  `com.ykb.uplift.uptest` (dots), while `RunCucumberTest` selects the classpath
  resource `com/ykb/uplift/uptest` (slashes), which holds only the suite class.
  Harmless for the server path (the executor writes each scenario into its own
  run directory and passes the file to Cucumber directly), and harmless when a
  feature is passed explicitly with `-Dcucumber.features=<path>` (a filesystem
  path, which bypasses the classpath selector) — but plain `mvn test` and the
  IDE run gutter stay broken until the directory is renamed.

## Conventions

- Communication: Turkish or English. Direct and skeptical, action-oriented,
  copy-pasteable commands.
- Keep scenarios clean/readable (analysts edit them). Complexity → page object.
- Generic over hardcoded: derive locators from labels/attributes, not fixed ids.
- Diagnose by measuring, not guessing (e.g. print container/element counts
  before assuming why a locator fails). Prove the bug, fix it, prove it's fixed.

## Sharing between NGC and NGI (common layer)

Goal: share what is genuinely application-independent; keep
application-specific logic separate.

- **Truly shared (in `common`, done):** the 14 generic steps listed above and
  their logic in `CommonActions`, including `findValueElement`,
  `normalizeAmount`, `assertAmountEquals` — confirmed to work on both apps.
  `navigateTo` is bound to the `#headerMenuUsername` / `.main-nav` header
  markup; it's shared on the assumption NGC and NGI render the same menu.
  Plus the single `@Before`/`@After` hooks in `Hooks` (page lifecycle is the
  same for both apps).
- **Application-specific (stays in NGC or NGI):** only `login` (NGC = CIF+userid;
  NGI = customer code + password).
- **Rule:** do NOT move something to `common` just because the name looks
  generic. Confirm it works identically on BOTH apps first. A step that looks
  shared (`login`) often isn't. If an action is conceptually shared but
  structurally different per app, put the shared contract (method signature) in
  common and let each app provide its own implementation.
- **Cucumber glue:** all step classes share one pool — a step defined in
  CommonSteps is usable by both NGC and NGI features automatically. Define each
  step pattern in exactly ONE class; a duplicate pattern across classes causes
  a "duplicate step definition" error. The glue is the PACKAGE name
  (`com.ykb.uplift.uptest.playwright`), not a folder path; `CucumberRunner`
  and `RunCucumberTest` must stay in sync on it.
- **Page sharing:** use Cucumber dependency injection (PicoContainer). A shared
  `TestContext` holding `page` is injected into every step class for a
  scenario; the hook fills `ctx.page`, the other step classes read it. Do NOT
  use a `static` page (breaks parallel scenarios). Note injection order: step
  class constructors run BEFORE `@Before`, so `ctx.page` may be null in a
  constructor — build the page object lazily (on first use), not in the constructor.
