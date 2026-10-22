# cucumber-uplift-test

AI test automation for Yapı Kredi internet banking. An LLM generates Gherkin
scenarios using a fixed, human-written step vocabulary; it produces only
`.feature` files, never step implementations.

## Working rules — never skipped

These come first because they are standing orders, not navigation. Everything
below them is detail about THIS project; these are how the work is done at all.

### 1. Understand the Existing Codebase First

Before changing code, understand the relevant parts of the project.

- Inspect the existing implementation, call hierarchy, dependencies, and related tests before editing.
- Trace the complete flow when working on bugs, including frontend, API, service, repository, and database layers where applicable.
- Reuse existing utilities, patterns, and conventions instead of introducing new ones unnecessarily.
- Do not guess how a function, API, or dependency behaves. Inspect its implementation or documentation.
- If the root cause is unclear, investigate before proposing a fix.

### 2. Preserve Existing Behavior

- Treat existing behavior as intentional unless there is evidence of a bug or a requirement to change it.
- Identify potential regressions before modifying shared code.
- Preserve backward compatibility unless the task explicitly requires breaking changes.
- Consider edge cases, null values, validation, error handling, and concurrency when relevant.
- Do not silently change API contracts, database schemas, configuration, or security behavior.

### 3. Verify Before Claiming Success

- Run the most relevant tests and build commands available.
- For bug fixes, verify that the original problem is resolved and existing behavior remains intact.
- Review the final diff for unrelated changes, formatting noise, accidental deletions, and generated files.
- If tests cannot run, explain why and identify what remains unverified.
- Never claim that tests passed, a build succeeded, or a bug was fixed unless the result has been verified.

> **In THIS project Claude does not run the tests** — see *Execution model*
> below. Nothing here reaches the banking environment, so "if tests cannot run,
> explain why and identify what remains unverified" is the DEFAULT case, not the
> exception. What can be verified locally is compilation and pure-Java logic;
> everything that needs a browser is verified by the user and reported back.
> Never describe a browser-dependent fix as working before that report arrives.

### 4. Debug Systematically

- Start with the observed symptoms, logs, stack traces, and reproducible steps.
- Form a hypothesis and gather evidence before changing code.
- Identify the root cause rather than masking the symptoms.
- Prefer the smallest fix that addresses the root cause.
- Avoid speculative changes and multiple unrelated fixes in a single patch.
- After implementing a fix, explain the root cause and why the solution works.

> Concretely here: diagnose by measuring, not guessing — print container and
> element counts before assuming why a locator fails. Prove the bug, fix it,
> prove it's fixed.

### 5. Security and Sensitive Data

- Never hardcode secrets, passwords, access tokens, private keys, or credentials.
- Do not weaken authentication, authorization, input validation, or security checks to make tests pass.
- Follow the project's existing security practices and least-privilege principles.
- Avoid logging sensitive data.
- Flag security vulnerabilities discovered during implementation, even if they are outside the immediate task.

> Two directories in this repo are exactly this rule made concrete:
> `DO_NOT_COMMIT/` (page objects carrying real CIF, userid and passwords) and
> `.mcp-browser-profile/` (a logged-in bank session cookie). Never read, expose
> or commit either; both `.gitignore` and `.dockerignore` guard them.

### 6. Communication and Final Summary

Before substantial changes, provide a short plan with clear verification criteria.

After completing the task, summarize:

- **Root cause:** What was wrong and why.
- **Changes:** Which files and behaviors were modified.
- **Verification:** Which tests or commands were actually run and their results.
- **Risks:** Any known limitations, regressions, or unverified assumptions.

Keep explanations concise and specific. Clearly distinguish verified facts from assumptions.

---

**This file covers the TEST side** — step vocabulary, page objects, Select2
handling, NGC/NGI rules. Two companion documents own the rest; do not duplicate
them here:

- **Server side** (orchestrator + executor API, deployment):
  [`uptest-executor/src/main/java/server/CLAUDE.md`](uptest-executor/src/main/java/server/CLAUDE.md)
- **Goals, deliverables, roadmap:** [`PROJECT.md`](PROJECT.md)
- **End-to-end flow, beginner level (Turkish):**
  [`docs/COKLU-SENARYO-AKISI.md`](docs/COKLU-SENARYO-AKISI.md) — what actually
  happens from "test invoice creation on this screen" to a running test: every
  HTTP call, every model call, what the model is given, which calls take
  minutes, and the three separate "AI" paths that are easy to confuse (MCP
  agent / DOM scan / scenario generator). Read it before asking how any of this
  fits together.

## Repository shape

Two Maven modules under a `packaging=pom` parent. The split is deliberate: the
orchestrator never opens a browser, so it must not carry the 236 MB Playwright
driver bundle. They talk over HTTP only — there is no Maven dependency between
them.

```
uptest-executor/      runs tests: Cucumber + Playwright + Spring Web
uptest-orchestrator/  UI, validation, dispatch. NEVER runs a test.
```

One repository and one parent pom, but **two separate running applications**:
built together, started apart, on their own ports. The orchestrator (`:8035`)
is the only one analysts reach; executors (`:9035`) answer it alone. Each
executor takes exactly one run at a time, so concurrency
comes from adding replicas — and there is no queue: when they are all busy the
next request is refused outright.

Ports, the dispatch chain, what happens when several scenarios are submitted at
once, and where the run history is stored are all in the server-side document
([`uptest-executor/src/main/java/server/CLAUDE.md`](uptest-executor/src/main/java/server/CLAUDE.md)).
Do not restate them here.

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
- Common: `PW/common/` — CommonSteps (18 shared steps), CommonActions (all the
  Playwright logic), Hooks, TestContext, RunContext
- Components: `PW/components/` — grid/Grid+GridElement, selectbox/account,
  end/EndPage

Elsewhere:

