# AI Test Automation for Corporate Internet Banking

## Overview

This project automates regression testing for Yapı Kredi's corporate
internet banking platform (NGC) using a BDD stack: Cucumber + Playwright +
Java. The long-term goal is a pipeline where an LLM generates Cucumber/Gherkin
test scenarios, constrained to a fixed, human-authored step vocabulary.

The core design principle: the LLM produces only `.feature` files. Step
definitions and page objects remain human-written and fixed. Complexity
(dynamic locators, value normalization, timing) is hidden inside the page
object layer so that scenarios stay clean and readable — readable enough for
a business analyst to review, edit, and run without writing code.

## Deliverables

A working AI pipeline that generates Cucumber/Gherkin scenarios using the
human-written Playwright step library (vocabulary). The AI produces only the
scenarios; step implementations stay fixed and developer-authored. This
constraint guarantees that every generated test rests on validated,
executable steps rather than AI-invented code.

A web interface that lets analysts generate, review, edit, and export test
scenarios without writing any code.

A test execution history and trace-inspection capability, so previously run
scenarios can be reviewed after the fact — including pass/fail results, the
step at which any failure occurred, and a step-by-step Playwright trace for
debugging.

Documentation and a handover guide that enable the QA team to maintain and
extend the system independently.

## Expected Results

A significant reduction in manual test authoring time: analysts produce
regression scenarios in minutes rather than hours.

Broader and more consistent test coverage, since generating scenarios becomes
cheap enough to cover more edge cases.

A lower barrier to entry: business analysts who don't write code can
contribute test cases directly.

Faster regression cycles, supporting more frequent and confident releases of
the internet banking platform.

Greater traceability and auditability, since past test runs and their
execution traces can be reviewed, making regressions easier to diagnose and
supporting compliance needs.

## Architecture (layers, top to bottom)

- **Scenario layer** — Gherkin `.feature` files. Readable scenarios. The LLM
  generates these; analysts review and edit them.
- **Vocabulary layer** — step definitions (NGCSteps, NGISteps). Maps each
  Gherkin line to a page object method. Fixed, human-authored.
- **Page object layer** — Playwright logic (NGC, NGI). Complexity lives here:
  Select2 dropdowns, amount normalization, auto-wait timing.
- **Real page** — the rendered DOM of the banking application.

Playwright is the execution engine that drives the lower layers: it runs the
step methods and interacts with the page (click, read, verify, wait).

The abstraction flows top-down: each layer uses the one below it without
knowing its internals. In Phase 2 the LLM only generates the top (scenario)
layer; the three layers below stay human-written and fixed.

The project covers two parallel applications: **NGC** (corporate internet
banking, the original target) and **NGI** (individual/retail internet banking,
added later). They are separate applications with different login flows and
HTML structures, so each has its own page object and step definitions; the
vocabulary does not transfer one-to-one between them.

## Current Status

### Phase 1 — Core infrastructure & vocabulary (completed)

Built the Cucumber + Playwright + Java BDD framework from scratch (the team
previously did fully manual testing, no BDD). A first end-to-end flow works:
the Banko payment request flow, from login to the success message, including a
kuruş-precise amount, deterministically (no fixed waits — auto-wait based).

Real engineering obstacles solved along the way: corporate VDI/SSL browser
download blocking, masked amount fields, late-loading confirm screens,
hidden-input misreads, label ambiguity, and normalized amount comparison —
including a critical bug where text fields were falsely passing because every
value was being collapsed to an empty string before comparison.

Resulting proven vocabulary: login, navigate, click, enter, enter amount,
select (Select2 dropdown), verify field equals, verify field has amount,
verify text is displayed.

### Phase 1.5 — Vocabulary validation on a second flow (in progress)

Writing a second flow (DBS Firm Invoice Inquiry) to test whether the
vocabulary is genuinely generic or Banko-specific. This already surfaced a new
interaction type (Select2 dropdown), which was added as a generic `select`
step that reads the dropdown's `aria-owns` attribute dynamically rather than
hardcoding per-field ids — so the same step works for any dropdown.

### Phase 2 — AI / LLM generation (planned)

The main goal: an LLM generates Gherkin scenarios constrained to the existing
step vocabulary. Planned approach: discover the page structure with Playwright
MCP, produce a structured catalog of each screen (labels, dropdowns, available
values), and feed that catalog plus the vocabulary to the LLM so it can author
valid scenarios. A validation layer then confirms each generated scenario maps
to defined steps and actually runs. A web interface for analysts and execution
history/trace inspection follow.

Why this is feasible on web: the rendered DOM is queryable and
accessibility-rich, so offline page discovery is possible without sending the
DOM to the LLM on every request — a privacy/compliance advantage in a banking
context.

## Open Issues

- `verifyFieldEquals` reads the label span instead of the value on the DBS
  Firm Invoice summary screen, because label and value share the class
  `elem-span-infos`. Needs label-exclusion logic to stay generic.
- The invoice table