# AI Test Automation for Corporate Internet Banking

Goals, deliverables and roadmap. This is the "why and where to" document; the
"how" lives elsewhere and is not repeated here:

- **Test side** (step vocabulary, page objects, Select2/NGC/NGI rules):
  [`CLAUDE.md`](CLAUDE.md)
- **Server side** (orchestrator + executor API, concurrency, Docker, deployment):
  [`uptest-executor/src/main/java/server/CLAUDE.md`](uptest-executor/src/main/java/server/CLAUDE.md)
- **Analyst-facing vocabulary:** [`VOCABULARY.md`](VOCABULARY.md)
- **Catalog schema:** [`catalogs/SCHEMA.md`](catalogs/SCHEMA.md)
- **End-to-end flow, beginner level (Turkish):**
  [`docs/COKLU-SENARYO-AKISI.md`](docs/COKLU-SENARYO-AKISI.md) — the whole
  pipeline walked through on one real catalog: catalog production (two
  producers), the variation plan, the N model calls, the UI, the run. Also the
  one distinction nothing else spells out: the MCP agent's context grows and
  killed a run, the scenario generator's does not grow at all.
- **Narrative / presentation context (Turkish):**
  [`docs/SUNUM-BAGLAMI.md`](docs/SUNUM-BAGLAMI.md)
- **External-framework evaluation (Turkish):**
  [`docs/E2E-CERCEVE-DEGERLENDIRME.md`](docs/E2E-CERCEVE-DEGERLENDIRME.md) —
  why we are not adopting `tester-army/e2e`, and the three of its ideas we are
  building ourselves instead

## Overview

This project automates regression testing for Yapı Kredi's internet banking
platform using a BDD stack: Cucumber + Playwright + Java. An LLM generates
Cucumber/Gherkin scenarios, constrained to a fixed, human-authored step
vocabulary.

The core design principle: **the LLM produces only `.feature` files.** Step
definitions and page objects remain human-written and fixed. Complexity
(dynamic locators, value normalization, timing) is hidden inside the page
object layer so that scenarios stay clean and readable — readable enough for a
business analyst to review, edit, and run without writing code.

That constraint is where the trust comes from: every generated test rests on
steps that were already proven to run against the real application. The model
cannot invent a new way to click something, because the only verbs it has are
the ones that exist.

What started as a test framework is now a small platform: two Spring Boot
applications (an analyst-facing orchestrator and a browser-driving executor), a
web UI, a scenario validator, and an agent that walks a screen and writes its
catalog.

## Deliverables

| # | Deliverable | Status |
|---|---|---|
| 1 | AI pipeline generating Gherkin against the human-written step vocabulary | **built** |
| 2 | Web interface for analysts to generate, review, edit, run and export scenarios | **built** |
| 3 | Execution history and Playwright trace inspection | **built**; history now persisted (H2, orchestrator) — end-to-end restart proof still pending, see Roadmap |
| 4 | Documentation and a handover guide for the QA team | **partial** — reference docs exist, the handover guide does not |

1. **The AI pipeline.** The model produces only scenarios; step
   implementations stay fixed and developer-authored.
2. **The web interface.** Analysts work in the browser: describe intent,
   generate, edit, validate, run, watch the step timeline, download the report.
3. **History and traces.** Pass/fail per run, the step at which a failure
   occurred, and a step-by-step Playwright trace for debugging.
4. **Handover.** Enough documentation for the QA team to maintain and extend
   the system independently.

## Expected Results

A significant reduction in manual test authoring time: analysts produce
regression scenarios in minutes rather than hours.

Broader and more consistent test coverage, since generating scenarios becomes
cheap enough to cover more edge cases.

A lower barrier to entry: business analysts who don't write code can contribute
test cases directly.

Faster regression cycles, supporting more frequent and confident releases of
the internet banking platform.

Greater traceability and auditability, since past test runs and their execution
traces can be reviewed, making regressions easier to diagnose and supporting
compliance needs.

## Architecture

### The four layers

- **Scenario layer** — Gherkin `.feature` files. The LLM generates these;
  analysts review and edit them.
- **Vocabulary layer** — step definitions (CommonSteps, NGCSteps, NGISteps).
  Maps each Gherkin line to a page object method. Fixed, human-authored.
- **Page object layer** — Playwright logic (CommonActions, NGC, NGI).
  Complexity lives here: Select2 variants, masked inputs, amount
  normalization, auto-wait timing.