- Scenarios (working copies analysts and the LLM look at): `scenarios/*.feature`
- Product context (the oracle): `products/<app>/<product>/` — `product.yaml`
  (identity + the flows it claims), `rules.yaml` (ID'd business rules), and
  optional `flow.md` / `roles.md` / `open-questions.md`. Schema in
  `products/SCHEMA.md`, read by `orchestrator/product/`. Separate from
  `catalogs/` and NOT a directory level inside it: `Origin{app, flow}` travels
  on every validate/suggest/run request and is persisted in `TEST_RUN`'s own
  columns, so a third path segment would mean changing the wire format, the
  database and three places in the UI. The product declares its flows rather
  than each catalog naming its product — see the lesson below for why that
  direction is load-bearing.
- Kayıtlı Senaryolar (ana menü, screens, scenarios): the orchestrator's H2
  database, tables `MAIN_MENU` / `SCREEN` / `SCENARIO`, touched only by
  `orchestrator/product/ScenarioStore`. Natural language and Gherkin are
  separate columns (`ScenarioFields`); `FeatureComposer` rebuilds the .feature
  a run needs, `ScenarioDocParser` splits one. product.yaml and
  `products/**/scenarios/*.feature` are read once, at startup, for a product
  the database does not know yet (`ProductService.importMissing`) — after that
  the database is the source, and "Dosyaya aktar" (`POST
  /api/v1/products/export`) writes it back to files for git, never deleting.
  Rules, test data and the analysts' notes stay files.
- Screen catalogs for LLM prompting: `catalogs/<app>/<flow>.yaml`; schema in
  `catalogs/SCHEMA.md`
- Screen discovery (executor): `PW/discovery/` — `CatalogDiscovery` walks a flow
  and writes `catalog-draft.yaml` into the run directory. Driven by two steps
  (`discover screen catalog`, `fill discovered fields:`) that `VocabularyExtractor`
  deliberately keeps OUT of the published vocabulary: they read a screen, they do
  not describe a test, and the generator must never emit them. That exclusion is
  why a hand count of `@Given/@When/@Then` (22) disagrees with
  `GET /api/v1/vocabulary` (20).
- Run history (orchestrator): `orchestrator/persistence/` holds the entities and
  repositories, `orchestrator/run/RunStore` is the ONLY bean in the run path
  that touches JPA, and `orchestrator/run/Redactor` strips the cif and userid
  out of every path that writes scenario text down. H2 file mode, schema by
  `ddl-auto=update`, orchestrator only — the executor never gets a database.
  Details in the server-side document.
- MCP exploration agent (orchestrator): `orchestrator/explore/` + `.mcp.json`
- Scenario chat (orchestrator): `orchestrator/chat/` — writing a scenario by
  talking about it. **No browser**: it is the generator with the repair turn
  pointed at a PERSON. `SuggestService.suggestOnce` generates once, and
  `ChatQuestions` turns the validator's first finding into a QUESTION whose
  buttons are the routes that finding's own message already names — for an
  unearned assertion, "give me the expected value" and "drop the check".
  Three kinds get a tailored question; everything else shows the validator's
  own words and asks for free text, because inventing options for a finding
  this class does not understand is the guessing it exists to stop. Sessions
  are in memory only (`ChatSessionStore`, swept on access, no `@Scheduled`) —
  a transcript is free text and free text is an uncontrolled channel, the
  hazard `TestRunEntity` already documents.
- Discovery dispatch (orchestrator): `orchestrator/catalog/CatalogDiscoveryService`
  builds the synthetic feature and sends it through `ExecutorPool` like any other
  run; `orchestrator/api/DiscoveryController` is its endpoint.
- Human-facing vocabulary contract: `VOCABULARY.md`
- `mvn test` feature classpath: `uptest-executor/src/test/resources/` +
  `uptest-executor/src/test/java/.../RunCucumberTest.java`
- `DO_NOT_COMMIT/` — if present, page object and steps variants with hardcoded
  test data (CIF, userid, passwords). **Never read, expose, or commit.** Both
  `.gitignore` and `.dockerignore` guard it; keep it that way.

## Execution model (IMPORTANT — read this)

**Claude does NOT run the tests.** This local copy is for writing and editing
code only — nothing here reaches the real banking environment. Real execution
happens in exactly ONE place, operated by the user: **the corporate VDI**
(IDE / `mvn test`, or the orchestrator UI driving an executor there).

The loop:

1. Claude writes or edits the code (step definitions, page object, features).
2. The user runs it on the VDI.
3. The user brings results back as a screenshot or pasted text (console
   output, stack traces, the Cucumber run panel, the UI's step timeline).
4. Claude reads those results and continues — diagnosing failures, fixing,
   or moving on.

So: never assume a test was run, never claim a fix works until the user reports
the result, and don't try to execute Maven/test commands locally. Produce the
code; the user is the bridge to execution.

**Docker: later.** The repo carries compose files and Dockerfiles, and the
server-side document describes a container deployment in detail — but it is
**not a live path**. Docker does not work on the VDI and is not used today.
Container deployment will be taken up later; until then do not propose a Docker
step and do not describe one as a way runs happen.

## Environment notes

**Browser settings come from three env vars**, read in `Hooks.launchOptions`.
The defaults ARE the VDI, so nothing has to be set for an ordinary run:

| var | default (= the VDI) | meaning |
|---|---|---|
| `UPTEST_HEADLESS` | `false` | a visible browser |
| `UPTEST_BROWSER_CHANNEL` | `msedge` | the system Edge |
| `UPTEST_SLOWMO` | `50` | ms between Playwright actions |

They exist because a headless host would need all three overridden. That host
is the container deployment, which is not live — see *Docker: later* above.

- The VDI also needs `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` on every run config:
  corporate SSL inspection blocks the Chromium download.
- `UPTEST_BROWSER_CHANNEL` deliberately does NOT go through the
  blank-means-default helper: *unset* = system Edge, *set-but-empty* = no
  channel, which is the only way to ask for Playwright's bundled Chromium.
  Routing it back through that helper made `""` unreachable and sent a
  browserless host hunting for an Edge it did not have.
- Traces go to the per-run directory the server created
  (`{runDir}/traces/<scenario>.zip`), falling back to `target/traces/` when
  Cucumber was started outside the server (`mvn test`).

## Current vocabulary (22 steps)

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
- `enter {string} {string}` (text/date input, by label — a literal date still
  works here, and that is the only way to write one specific day)
- `enter date {string} {string}` (a date written as what it MEANS, resolved by
  `RelativeDate` AT RUN TIME: `"bugün"`, `"yarın"`, `"dün"`, `"+3 gün"`,
  `"-1 hafta"`, `"2 ay"`, `"1 yıl"`, or an absolute `gg/aa/yyyy` passed through
  untouched. The generated verb for `type: date` — see the lesson below on why a
  literal date rots)
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
- `upload {string} {string}` (attaches a file. Second argument is a NAME, not
  a path — resolved under `UPTEST_TESTDATA_DIR`, default `./testdata`, so no
  machine path reaches a scenario. Two shapes: a real `input[type=file]`
  driven with `setInputFiles` — which deliberately does NOT need the input
  visible, because these forms hide it behind their own "Gözat" button — and,
  when there is no such input, `waitForFileChooser` armed BEFORE the click.
  Reads the attachment back. Closes the DBS "Oluşturma Tipi" fork's second
  branch, which `choose` could reach and nothing could then drive)
- `check {string}` (ticks the checkbox with that caption — a whole sentence on
  these screens. Idempotent: a box already ticked is left alone, because
  clicking it again would UNtick it. Same hidden-input problem as the radios,
  so it falls back to clicking the label, and reads the state back afterwards)
- `select row {string}` (result grid radio button: matches the radio's own `td`
  first, then the whole `tr`, so a value in any column works)
- `select row at index {int}` (0-based over the radios on the page)
- `choose {string}` (a radio OUTSIDE a grid, by the text beside it. `select row`
  matches on `ancestor::td` then `ancestor::tr` and so cannot see one in a plain
  form section — on DBS Fatura Oluştur the form is gated behind "Oluşturma
  Tipi". Found by ROLE so the name is the one a person reads, then falls back to
  clicking the label when the input is custom-styled and hidden)
- `verify the {string} field equals {string}` (text/date, EXACT match, NO normalize)
- `verify the {string} has amount {string}` (amount, normalized)
- `verify the {string} field is masked` (the application is still HIDING the
  value — `20****694`. The only verify taking no expected value, which is what
  makes it the one assertion available on a read-only screen: nothing was
  filled in there, so `checkAssertionsAreEarned` refuses every value-bearing
  verify, and before this step existed the model's repair turn resolved that by
  deleting the assertion and calling a scenario that checks nothing "valid")
- `verify {string} text is displayed`
- `wait {string} seconds` (avoid — prefer auto-wait)

## Where rules come from — and why they are not in the catalog

A catalog says what a screen HAS. A **product** says what the application must
DO. The split is not organisational; the two have different producers and
different sources of truth, and that is the whole argument:

- A catalog is **discovered**. The DOM scan reads the rendered page, the MCP
  agent reads an accessibility snapshot. Both produce a description of a
  screen as it stands, and **neither can say whether what it found is
  correct**. Ask an agent to "test this screen" and it records the İptal
  button it finds on a paid invoice as expected behaviour — a bug promoted to
  a passing test, for ever.
- A rule is **written**, by a person, from an analysis document or the
  business. Nothing on the screen establishes it.

The second reason decides the direction of the binding: **rules exist before
the screen does.** A product is specified while its catalogs are still being
walked, so the product declares its flows and a flow with no catalog on disk
is the work queue, not an error. Had the binding lived in the catalog, a
product could not be written at all until someone had walked a screen.

**A rule that CHANGES marks the scenarios bound to it, and never rewrites
them.** A rename or a deletion was always caught, because the binding is an id;
a reworded `description` or a dropped option was silent, and five scenarios
went on testing the old wording. `orchestrator/product/SourceSnapshot` stores
the full text of every rule and alias a scenario names at the moment it was
last reviewed (`SCENARIO.reviewed_sources`, a copy rather than a hash because
"what changed" is the half an analyst can act on) and diffs it on every read.
It reports and stops: the same difference means either "the application changed
and the rule caught up" (the scenario should follow) or "the rule was written
down wrong and has been corrected" (the scenario should change AND the run
should stay red over a defect that is still there), and auto-rewriting hides
the second one behind a green test. An UNSTAMPED scenario reports nothing — a
badge that is on for every row says nothing about any of them.

**Only `messages` and `degerler` are enforced**, and they earn the layer its
place. `degerler:` is named lists of literals a rule fixes (`secenekler`,
`varsayilan`); the validator accepts them exactly as it accepts `messages`,
since a dropdown's options are something the screen prints, and on a screen
with no catalog they are the only thing the check can consult.

Before any of it, `ScenarioValidator.checkMessageIsCatalogued` accepted exactly
one asserted sentence — the catalog's `success_message` — and its own javadoc
recorded the cost: *"a negative scenario asserting an error dialog's text is
flagged too."* For a payments product that is most of the suite, because nearly
every rule worth writing is a refusal and a catalog has nowhere to record one.
The accepted set is now `success_message` ∪ every rule's `messages` ∪ every
rule's `degerler`, which widens what counts as catalogued rather than loosening
the check.

Everything else — `description`, `flow.md`, `roles.md`, `fields` — steers the
prompt without the validator having an opinion, exactly as `navigation` and
`verifiable` do in a catalog, and carries the same hazard.

**The ticked rules shape the PROMPT; the product shapes the VALIDATOR.** Only
the rules an analyst selected reach the model, because the prompt already
carries eleven hard rules, the live step list and the whole catalog, and a rule
far from where it applies is a rule the model does not read. The validator sees
every message the product knows regardless — tying it to the ticks would mean a
draft accepted at generation being refused by the identical check at the run
gate because a checkbox was cleared in between.

`open-questions.md` is the one file deliberately **never** sent to the model.
Its purpose is to mark where an assumption would otherwise be invented, and
handing that list to something that invents plausible text defeats it. It is
shown to the analyst instead.

## Where catalogs come from

A catalog is the screen's **dictionary**, not a scenario and not a template:
labels, types, options, how to reach the screen. It carries no test data — a
scenario is catalog (nouns) + intent (verbs and values) + vocabulary (grammar),
so ONE catalog serves many scenarios and an analyst varies data without
touching it. It is also what `ScenarioValidator` checks a generated scenario
against, which a template could not be.

**Two producers, with different blind spots.** They were built in that order and
both are still here, because each has caught the other being wrong:

- **DOM scan** (`PW/discovery/CatalogDiscovery`, UI "Ekranı Keşfet"). No model,
  no tokens, repeatable. It runs on the EXECUTOR: `CatalogDiscoveryService`
  builds a synthetic "login, navigate, discover" feature and dispatches it
  through `ExecutorPool` like any ordinary run. Reading types off the DOM is
  what only it can do — Select2 v4 (drivable) versus v3 (no step exists, so it
  is flagged `UNSUPPORTED_V3`), an amount that is two inputs, and dependencies
  measured by diffing screen state, which is where `readonly` comes from. It is
  also the ONLY producer of the constraint keys (`max_length`, `min`, `max`,
  `numeric`) the batch generator needs: those are DOM attributes, and an
  accessibility snapshot carries none. Blind
  to anything outside a `fieldcontainer` (it missed "Ek Bilgi") and it cannot
  reach a `success_message`, because it never presses the final button.
- **MCP agent** (`orchestrator/explore`, UI "Ajan"). An LLM driving a real
  browser through Playwright MCP. Good at what needs acting and watching:
  finding the path to a screen, the confirmation screen, the success banner,
  fields the scan's selector never sees. It guesses types from an accessibility
  snapshot, so v3-versus-v4 is invisible to it, and it is not repeatable — the
  same screen gave "Ay" once and "Ay, Hafta, Gün" the next run.

Either draft is saved from the UI into `catalogs/<app>/<flow>.yaml`, where the
generator picks it up.

**A draft is not a catalog until a human has read it.** Run both on a new screen
and read the difference: that is how "Ek Bilgi" and a missed `verifiable` were
both found. `ExploreController.problems()` reports mechanically what is checkable
before anyone looks: a type no step can drive, a dropdown with no options, a
`success_message` with no `verifiable` field behind it.

**Only part of a catalog is enforced.** `CatalogModel` parses `fields`
(`label`, `type`, `options`/`accounts`, `readonly`, plus `shown_when` and the
constraint keys `max_length`/`min`/`max`/`numeric`) and `success_message`.
Of those, only the first group is ENFORCED: `navigation` and `verifiable` are
not parsed at all, and `shown_when` and the constraint keys are parsed for
`VariationPlanner` but checked by nobody. `CatalogService` hands the YAML to the
model **verbatim**, so everything unenforced steers the generator without the
validator having an opinion. A wrong `entry_tab` surfaces as a 30-second timeout
in a browser, not as a validation error.

**One catalog, many scenarios — and the variation is computed, not requested.**
`POST /api/v1/scenarios/suggest-many` (UI: "Çoklu Üret") produces N drafts for
one screen. `VariationPlanner` decides in Java what makes each one different —
`options[i % k]` for every dropdown, a boundary ladder for amounts and dates, a
length ladder for text bounded by `max_length` — and the prompt carries those
values literally. Asking the model to "vary the values" cannot work here:
`temperature` is 0.3 precisely so the same request gives the same scenario, and
raising it buys diversity by also buying invented labels. The planner also
resolves `shown_when` correctly (a revealed field is filled ONLY in the
variations that chose its trigger) and reports its own ceiling — the DBS form has
three single-option dropdowns, so past the second scenario only data differs, and
that is said out loud rather than padded over.

**N separate documents, not one file with N scenarios.** `collectFromFeature`
gathers every scenario's steps into ONE list and `checkAssertionsAreEarned` walks
it with ONE set of written labels, so in a five-scenario file the fifth
scenario's `verify` is "earned" by the first's `enter` — the same hole that let
an invented "Bayi Ünvanı" reach a browser run. Separate documents keep each check
as strict as it is for a single scenario, give each draft its own repair turn,
and leave each independently runnable.

## Hard-won lessons (DO NOT regress these)

- **verifyFieldEquals must NOT normalize text.** Normalizing collapsed "Test"
  → "" and made all text verifies falsely pass ("" == ""). This was a serious
  2-day bug — every test silently passed. Text/date = exact match only.
- **Amount fields: read the value SPAN, not hidden inputs.** Confirm-screen
  amount containers have a hidden input holding the kuruş-less value (1.250);
  reading it gives wrong results. Wait for "TL" (containsText) then read the span.
  **Writing has the mirror-image trap**, and the MCP agent walked into it:
  typing a formatted "1.250,50" into what looks like one box leaves the form
  reading nothing, because the value it validates is computed from the two
  separate lira/kuruş inputs. The boxes look filled, the submit is refused with
  "Toplam tutar giriniz", and the screen is right. `enter amount` splits them;
  `ExploreController.SYSTEM` now tells the agent to do the same.
- **A helper that returns `false` for several different reasons makes the
  caller decide wrongly.** `pickFromCalendar` returned false in three cases:
  the value is not a date, the calendar never opened, and THE CALENDAR OPENED
  AND DOES NOT OFFER THAT DAY. The first two mean "this is not mine to drive"
  and falling back to typing is right for them. The third is the application
  saying the date is not allowed — "Başlangıç Vadesi" may not be today,
  because a maturity date must be in the future, so the grid greys today out.
  All three landed in one bucket, so the caller typed the digits with real
  keystrokes, the mask accepted them, `valueTook()` agreed, and the step went
  GREEN on a date the screen had just refused. The run then walked on with a
  field the bank would reject. Fix: the refusal throws, and the message prints
  the days the grid DID offer — because "the grid offers 10..31 and we wanted
  9" (the application's rule, an analyst writes `+1 gün`) and "the grid offers
  1..31 and we matched nothing" (our locator) need opposite fixes and were
  indistinguishable. The general shape: when a helper cannot tell "I could not"
  from "it must not", the second one belongs in an exception, not a boolean.
- **Verifying the effect is part of a step's definition, not a courtesy.**
  Walking `CommonActions` end to end after the calendar bug: `enter`, `select`'s
  Select2 branch and `check` read their effect back; `select`'s NATIVE branch
  (which also passes `setForce(true)`), `selectAccount`, `enterAmount`,
  `clickOption` and `clickRadio` did not. Each of those can press the right
  thing and change nothing — a masked amount box that refuses what was typed, a
  forced `<select>` the application never notices, a label click that lands on a
  neighbouring option. All five now read back and fail loudly. Note which half
  of the file had the checks: exactly the parts where a silent pass had already
  cost a debugging round. The lesson is not "add checks when burned" — it is
  that an action step without a read-back is unfinished.
- **Use auto-wait assertions** (hasText/hasValue/containsText), not
  `wait "N" seconds`. Both branches (input and span) need auto-wait.
  `CommonActions` holds **no** deliberate pause between steps today: the only
  timing knobs in this checkout are `UPTEST_SLOWMO` (per Playwright action, set
  in `Hooks`) and the per-locator timeouts (`PICKER_MS`, `MENU_OPEN_MS`,
  `SELECT_CONFIRM_MS`, `VERIFY_TIMEOUT_MS`). If pacing is ever added, add it as
  a debt paid by the NEXT step so the last step of a scenario never waits for
  nothing — and treat it as hiding a race, not fixing one.
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
  **The MCP agent hit the same wall**, from the other side: it called the MCP
  tool that picks an option out of a select and got back "Element is not a
  `<select>` element … locator resolved to `<span role="combobox"
  class="select2-selection …">`". It had no rule for filling a dropdown at
  all — only for deciding the TYPE of one — so `ExploreController.SYSTEM` now
  tells it to open the control and click the option, and to copy the option
  text rather than retype it (it produced "Taksitli Ödnsin", which matches
  nothing and looks like an application fault).
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
  **The MCP agent hits the same wall**, and typing turned out not to be worth
  rescuing there either. Typed slowly it worked on "Başlangıç Vadesi" and
  failed on "Fatura Tarihi" — same mask, same method, same run — producing a
  date beginning with a digit nobody typed. The asymmetry points at the second
  box not being EMPTY when typing starts: a mask interleaves new digits with
  old ones. So the agent now drives the calendar FIRST, exactly as
  `pickFromCalendar` does, and types only where no calendar opens. The calendar
  replaces whatever the box held, which makes "was it empty?" stop mattering —
  the same reason `select` drives the Select2 widget instead of its hidden
  `<select>`. Order is load-bearing (year → month → day): changing year or
  month redraws the day grid.
- **`waitForSelector` waits for the FIRST match to be VISIBLE.** On Banko
  Alacak İşlemleri the first `input[type=radio]` on the page belongs to the
  collapsed "Detaylı Arama" panel, so `select row` spent its full 30 s waiting
  on a radio it had no interest in and never reached the grid — 21 radios were
  present the whole time. `selectRow` now waits for `ATTACHED` instead.
  Narrowing the wait to `:visible` would have been wrong for the reason
  immediately below. Same shape as the `navigateTo` lesson: the locator was
  right, the first match was hidden.
  **It came back a third time, in `verify {string} text is displayed`.**
  `verifyTextIsDisplayed` was `getByText(text).first()`. On Alıcı Listesi
  İşlemleri the word "Detay" appears EIGHTEEN times, and the first in document
  order sits inside a `<div class="remainText nonprintable">` the application
  keeps hidden — so the assertion burned its whole timeout waiting for that
  div while the real button was on screen the entire run (`18 × locator
  resolved to … unexpected value "hidden"`). The fix is
  `filter(setVisible(true))` before `.first()`, and it is the OPPOSITE of the
  `select row` fix above: there the step clicks a deliberately hidden input
  through its label, here the step's whole claim is that a person can SEE the
  text. So the rule is not "wait for attached" or "wait for visible" — it is
  that **`.first()` is a bet that the first match in document order is the one
  the step means**, and on these screens that bet keeps losing. The failure
  now also reports the match count, because "the text is nowhere" and "the
  text is here N times and every copy is hidden" need opposite fixes and
  Playwright's own message cannot tell them apart.
- **Custom-styled radios are invisible to Playwright.** The drawn circle IS the
  `<label>`; the input is visually hidden and Playwright refuses to click a
  hidden element. `clickRadio` checks visibility and clicks the label instead —
  the way a user does.
- **An agent loop has no natural end, so bound it from outside.** Given
  `browser_run_code_unsafe` and `browser_evaluate` the model stopped clicking
  and started writing JavaScript, then looped on its own syntax errors; both
  are now withheld in `ExploreController.WITHHELD`. Told to explore, it
  finished the flow, closed the browser and started again — `BudgetedTool`
  now refuses a second navigation to a page already visited, and caps total
  tool calls as a last resort. Asked for "YAML and nothing else" it wrote
  pages of prose first; the catalog is now a TOOL CALL (`CatalogSink`), so
  the shape is enforced by a schema and the YAML is rendered by our code.
  Even then, `submit_catalog` only *told* the model the task was over
  ("do not call any more tools") and it kept browsing — so the run never
  returned, and because the UI re-enables its button when the response
  arrives, the analyst's "Katalog Üret" stayed disabled forever. Two
  symptoms, one cause. `@Tool(returnDirect = true)` ends the loop for real:
  the result goes straight back to the caller instead of to the model, so
  there is no next turn to continue in. `BudgetedTool` refuses every call
  once `CatalogSink.isClosed()`, as the backstop. Each of these started as a
  prompt rule and only stopped when it became code.
- **Fields are noted as the agent goes, not all at the end.** `note_field`
  per field, `submit_catalog` once to close. One nested call asks too much of
  a smaller model, everything seen early has to survive forty turns of
  snapshots, and a run that dies leaves nothing. Noting keeps each call flat,
  each observation fresh, and a partial catalog recoverable.
- **Ask each question where its answer exists.** `verifiable` was an argument
  of `note_field` and came back empty every single run. Not carelessness: a
  field is noted while the FORM is being filled, and whether the application
  echoes its value back is knowable only after the submit, on a screen the
  agent has not opened yet — so the honest answer is always "no". The prompt
  explained the flag at length and could not help; no wording makes an
  unanswerable question answerable. It became its own tool,
  `note_confirmation`, called once on the confirmation screen with the labels
  printed there, and it reports back any label that was never noted — which is
  also how the missed confirmation-table rows surface. `ExploreController
  .problems()` now flags the one combination that proves the omission: a
  `success_message` (only readable past that screen) with no `verifiable`
  field anywhere.
- **A confirmation screen prints the label WITH its value, so match on the
  prefix.** Asked for the labels "exactly as printed there", the agent sent
  `"Bayi Kodu 93002"`, `"Toplam Tutar 222,00 TL"` — because that is genuinely
  one run of text on the page. `CatalogSink.keyOf` demanded equality, so all
  fifteen labels of a correctly walked flow matched nothing: no field became
  `verifiable`, and the draft then looked exactly like a run that had skipped
  the confirmation screen. The agent was right and the matcher was wrong.
  `longestPrefix` now accepts a reported text that BEGINS with a noted label,
  with two guards that are not hypothetical on this form: the next character
  may not be a letter (or `Fatura No` swallows `Fatura Numarası 955231`), and
  the longest match wins (or `Taksit Periyodu 2` lands on `Taksit Periyot
  Birimi`). Loose matching is safe here because it only sets a flag — being
  wrong costs one extra assertion, never a wrong value.
- **A tool's RETURN VALUE reaches where the prompt cannot.** The agent kept
  producing catalogs whose dropdowns had no options — four of them on the DBS
  form — and the system prompt already covered it at length, in capitals, with
  the reason AND the consequence spelled out ("TO LEARN THE OPTIONS YOU HAVE TO
  OPEN IT… a dropdown recorded with no options is a field a scenario cannot
  use"). Adding a firmer sentence was never going to work: the prompt is read
  ONCE, at the start of a forty-turn walk, and by field eleven it is far away.
  `note_field` now answers back — it records the field (never refuses, or a
  model that reads the complaint and moves on loses it entirely) and returns
  what to do about it, at the moment the mistake is made and on the screen where
  it can still be fixed. `note_confirmation` had already proved the shape by
  reporting unmatched labels back. The ordering to remember: **tool return value
  first, required argument second, prompt last** — and "add a line to the
  prompt" is almost never the answer to a rule the model is already ignoring.
- **An empty list means two things, so make the agent say which.** A dropdown
  noted with no options might never have been opened, or might have been opened
  and found genuinely empty — "Döviz Cinsi" has no options until "Bayi Kodu" is
  chosen. Written down, those were identical, so the draft could not tell an
  incomplete walk from a true fact about the screen. An empty list is now
  accepted only WITH a reason in `uncertain`, which already renders into the
  YAML as a `# emin degil:` comment. One consequence worth knowing: that comment
  cannot be read back: `problems()` re-parses the draft through `CatalogModel`
  and SnakeYAML drops comments, so the reason is reported from `CatalogSink`
  (`emptyChoiceReasons()`) exactly as `missedOnConfirmation()` already is.
  Anything a YAML comment carries is invisible to every later reader of that
  YAML.
- **An optional tool argument is an unanswered one.** `submit_catalog` took
  `entryRow`, `entryButton`, `entryTab` and `successMessage` as
  `required = false`, and they came back null run after run — including a run
  whose instruction listed the values literally, argument by argument
  ("entryRow = FORD DBS ODEMELERI"). The split in one such call is the whole
  story: **every required argument arrived** (app, flow, mainMenu, subMenu, and
  confirmedLabels with all fifteen labels), almost no optional one did. A model
  fills what the schema demands and skips what it merely permits, so the fix is
  not a firmer sentence in the prompt — it is deleting `required = false` and
  documenting `""` as the way to say "there was none". `confirmedLabels` had
  already been made required for exactly this reason and it holds. The damage
  was silent, which is why it took three runs to see: `CatalogSink.toYaml`
  writes a navigation key only when it is `present()`, so a dropped argument
  looks identical to a screen that never had one.
- **A new argument gets filled with the NEAREST observation, not the right
  one.** `shown_when` had no tool argument, so the agent could not report a
  conditional field at all. The first draft after it got one put
  `shown_when: {Bayi Kodu, 93002}` on "Döviz Cinsi" — whose options merely LOAD
  once a dealer is picked. The field is on the screen the whole time. Two
  different events look identical from the agent's seat (the page changes after
  a choice) and the prompt I wrote said "a box that was not on the screen",
  which an empty dropdown satisfies if you read it as "not usable". The
  discriminator has to be written down, and it is one question: **was the LABEL
  missing from the page, or was the label there with nothing inside it?**
  What makes this worth a lesson rather than a tweak is that the cost is
  ONE-SIDED. `VariationPlanner` excludes a conditional field from every
  variation that does not pick the trigger value, so a wrong `shown_when` left
  a REQUIRED field untouched in four scenarios out of five — while a missing
  one costs nothing for a field that is always present. Before this argument
  existed the agent said nothing about Döviz Cinsi; afterwards it said something
  harmful. So when a new argument's failure modes are asymmetric, ship the
  mechanical question WITH it: `problems()` now asks about any choice field
  carrying both a condition and a filled option list, which is that mistake's
  exact shape and does not fire on the real case (a genuinely revealed field is
  a plain box).
- **A tool that accepts an argument must also render it.** `submit_catalog` took
  `entryTab`, threaded it through `toYaml` — and the navigation block never
  emitted it. That is why the Banko catalog said nothing about the "Ödenecekler"
  tab the agent demonstrably clicked, and it read as "the agent forgot the tab"
  for a whole round of debugging. When a key is added to `SCHEMA.md`, follow it
  all the way to the emitted YAML; an argument that is accepted and discarded is
  worse than one that was never offered.
  **It then happened twice more, and the second time was worse than "accepted
  and discarded": `entry_choice` and `shown_when` were documented in SCHEMA.md
  with NO ARGUMENT AT ALL.** SCHEMA.md is pasted into the agent's system prompt
  verbatim, so the model read about both keys and had nowhere to put the answer
  — it very likely saw the DBS radio fork and the observation simply evaporated.
  That reads as "the AI forgot", which is the wrong lesson entirely. All three
  failures are invisible in the draft: a catalog with no `entry_choice` looks
  exactly like a screen that has no fork, and `problems()` cannot check what
  leaves no trace. Three repeats of one mistake is where a comment stops being
  enough, so `CatalogContractTest` now parses SCHEMA.md's own navigation block,
  calls `submit_catalog` with every argument filled by reflection, and fails
  naming any documented key that does not come out the other end. It is
  signature-agnostic on purpose: a contract test you must edit when the contract
  changes is one more thing to forget.
- **Two prompt rules that contradict each other are not settled by a third.**
  Batch drafts intermittently came back as a `Scenario Outline` with
  `select "<Fatura Tipi>" from "Fatura Tipi"` and a one-row `Examples` table —
  and the row said "Normal" on a scenario whose plan said "E-Fatura". It looked
  like the model ignoring the variation block. It was not: the system prompt's
  rule 5 says to use an Outline "when the intent asks for several input rows",
  which is RIGHT for one scenario and wrong for a batch, where
  `VariationPlanner` has already chosen the data and the user turn lists it
  literally. The model was obeying one half of a prompt that disagreed with
  itself — which is exactly why it happened sometimes and not others, and why
  it clustered on the combinations interesting enough to look tabular.
  Two fixes, and the order matters: **cancel the contradiction where the
  context is known** (the counter-rule lives in the variation block, the only
  place that knows this is a batch — rule 5 is in the shared system prompt and
  cannot be changed for one path), and then **back it with code**, because a
  rule is not made firmer by repetition. `OutlineFlattener` resolves the
  placeholders FROM THE PLAN — never from the model's Examples table, which is
  the invention — deletes the table, and renames the keyword. It is all-or-
  nothing: a placeholder the plan has no value for leaves the draft untouched,
  because flattening around it would strip the table that was feeding it. On
  the single-scenario path the variation is null and the class stands aside,
  since there rule 5 is simply correct. The shape to remember: before adding a
  rule, check whether an existing one already says the opposite.
- **You cannot ask a deterministic model for variety.** `temperature=0.3` is
  there so the same request gives the same scenario — a deliberate choice, and
  the right one for drafting Gherkin against a closed vocabulary. It also means
  "write five different scenarios" returns five near-identical files, and the
  only prompt-level fix is raising the temperature, which buys diversity by
  also buying invented labels and reworded steps. So `VariationPlanner` computes
  the differences in Java and the prompt carries the chosen values LITERALLY
  ("Fatura Tipi" = "E-Fatura"), which has the side benefit that every value came
  out of the catalog's own option list and is therefore already known to pass
  validation. Same shape as every other fix here: a prompt rule only holds once
  it becomes code. The planner resolves `shown_when` for the same reason — it is
  a condition with a right answer, not a judgement call.
- **Credentials are optional for ONE scenario and required for a BATCH.** The
  asymmetry looks like an oversight and is the opposite. `LoginStep.forApp`
  returns null for a blank cif, `LoginStep.apply` then leaves whatever login the
  model wrote, and that is a fair trade for a single draft: there is one login
  line and the analyst is looking straight at it. A batch is N SEPARATE model
  calls, so the same blank cif produces N independently invented customer
  numbers — measured, not feared: five drafts came back with five different
  cifs, one of them using the NGI step on an NGC screen and one with no login
  line at all. The analyst asked for one user exercising one screen five ways
  and would get five users and five hand-edits. `/suggest-many` therefore
  refuses a blank cif where `/suggest` accepts it. Note which half of the
  problem was already solved: the values the PLANNER varies are catalog fields,
  and a catalog carries no credentials by design (SCHEMA.md), so nothing was
  ever varying the login — the divergence came from NOT forcing it. "Held
  constant" and "never varied" are different guarantees, and only the first one
  needs a forced value.
- **Two valid steps can still be one bug.** A generated scenario carried
  `click "FORD DBS ODEMELERI"` on the line directly above its own
  `select row "FORD DBS ODEMELERI"`. Every check passed: both are real patterns,
  the value is a real catalogued one, the labels and types are right. But
  `click` resolves `getByRole(BUTTON|LINK)` and a grid cell is neither, so the
  run would spend the full timeout and die on a line that merely repeated the
  next one. Nothing could see it because nothing knew which catalogued value is
  a ROW — `CatalogModel` read `fields` and `success_message` and skipped
  `navigation` entirely. It now reads `entry_row`. The shape to remember: a
  check that only looks at one step at a time cannot see a wrong RELATIONSHIP
  between two correct ones, and the catalog usually knows which relationship was
  meant.
  **And the check alone was not the fix**, which is the second half of this
  lesson. A validator finding reaches the model as a REPAIR PROMPT, and a repair
  prompt is a request — the date verbs had already priced that: the model wrote
  the wrong verb, was shown the validator's own complaint, and wrote it again.
  So `EntryRow` now deletes the spurious `click` when the draft also selects the
  row properly, and rewrites it to `select row` when it does not; the check stays
  as the backstop for when the rewriter declines (no parsed catalog, or an
  executor not publishing `select row`). The test for which of the two a finding
  deserves: if the right answer is deterministic and readable from the catalog —
  one argument, same position, nothing to get wrong — write the rewriter. If it
  needs a human's judgement (a dropdown written with `enter` needs its arguments
  REVERSED), report it. Reporting a mechanically fixable mistake only moves the
  work to whoever reads the notes.
