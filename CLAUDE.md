# cucumber-uplift-test
**Server side (orchestrator + executor API): see cucumber-uplift-test/src/main/java/server/CLAUDE.md.**
This repo is the step vocabulary and page objects only.
AI test automation for Yapı Kredi corporate internet banking (NGC).
An LLM generates Gherkin scenarios using a fixed, human-written step
vocabulary; it produces only `.feature` files, never step implementations.

**Project goals, deliverables, and roadmap: see PROJECT.md.** This file is
operational only — how to work in the codebase.

## Architecture (layers, top to bottom)

- `.feature` files (Gherkin) — readable scenarios. LLM generates these.
- Step definitions (NGCSteps / NGISteps) — map Gherkin lines to page object
  methods. FIXED, human-authored.
- Page objects (NGC / NGISteps) — Playwright logic. Complexity hidden here.
- Real page — rendered DOM of the banking app.

Playwright is the execution engine: runs step methods AND interacts with the page.

### Two parallel applications

- **NGC** — corporate internet banking. The original target. Login uses
  CIF + user id. Flows: Banko payment, DBS Firm Invoice Inquiry.
- **NGI** — individual (retail) internet banking. Added later. **Different
  app**: login is customer code + a password field, not CIF + userid. NGC vocabulary does NOT transfer
  one-to-one — NGI has its own page object and steps (NGI / NGISteps).

Keep NGC and NGI separate. A step that works on NGC may need a different
implementation on NGI because the HTML structure and login flow differ.

## Key paths

- NGC steps: `src/test/java/com/ykb/uplift/uptest/playwright/ngc/steps/NGCSteps.java`
- NGC page object: `.../ngc/steps/util/NGC.java`
- NGI steps: `.../playwright/ngi/steps/NGISteps.java`  (verify exact path)
- NGI page object: `.../playwright/ngi/steps/util/NGI.java`  (verify exact path)
- Common: `.../playwright/common/` — CommonSteps, CommonActions, Hooks, TestContext
- Components: `components/` (grid/Grid+GridElement, selectbox.account, end/EndPage)
- Features: `src/test/resources/com/ykb/uplift/uptest/*.feature`
- `DO_NOT_COMMIT/` — page object and steps variants with hardcoded test
  data (CIF, userid, passwords). **Never read, expose, or commit.**

## Execution model (IMPORTANT — read this)

**Claude does NOT run the tests.** This local copy is for writing and editing
code only. The actual test execution happens on the corporate VDI, separately:

1. Claude writes or edits the code (step definitions, page object, features).
2. The user copies the changes to the VDI and runs the tests there.
3. The user brings results back as a screenshot or pasted text (console
   output, stack traces, the Cucumber run panel).
4. Claude reads those results and continues — diagnosing failures, fixing,
   or moving on.

So: never assume a test was run, never claim a fix works until the user
reports the VDI result, and don't try to execute Maven/test commands locally —
they won't reach the real banking environment. Produce the code; the user is
the bridge to execution.

## Environment notes (for the VDI runs the user performs)

- Browser: uses system Edge via `.setChannel("msedge")`, NOT downloaded Chromium
  (corporate SSL inspection blocks browser download).
- Required env var on every run config: `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`
- Traces saved to `target/traces/` per scenario (via `@After tearDown`).

## Current vocabulary (proven steps)

- `login with cif {string} and userid {string}` (NGC)
- `login to ngi with cif {string}` (NGI — customer code; password/OTP hardcoded
  in the NGI page object, not exposed as step args)
- `navigate to {string} {string}`
- `click {string}` (matches button OR link)
- `enter {string} {string}` (text input)
- `enter amount {string} {string}` (masked amount field, kuruş-aware)
- `select {string} from {string}` (Select2 dropdown — reads aria-owns dynamically)
- `select account {string} from {string}` (Select2 **v4** account combobox, e.g.
  "Alıcı Hesabı": opens via the field label, clicks the option whose digits
  contain the wanted account number — spacing ignored. NOT the native `<select>`,
  which is aria-hidden, so the generic `select` step can't drive it.)
- `select account at index {int} from {string}` (same picker, 0-based over the
  listed `li.select2-results__option` items)
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
- **findValueElement:** getByText exact → `ancestor::div[fieldcontainer]`.
  Confirmed to work on BOTH NGC and NGI, so it lives in `CommonActions` (along
  with `select`, `verifyFieldEquals`, `verifyFieldHasAmount` which depend on it).
  May still break on screens where label and value share a span class.
- **Account pickers ≠ one widget.** Two distinct Select2 variants exist. The
  recipient "Alıcı Hesabı" field is **Select2 v4** (`select2-selection__rendered`,
  `role=combobox`, options = `li.select2-results__option` at body level, 3 spans:
  text/branch/accountnumber). The `components/selectbox.account` `AccountSelectBox`
  is **Select2 v3** (`.select2-drop-active`, `.select2-results-dept-0`) and its
  `Account` parser needs **10 spans with balances** — it fits the balance-rich
  *source*-account dropdown, NOT the v4 recipient field. Don't route v4 fields
  through it. v4 selection lives in `CommonActions.selectAccount` (open via label,
  match option by digits, or by index).

## Open issues

- `verifyFieldEquals` reads the LABEL span instead of the VALUE on the DBS
  Firma Fatura summary screen (label and value share class `elem-span-infos`).
  Needs label-exclusion logic to stay generic.
- Verifying text in a Invoice table 

## Conventions

- Communication: Turkish or English. Direct and skeptical, action-oriented, copy-pasteable commands.
- Keep scenarios clean/readable (analysts edit them). Complexity → page object.
- Generic over hardcoded: derive locators from labels/attributes, not fixed ids.
- Diagnose by measuring, not guessing (e.g. print container/element counts
  before assuming why a locator fails). Prove the bug, fix it, prove it's fixed.

## Sharing between NGC and NGI (common layer)

Goal: share what is genuinely application-independent; keep
application-specific logic separate.

- **Truly shared (in `common`, done):** generic steps (`navigate to {} {}`,
  `click`, `enter`, `enter amount`, `select`, `verify the {} field equals {}`,
  `verify the {} has amount {}`, `verify {} text is displayed`, `wait`,
  `go to`) and their logic in `CommonActions`, including `findValueElement`,
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
  a "duplicate step definition" error.
- **Page sharing:** use Cucumber dependency injection (PicoContainer). A shared
  `TestContext` holding `page` is injected into every step class for a
  scenario; the hook fills `ctx.page`, the other step classes read it. Do NOT
  use a `static` page (breaks parallel scenarios). Note injection order: step
  class constructors run BEFORE `@Before`, so `ctx.page` may be null in a
  constructor — build the page object lazily (on first use), not in the constructor.