- **Real page** — the rendered DOM of the banking application.

Playwright is the execution engine that drives the lower layers: it runs the
step methods and interacts with the page. The abstraction flows top-down: each
layer uses the one below it without knowing its internals. The LLM only ever
touches the top layer.

### What a scenario is made of

> **catalog** (the nouns) + **product rules** (what must be true) +
> **analyst intent** (the verbs and values) + **vocabulary** (the grammar)

A catalog is a screen's dictionary — labels, types, options, how to reach the
screen — and carries no test data, which is why one catalog serves many
scenarios and why the validator can check a generated scenario against it.

The **product rules** are the fourth element and the newest. They exist
because a test has two halves and only one of them can be read off a screen:
an action, which discovery can find, and an *expectation*, which it cannot. A
catalog is produced by looking at the application, so whatever it records is
true by construction — including a bug. Told only to "test this screen", the
model treats what it sees as what should be, and writes the defect into the
suite. The rules are written by a person, live in
`products/<app>/<product>/rules.yaml`, and are the only input that can
contradict the application. See `products/SCHEMA.md`.

### Two applications

**NGC** (corporate) and **NGI** (individual/retail) are separate applications
with different login flows and HTML structures. Each has its own page object
and step definitions; only genuinely application-independent logic lives in the
common layer. The vocabulary does not transfer one-to-one between them.

### Two running applications

The orchestrator (analyst UI, validation, LLM, dispatch) never opens a browser;
the executor does nothing else. They are separate processes on separate ports
for two measured reasons — image size and the fact that a hung Playwright call
cannot be killed from Java, so the recovery is restarting one executor while
the others keep serving. Details in the server-side document.

## Current Status

### Phase 1 — Core infrastructure and vocabulary (completed)

Built the Cucumber + Playwright + Java BDD framework from scratch; the team
previously did fully manual testing with no BDD layer at all. A first
end-to-end flow works: the Banko payment request flow, from login to the
success message, kuruş-precise, deterministically (no fixed waits — auto-wait
based).

Real engineering obstacles solved along the way: corporate VDI/SSL blocking the
browser download, masked amount and date fields, late-loading confirm screens,
hidden-input misreads, label ambiguity, custom-styled radios Playwright refuses
to click, and two visually identical Select2 variants of which only one can be
driven. The most expensive of them was a silent one: text verification
normalized its values, collapsing every string to `""`, so **every text
assertion passed against nothing for two days.** The rule that came out of it —
a silent pass is worse than a failure — shaped much of what followed.

### Phase 1.5 — Vocabulary validation on further flows (completed)

A second and third flow (DBS Firm Invoice Inquiry, Online DBS Fatura Oluştur)
were written to test whether the vocabulary was genuinely generic or
Banko-specific. They surfaced the grid row selection, account picker and masked
date interactions, each of which became a generic step rather than a
flow-specific one. The vocabulary now stands at **20 patterns**, proven across
two applications and several flows.

### Phase 2 — AI generation and the analyst platform (largely built)

- **Vocabulary as a generated single source.** `VocabularyExtractor` reads the
  `@Given/@When/@Then` annotations at build time into `vocabulary.json`, which
  the orchestrator fetches over HTTP from a live executor. A hand-maintained
  list would drift from the code, and a validator working from a drifted list
  approves scenarios that then fail as undefined steps.
- **Screen catalogs, produced two ways.** An LLM drives a real browser through
  Playwright MCP, walks the flow, records each field as it examines it, reads
  the confirmation screen and the success banner, and the analyst saves the
  draft straight into `catalogs/`. Alongside it, a deterministic DOM scan runs
  on the executor as a synthetic feature and sees what the agent cannot: Select2
  v4 versus v3, an amount that is two inputs, dependencies measured by diffing
  screen state. Their blind spots are opposite — the scan cannot reach a success
  message, the agent cannot tell one Select2 from another — which is why both
  are kept and why each has caught the other being wrong. Neither output is a
  catalog until a human has read it.
- **A catalog-aware validator.** Grammar is checked with the real Cucumber
  parser rather than hand-written regex, so its verdict matches Cucumber's
  exactly. Semantics are checked against the catalog: unknown labels, verbs
  that do not fit a field's type, options the screen never offered, invented
  expected values, a re-worded success message. Runs in ~50 ms with no browser.