- **`getByRole` cannot see a hidden input, and `count()` does not wait.** The
  first `choose` implementation found its radio with `getByRole(RADIO)` and
  guarded with `count() == 0`. On the real screen it threw "0 options on the
  page", intermittently enough to look like a race, and it was two separate
  bugs — each one a lesson this file already carried:
  * `Locator.count()` answers about the DOM as it stands this instant; it is not
    an auto-waiting assertion. Called while the screen was still arriving it
    said zero and the step threw at once. `selectRow` has always called
    `awaitRadios()` first (waiting ATTACHED, not visible, precisely because
    these radios are hidden) and anything touching a radio must do the same.
  * A visually hidden element is excluded from the ACCESSIBILITY TREE, which is
    what `getByRole` queries. These radios are custom-styled — the drawn circle
    is the label, the input is hidden, which is the whole reason `clickRadio`
    clicks the label — so `getByRole` could never have found them however long
    it waited. The raw `input[type='radio']` locator is what works, and is what
    the grid picker next door had been using all along.
  The general shape: when a new step touches a control an existing step already
  drives, copy that step's locator strategy before inventing one. The existing
  one encodes failures already paid for.
  **Then it failed a third time, and copying was exactly what was wrong.**
  `awaitRadios()` was borrowed from `selectRow`, where the grid is already on
  screen; `choose` runs straight after the click that REPLACES the page. So the
  wait returned on the first radio to attach — not necessarily one of the
  fork's two, and one whose label had not rendered yet reads back as `''`. The
  run reported "1 secenek bulundu: ''" on a screen that has two, which looks
  like a locator bug and is not one. Waiting for ANY member of a class of
  elements is not waiting for the ONE the step is about: `choose` now waits for
  the option's own caption, which is also the thing a user sees, since these
  radios are drawn as their labels. The borrowed helper was right for the
  screen it came from and wrong for this one — so copy the STRATEGY, but check
  the assumption it rests on. And when a wait can fail for two opposite reasons
  (wrong screen versus wrong locator), make the failure say which: the message
  now reports whether the caption was on the page at all, because those two
  need opposite fixes and were indistinguishable.
- **A radio outside a grid had no step at all, and the gap was documented
  rather than noticed.** `CatalogDiscovery` has listed loose radios as
  UNRECOGNISED since it was written — "no vocabulary step drives a radio outside
  a grid" — and that stayed an abstract limitation until DBS Fatura Oluştur put
  one in front of its form ("Oluşturma Tipi": Bilgi Girişi / Dosya Yükleme).
  `select row` cannot reach it: it matches on `ancestor::td` then `ancestor::tr`,
  so a radio in a plain form section matches nothing and the step fails with "no
  row matching". `choose {string}` finds it by ROLE — Playwright computes a
  radio's accessible name from its label — then falls back to clicking the label
  when the input is custom-styled and hidden, the same two-step dance
  `clickRadio` already does inside a grid.
- **A fork between two screens is NAVIGATION, not a field.** The tempting model
  for "Oluşturma Tipi" was `type: radio` with two options, which would have let
  `VariationPlanner` vary it across a batch. That would have been a disaster of
  its own making: the other branch is a file-upload screen where NONE of the
  sixteen catalogued fields exist, so half the scenarios would have timed out on
  every field in turn. The catalog describes one screen; a second screen gets a
  second catalog ("one file per flow"), and the choice that reaches the first one
  belongs in `navigation.entry_choice` where nothing varies it. The test for
  which model is right: ask whether the catalogued fields still exist after the
  other option is chosen.