- **Generate → validate → repair.** `SuggestService` feeds validation errors
  back to the model for one repair turn, and returns a still-invalid draft
  anyway together with its errors — a nearly-right scenario an analyst can fix
  beats an error page.
- **Batch generation, with the variation computed rather than requested.** One
  screen, N scenarios, each exercising a different combination of what that
  screen offers. The differences are decided in Java (`VariationPlanner`) and
  handed to the model as literal values, because the generation temperature is
  deliberately low — the same request must give the same scenario, which makes
  "write five different ones" return five near-identical files. Computing it also
  makes the planner answerable: it resolves conditionally-shown fields correctly,
  never writes to a field the application owns, and reports its own ceiling when
  a screen cannot produce as many genuinely different tests as were asked for.
  Each draft is its own document, validated and repaired on its own.
- **The platform.** One orchestrator (`:8035`) and three executors (`:9035`,
  internal only) under Docker Compose, built and verified. Live step timeline,
  run history, HTML report and trace download.
- **The model runs inside the bank.** A vLLM deployment on the bank's own
  infrastructure, reached over an OpenAI-compatible API through Spring AI, so
  no data leaves the institution and swapping models is a configuration change.

### Phase 3 — Hardening and handover (next)

Persistence, artifact retention, agent robustness, and the QA handover guide.
See Roadmap.

## Roadmap

1. **Run history persistence — largely done, not finished.** `TEST_RUN` and
   `TEST_RUN_STEP` landed 2026-10-06 (H2 file mode, Spring Data JPA,
   `ddl-auto=update`, orchestrator only). The history the UI lists now survives
   a restart, so a report from last month is still reachable. What remains:
   `SCENARIO_DRAFT` and `DISCOVERY_RUN` are designed but unwritten, so a
   catalog discovery that finishes after a restart is still unrecoverable; the
   four test classes exist but have never been run; and the one irreversible
   check — that the long-text columns really came out unbounded rather than
   `varchar(255)`, which `ddl-auto=update` can never widen — has not been done
   against a live database yet.
2. **Artifact retention policy.** Traces run to tens of megabytes each and
   nothing prunes them.
3. **MCP agent robustness.** The agent is useful but not yet reliable: it can
   retry a failing tool call verbatim instead of taking a fresh snapshot, and
   its guards cap total calls and repeat navigations but not repeated identical
   failures. Every fix so far has followed the same shape — a prompt rule only
   held once it became code.
4. **Catalog completeness.** `shown_when` is still hand-written, and the agent
   can miss rows of a confirmation table, which weakens the `verifiable` flags
   a scenario depends on.
5. **Wider coverage.** More flows, and more of NGI, which today has login and
   little else.
6. **Handover guide.** The reference documents exist; the maintenance guide for
   the QA team does not.

## Open Issues

- **Persistence is partial.** Run history is now a database (`TEST_RUN`,
  `TEST_RUN_STEP`, orchestrator-side H2). Still in memory and lost on restart:
  catalog discovery runs — so a discovery that finishes after a restart cannot
  be recovered and its YAML must be walked again — and the record of what the
  model generated. The executor keeps nothing by design.
- **The MCP agent can loop on a failed tool call.** Seen on the NGC login page,
  where two `Giriş` links match one selector: the model retried the same guess
  dozens of times instead of re-reading the page, then had its recovery
  navigation refused by the restart guard and returned nothing.
- **The "Seçiniz" placeholder still reaches catalog drafts**, and it is the one
  option a scenario must never choose.
- **`shown_when` is hand-written** — neither producer can emit it.
- **`verifyFieldEquals` reads the label instead of the value** on screens where
  label and value share a span class (`elem-span-infos`, DBS Firm Invoice
  summary). Needs label-exclusion logic to stay generic.
- **Verifying text inside an invoice table** is not solved.
- **`mvn test` and the IDE run gutter find no features**, because the feature
  directory is literally named with dots while the runner selects a slashed
  classpath resource. Harmless for the server path, but it is the first thing a
  new developer trips over.

## Numbers

| | |
|---|---|
| step vocabulary | 20 patterns, generated from the code |
| applications supported | 2 (NGC, NGI) |
| executor image | 4.97 GB (Playwright + browsers) |
| orchestrator image | 444 MB |
| concurrent run capacity | 3 (one per executor, no queue) |
| validation | ~50 ms, no browser |
| one scenario run | minutes |