- **Never rewrite a draft into a step the deployment does not serve.** `DateVerb`
  turned `enter` into `enter date` the moment the orchestrator knew that step —
  but the orchestrator and the executors restart SEPARATELY, and on the run that
  found this the executor was still serving the old vocabulary. Every rewritten
  line came back "Tanımsız adım", and because an unknown step suppresses the
  catalog checks entirely (`everyStepIsKnown`), the draft also lost every OTHER
  finding it would have reported. A correction that outruns the deployment is
  strictly worse than no correction: it converts a reported problem into an
  unrunnable scenario AND hides the rest. `DateVerb.apply` now takes the live
  `Vocabulary` and rewrites only into patterns `Vocabulary.has` confirms, so an
  executor that is behind degrades to the previous behaviour instead of
  breaking. The general shape: anything that EDITS a scenario must ask what the
  executors actually publish, exactly as the validator already does.
- **The date verbs had to be applied, not requested.** The prompt was told twice
  — a type-to-verb table and four lines explaining why — that a `type: date`
  field takes `enter date` and `verify the {} has date {}`. The model wrote
  `enter "Başlangıç Vadesi" "+1 ay"` and
  `verify the "Başlangıç Vadesi" field equals "+1 ay"`, then did it again
  through a repair turn that quoted the validator's own complaint back at it.
  `DateVerb.apply` now rewrites both, from the catalog, exactly as
  `LoginStep.apply` rewrites the login line — and for the same reason: a rule
  asked for is a rule sometimes followed. Note the failure it prevents is not
  cosmetic: `field equals` compares exactly (it must, see the normalize lesson),
  so it reads a screen showing 08/11/2026 and hunts for the literal "+1 ay" —
  a field set perfectly failing its own assertion, minutes into a browser run.
  Deliberately narrow: only dates, and only the verb, because both pairs take
  their arguments in the same order and the rewrite has nothing to get wrong. A
  dropdown written with `enter` needs its arguments REVERSED, which is a riskier
  edit the validator reports and a human fixes.
- **A date decided at generation time rots; decide it at run time.**
  `VariationPlanner` used `LocalDate.now()` while WRITING the scenario, so a
  draft produced on 7 October carried "08/10/2026" for ever — correct that week,
  in the past a fortnight later, and refused by a form whose maturity date may
  not be behind today. Nothing about the scenario changed; it simply aged out.
  Tightening the ladder to near dates (the obvious fix) makes it worse: the
  nearer the date, the faster it expires. The answer is that the text should say
  what it MEANS — `enter date "Başlangıç Vadesi" "+3 gün"` — and the step
  resolves it when the browser runs. Note what this is NOT: a tool call for the
  model to fetch today's date. The date was never the model's to choose; Java
  already knew it. The question was only WHEN to ask, and the answer is at the
  run, in a human-written step. A literal `gg/aa/yyyy` still passes through
  `enter date` untouched, because an analyst who needs one specific day must be
  able to write it.
- **"One server, so serialise" is wrong about vLLM.** The scenario batch was
  sequential on the reasoning that five concurrent requests share one GPU and
  finish no sooner. Continuous batching is the thing vLLM exists for: concurrent
  requests are folded into the same forward pass, not queued. The model sharpens
  it — `Qwen3.6-35B-A3B` is a mixture of experts with 3B active parameters, so a
  single request is memory-bandwidth bound and leaves the GPU largely idle.
  Bounded concurrency (`llm.batch-concurrency`) rather than unbounded, because
  the deployment is shared with other teams and a batch of ten fired at once
  pushes everyone else behind it. The general shape: a performance claim about
  someone else's server is a guess until the server's own design is read.
- **Nothing counted tokens, so every cost answer was an estimate.**
  `.call().content()` is the short spelling of a Spring AI call and it discards
  the `ChatResponse` metadata — including the usage figures the server already
  returns. For as long as that was the only call shape here, "how much does an
  agent walk cost?" could only be answered by multiplying prompt sizes. 
  `LlmClient` now takes `.chatResponse()`, logs prompt/completion/total, and
  keeps a process counter a caller can difference across a batch.
- **One counter cannot index two different things.** `VariationPlanner` gave every
  field the scenario number, and that single index was wrong in two opposite
  ways at once. Choice fields each did `i % size`, which walks the DIAGONAL: two
  two-option dropdowns produce (A,X), (B,Y), (A,X)… and the combinations (A,Y)
  and (B,X) are never tested at all — half the coverage, silently. Data fields
  also used `i`, which made every field of the same type take the SAME value: on
  the DBS form all six text boxes got "TEST1" in scenario 1, and the two invoice
  numbers came out identical, which a uniqueness check refuses. Two indexes now:
  choice fields are digits of one mixed-radix counter, data fields are offset
  along their ladder by their own position. Dates are deliberately excluded from
  the offset — making them differ made them differ in ORDER, putting an invoice
  date six months after its own maturity date, and field relationships are not in
  the catalog to be read. Found by walking a real catalog by hand, not by a
  failing run; the table in `docs/COKLU-SENARYO-AKISI.md` is that walk.
- **A title that cannot tell two rows apart is worse than no title.** The card
  label was built from the first two catalogued options, and on a screen whose
  first two dropdowns have one option each that is the same string for every
  scenario — all five DBS cards read "Bayi Kodu = 93002 · Döviz Cinsi = TL" while
  the one field that actually differed was trimmed off the end. A label has to be
  built from what VARIES, which means asking the option list its size before
  using it.
- **An absent constraint is not an unbounded one.** `max_length` and friends come
  back null when the DOM did not say, and that is a different fact from "this box
  takes anything": the planner has to fall back to a short ladder AND report that
  it guessed. Writing `max_length: 0` to mean "none" would be read as a
  zero-character box, and a confident wrong bound is worse than an admitted
  missing one. Nor is `numeric` guessed from a label — "Fatura No" accepts
  `FTR-2026-0001` on this application.
- **Deleting a feature's CSS takes whatever else shared its class name.** The
  discovery panel was replaced by the batch panel, and its whole CSS block went
  with it — including `.discover-output`, which `#agentOutput` had also been
  using. The agent's answer box was then styled by `.search` alone: no gutter,
  no `min-height`, no monospace, and free to shrink. Nothing errored; CSS has no
  "undefined class", so a deleted rule is indistinguishable from one that never
  existed, and the panel simply looked wrong. The class name is what hid it —
  `discover-output` reads as belonging to the panel being deleted, and it did
  not. Two things follow: a shared rule should carry both names
  (`.yaml-output, .discover-output`) so its users are visible at the definition,
  and removing a feature is a good moment to run the cheap check that found
  this — every class used in the HTML and in the JS that builds HTML, grepped
  against the stylesheet. It takes seconds and it named the one live casualty
  out of 115 classes.
- **`[hidden]` loses to any author rule that sets `display`.** Author styles beat
  the user-agent stylesheet before specificity is even consulted, so
  `.agent-save { display: flex }` silently defeated `#agentSaveRow`'s `hidden`
  attribute and the "Kataloğu Kaydet" row was on screen from page load, offering
  to save a catalog that did not exist. `app.css` now declares
  `[hidden] { display: none !important }` once, so `el.x.hidden = true` means
  what it says. Worth remembering before adding another panel that toggles.
- **Hooks must close everything even when tracing fails.** `Playwright.create()`
  starts a node driver process per scenario; a throwing `tracing().stop()` that
  skipped the closes leaked that driver and its browser for the lifetime of the
  JVM — which in a long-lived executor means they accumulate run after run.

## Open issues

- **The "Seçiniz" placeholder still reaches drafts.** `selectableOptions`
  drops an option with no value, disabled or hidden; this app gives its
  placeholder a real value, so `Döviz Cinsi` came back as
  `options: ["Seçiniz"]` — the one thing a scenario must never choose. Run
  once with `UPTEST_DISCOVERY_DEBUG=true` and read the
  `nativeOptions=[value=text]` dump to learn how it IS marked, then write the
  rule from that. Do not match on the Turkish word.
- **`shown_when` is only as good as the walk that found it.** The DOM scan
  still cannot emit it — it reads the form before anything is filled, so a
  field a choice reveals is not there yet. `note_field` now takes it, but the
  agent can only report a condition the walk actually TRIGGERED: told to pick
  "Normal" for "Fatura Tipi", it never sees "E-Fatura Hash Kodu" and cannot
  know it exists. Making it probe each option was rejected deliberately — that
  means mutating the form, which breaks "the instruction is the complete list
  of what you may touch". So this went from "nobody can report it" to "reported
  when the walk reveals it", and a catalog still wants a human's eye on which
  fields are conditional.
- **The agent misses rows in confirmation tables.** It has twice reported
  "Orijinal Fatura Tutarı" absent from a screen where a screenshot shows it.
  A `browser_snapshot` depth limit is the untested suspicion. Harmless in this
  direction — a missing `verifiable` weakens a test, a wrong one fails a run —
  but it is why agent `verifiable` flags are cross-checked against a scan.
- Verifying text inside an invoice table.
- `mvn test` finds no features: they sit in a directory literally named
  `com.ykb.uplift.uptest` (dots), while `RunCucumberTest` selects the classpath
  resource `com/ykb/uplift/uptest` (slashes), which holds only the suite class.
  Harmless for the server path (the executor writes each scenario into its own
  run directory and passes the file to Cucumber directly), and harmless when a
  feature is passed explicitly with `-Dcucumber.features=<path>` (a filesystem
  path, which bypasses the classpath selector) — but plain `mvn test` and the
  IDE run gutter stay broken until the directory is renamed.

## UI: React, not the legacy vanilla files

**All new UI work goes to `uptest-orchestrator/src/main/frontend/`** — React 19
+ TypeScript, built by Vite. Not to `static/app.js`.

```
uptest-orchestrator/src/main/
├── frontend/                   ← ALL UI WORK HERE
│   ├── src/workspace/          editor · multi · side (agent, timeline, vocab)
│   ├── src/history/ hooks/ api/ lib/ ui/ shell/
│   └── npm run build  ──────►  ../resources/static/ui/
└── resources/static/
    ├── app.css                 ← SHARED. React imports it. See below.
    ├── app.js                  ← FROZEN legacy. Do not edit.
    └── index.html              ← FROZEN legacy. Do not edit.
```

Both UIs are served today — `/` was the vanilla one and `/ui` is React
(`orchestrator/api/UiRoutes.java`). The root now redirects to `/ui` so there is
one answer to "which screen am I looking at".

- **`app.css` is the exception and it is not legacy.** `frontend/src/main.tsx`
  imports it directly, so Vite bundles it into `/ui/assets/index-*.css`.
  React's own stylesheets are only ~79 lines (`root.css` + `theme.css`); every
  class the TSX uses — `discover-output`, `agent-save`, `s-line` — comes from
  `app.css`. A change there affects BOTH UIs, and it reaches `/ui` only after a
  rebuild.
- **Build:** `cd uptest-orchestrator/src/main/frontend && npm run build`
  (`tsc --noEmit && vite build`). Output lands in `resources/static/ui/` and is
  served from the jar. No CDN is contacted at runtime, by design.
- **`app.js` and `static/index.html` are frozen.** Not deleted — reverting
  stays cheap — but a fix made there is a fix made twice. Measured: two
  corrections written into `app.js` were already correct in `MultiPanel.tsx`,
  and the only one that mattered was in the shared `app.css`. Check the React
  source before touching anything under `static/`.

## Conventions

- Communication: Turkish or English. Direct and skeptical, action-oriented,
  copy-pasteable commands.
- Keep scenarios clean/readable (analysts edit them). Complexity → page object.
- Generic over hardcoded: derive locators from labels/attributes, not fixed ids.

## Sharing between NGC and NGI (common layer)

Goal: share what is genuinely application-independent; keep
application-specific logic separate.

- **Truly shared (in `common`, done):** the 18 generic steps listed above and
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

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
