# server — executor web service

The Spring Boot side of `cucumber-uplift-test`. Turns the Cucumber +
Playwright test suite into a service that runs scenarios on request, so
business analysts never install anything locally.

**This file covers `src/main/java/server/` only.** For step vocabulary, page
objects, Select2 handling and NGC/NGI rules, see the root `CLAUDE.md`.

## Why this exists

Analysts previously needed an IDE, Maven, the VDI and Playwright browsers to
run one scenario. The target is a single URL and nothing else.

## Target architecture

Two applications on one Linux server, one Docker network.

```
┌──────────────────────────────────────────────┐
│ LINUX SERVER   (e.g. dora.yapikredi.local)   │
│                                              │
│  executor-1   executor-2   executor-3        │──→ NGC / NGI test env
│    :9035        :9035        :9035           │    (Playwright target)
│      ▲            ▲            ▲             │
│      └────────────┴────────────┘             │
│                   │ HTTP                     │
│            ┌──────┴───────┐                  │
│            │ ORCHESTRATOR │  :8035           │←→ vLLM endpoint (Qwen)
│            │ (UI served   │                  │
│            │  from here)  │                  │
│            └──────┬───────┘                  │
└───────────────────┼──────────────────────────┘
                    │ :8035 published
              Analyst browser
```

- **orchestrator** — long-lived. Serves the UI, calls vLLM for scenario
  suggestions, validates Gherkin, dispatches runs to a free executor, stores
  and serves results. **Never runs a test itself.**
- **executor** — this code. Long-lived Spring Boot service wrapping Cucumber
  and Playwright. Takes one run at a time. Deployed as three replicas of the
  same image.

### Ports and exposure

| Component | Port | Exposure |
|---|---|---|
| orchestrator | 8035 | published to host; analysts reach it |
| executor-\* | 9035 | `expose` only, Docker network internal |

Analysts never touch an executor directly. If a request path implies the
browser calling an executor, that is a design error.

### Outbound dependencies

- orchestrator → vLLM endpoint (Qwen), for scenario suggestion
- orchestrator → a Playwright MCP server it starts itself (stdio, `.mcp.json`),
  which drives a real browser → NGC / NGI test environment
- executor → NGC / NGI test environment, via Playwright

All three cross the server boundary. Firewall/proxy access must exist before
anything works.

### The MCP agent bends the split — knowingly

"The orchestrator never opens a browser" is why the two applications exist at
all, and the exploration agent is an exception to it. The letter holds: the
orchestrator carries no Playwright Java driver, it spawns a Node process. The
spirit does not, and it is worth being clear about the cost:

- a long-lived browser now hangs off the ANALYST-FACING service, which is the
  exact failure the split was built to avoid
- there is one MCP browser for the whole application, so two explorations
  collide — `ExploreController.closeBrowser()` runs in a `finally`, and a
  second exploration started while the first is running can have its page
  closed underneath it
- **catalog production is split across both sides, by producer.** The MCP agent
  ("Ajan") never touches `ExecutorPool` — it drives its own browser from the
  orchestrator. The deterministic DOM scan ("Ekranı Keşfet") does the opposite:
  `CatalogDiscoveryService` builds a synthetic "login, navigate, discover"
  feature and dispatches it through `ExecutorPool` exactly like a normal run, so
  it consumes an executor slot and shows up in run history like any other run.
  Two producers, two paths, one output shape (`catalogs/SCHEMA.md`)

Acceptable while exploration is a manual, one-at-a-time activity. If it becomes
routine the agent belongs on the executor side, where browsers already live and
where restarting one process is the documented remedy.

## Current state — read this before assuming structure

**Both applications exist and are split into two Maven modules.** The split was
done before orchestrator code was written, exactly as planned below; the payoff
is visible in the jars: executor 236 MB (Playwright driver bundle),
orchestrator 19 MB.

```
cucumber-uplift-test/
├── CLAUDE.md              test side: vocabulary, page objects, Select2, NGC/NGI
├── PROJECT.md             goals and roadmap
├── VOCABULARY.md          human-facing view of the generated vocabulary
├── catalogs/              screen catalogs for LLM prompting
├── scenarios/             feature files
├── pom.xml                PARENT (packaging=pom, two modules)
│
├── uptest-executor/       runs tests; Cucumber + Playwright + Spring Web
│   └── src/main/java/
│       ├── com/ykb/uplift/uptest/playwright/   step definitions + page objects
│       │       common/ components/ ngc/ ngi/
│       │       discovery/  CatalogDiscovery, DiscoverySteps — the DOM-scan
│       │                   catalog producer, run as a synthetic feature
│       └── server/        THIS side
│           ├── controller/  CucumberController, CucumberStartRequest,
│           │                ArtifactController, ErrorResponse,
│           │                GlobalExceptionHandler
│           ├── exception/   ErrorCodes, StatusCodeException
│           ├── service/     CucumberService, CucumberRunner, CucumberInput,
│           │                CucumberRunnerResult, ArtifactService,
│           │                ErrorDetail, ResultStatus
│           ├── timeline/    StepTimelinePlugin, RunTimeline, TimelineRegistry,
│           │                StepState
│           ├── vocabulary/  VocabularyExtractor (build-time)
│           └── SpringBootApplication.java
│
└── uptest-orchestrator/   validates + dispatches; NEVER runs a test
    └── src/main/java/orchestrator/
        ├── api/          ScenarioController, RunController,
        │                 VocabularyController, DiscoveryController
        ├── catalog/      CatalogService, CatalogModel, CatalogDiscoveryService
        ├── executor/     ExecutorClient, ExecutorPool
        ├── explore/      ExploreController, CatalogSink, BudgetedTool, DateTool
        │                 — the MCP agent, the second catalog producer
        ├── llm/          SuggestService, PromptBuilder, LlmClient,
        │                 LlmController, LlmOutputCleaner, LoginStep
        ├── run/          RunService, RunStore, Redactor, RunReconciler
        ├── validation/   ScenarioValidator, ValidationResult, ValidationError
        └── vocabulary/   Vocabulary, VocabularyStep, VocabularyService
```

The orchestrator does **not** depend on uptest-executor as a Maven artifact —
that would drag the Playwright driver into an app that never opens a browser.
They talk over HTTP only.

The glue is `com.ykb.uplift.uptest.playwright` — the **package** the step
classes declare, which is what lands on the classpath. It is NOT the source
folder name: `--glue "playwright"` matched a package that never existed at
runtime, so every step came back undefined. `CucumberRunner.GLUE_PACKAGE` and
`RunCucumberTest`'s `GLUE_PROPERTY_NAME` must stay in sync.
Do not assume module boundaries that are not there.

### Orchestrator API (implemented)

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/scenarios/suggest` | LLM drafts Gherkin from catalog + intent, validates, repairs once |
| POST | `/api/v1/scenarios/suggest-many` | N drafts for one screen, each a different data combination; sequential, minutes — walked end to end in [`docs/COKLU-SENARYO-AKISI.md`](../../../../../docs/COKLU-SENARYO-AKISI.md) |
| POST | `/api/v1/scenarios/validate` | 200 always; body says valid + per-line errors |
| GET | `/api/v1/scenarios/catalogs` | which screens the model can be asked about |
| POST | `/api/v1/scenarios/catalogs` | store a catalog under `catalogs/<app>/<flow>.yaml` |
| POST | `/api/v1/runs` | 202 + runId; **422** invalid scenario; **503** all busy |
| GET | `/api/v1/runs` | history, newest first |
| GET | `/api/v1/runs/{id}` | merged orchestrator + executor status |
| PUT | `/api/v1/runs/{id}/stop` | cooperative cancel |
| GET | `/api/v1/runs/{id}/artifacts` | list: report, log, scenario, traces |
| GET | `/api/v1/runs/{id}/artifacts/**` | one file, proxied from its executor |
| GET | `/api/v1/vocabulary` | step catalog (for UI help + LLM prompt) |
| POST | `/api/v1/vocabulary/refresh` | re-read after an executor redeploy |
| GET | `/api/v1/executors` | which nodes are answering |
| POST | `/api/v1/discovery` | DOM scan: dispatches a synthetic feature, returns its runId |
| GET | `/api/v1/discovery/{id}` | the draft YAML once that run finished |
| GET | `/api/v1/llm/hello` | vLLM reachability check, no catalog, no validation |

`/api/v1/discovery` is the one catalog path that **consumes an executor slot**,
because it is a real run. It can therefore answer 503 for the same reason a
scenario can, and it shows up in run history.

The MCP agent adds three more, and they are **conditional**:

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/explore` | walk a flow, answer in prose |
| POST | `/api/v1/explore/catalog` | the same walk, answered as a catalog + `problems` |
| GET | `/api/v1/explore/tools` | what the MCP server offered, with argument schemas |

`ExploreController` is `@ConditionalOnProperty("uptest.explore.enabled")`.
Without `uptest.explore.enabled=true` these three are **not registered** and
every call answers a bare 404 — which looks like a wrong path or a broken model
and is neither. It cost a round of debugging once; check the property first.

**There is no stop endpoint, and no stop button.** A walk ends by finishing:
`closeBrowser()` runs in a `finally` on both POST endpoints, so the browser is
shut whichever way the request ends. A runaway walk is bounded from outside
instead — `uptest.explore.tool-budget` (250 calls) and `BudgetedTool`'s refusals
(a second navigation, an unmentioned field, a closed sink, a result over 12 000
characters). Cancelling one mid-flight is not possible today; the HTTP request
has to come back on its own.

### Run artifacts

Served by the executor (`/cucumber/artifacts/...`) and **proxied** by the
orchestrator, rather than the orchestrator reading `/var/qa/runs` directly.
Proxying assumes nothing about a shared filesystem, so it still works when the
orchestrator runs on another host — and analysts never touch an executor.

Two things here are load-bearing:

- **The artifact name is a query parameter on the executor hop**
  (`/cucumber/artifacts/{id}/file?name=traces/x.zip`), not a path segment. A
  trace name contains a `/`; encoding that into a path yields `%2F`, which
  Tomcat rejects with 400 before any handler runs. The orchestrator still
  exposes a friendly `.../artifacts/traces/x.zip` URL to the browser.
- **`ArtifactService.resolve` normalises and then checks containment**
  (`target.startsWith(runDir)`). Without it, `../..` hands out any file the
  process can read. Verified by calling the executor directly with
  `?name=../../pom.xml` — 404, so the check holds independently of whatever URL
  normalisation Spring does upstream.

The Cucumber HTML report embeds analyst-authored scenario text, so it is served
with `Content-Security-Policy: sandbox allow-scripts`: the report stays
interactive but runs in an opaque origin where it cannot reach the application.

`/runs` validates server-side before dispatching, even though the UI already
called `/validate` — the UI is not the only possible caller, and this is what
stops a typo from costing four minutes of browser time.

**It used to re-validate WEAKER, which quietly defeated the point.** The
catalog-aware checks — an unknown label, a verb that does not fit the field's
type, an option the screen never offered, an assertion of a value nobody
supplied — ran only inside `SuggestService`, at generation time, because only
it had the catalog. `/validate` and `/runs` were handed the vocabulary alone,
so a draft the generator had already flagged started anyway on grammar: a
model invented `Bayi Ünvanı` = "Ford Otomobil Sanayi ve Ticaret A.Ş." for a
`readonly` field the scenario never fills, and it cost fifteen seconds of
browser and a "Locator expected to have value". Editing a draft laundered its
mistakes.

Both now accept optional `app`, `flow` and `intent` and run the same checks the
generator runs. All three are optional, so an older caller still gets exactly
the grammar check it always got. **The intent is load-bearing, not
decoration**: asserting a `readonly` field IS legitimate when the analyst
supplied the expected value, and without the intent the server cannot tell an
earned assertion from an invented one — it would reject the good one.

The rule this leaves behind: **a re-check must be at least as strict as the
check it repeats.** A weaker one is worse than none, because it reads as a gate
and works as a rubber stamp.

### Step vocabulary — one generated source

`VocabularyExtractor` runs at `process-classes` in the executor and writes
`vocabulary.json` into the jar from the `@Given/@When/@Then` annotations
(they are `RUNTIME`-retained, so reflection is enough). The executor serves it
at `GET /cucumber/vocabulary`; the orchestrator fetches and caches it.

Fetched over HTTP rather than copied between modules at build time, so the
validator always describes the steps that are **actually deployed** and can
never accept a scenario the executor would reject as undefined.

Validation uses the real `io.cucumber:gherkin` parser and
`io.cucumber:cucumber-expressions`, not hand-rolled regex, so line numbers and
the accept/reject decision are the ones Cucumber itself would produce.

The generator also settles counting arguments, and keeps settling them: the
count moves whenever a step is added — **17** today, 16 before `check {string}`
arrived. Never trust a number typed into a document over `GET
/api/v1/vocabulary`.

**A hand count will say 19, and both numbers are right.** `VocabularyExtractor`
skips `com.ykb.uplift.uptest.playwright.discovery`, so `discover screen catalog`
and `fill discovered fields:` are Cucumber steps that the published vocabulary
deliberately omits: they read a screen rather than describe a test, and the
generator must never be able to emit one. Cucumber sees 19, the validator and
the LLM prompt see 17.

## Executor API — the contract with the orchestrator

Base path `/cucumber`. This is the only interface the orchestrator uses.

| Method | Path | Purpose |
|---|---|---|
| GET | `/test` | smoke check |
| GET | `/nodeStatus` | map of runId → status; how orchestrator finds a free slot |
| POST | `/start` | body `{ id, feature }` — `feature` is raw Gherkin text |
| PUT | `/stop/{id}` | request cancellation |
| GET | `/status/{id}` | current result for one run |

Design decisions that must not regress:

- **`/start` is asynchronous.** It returns immediately; progress comes from
  `/status`. A synchronous endpoint would hold an HTTP connection open for
  minutes and die to proxy timeouts.
- **Scenario text travels in the request body.** It is never read from
  `scenarios/` by name. Analysts edit scenarios in the UI; a filename-based
  contract makes their edits unrunnable.
- **`/status` must return step-level detail**, not just PASSED/FAILED. The UI
  shows a live step timeline:

```json
{
  "status": "RUNNING",
  "currentStepIndex": 3,
  "steps": [
    { "text": "Login ekranını aç", "status": "PASSED",  "durationMs": 2100 },
    { "text": "Tarih aralığı seç", "status": "RUNNING", "durationMs": null },
    { "text": "Sorgula",           "status": "PENDING", "durationMs": null }
  ]
}
```

### Step timeline (implemented)

`Main.run()` only yields an exit code, and a `Future` only has a value once the
run is over — neither can back a progress view. So:

- `StepTimelinePlugin` is a `ConcurrentEventListener` registered as
  `--plugin server.timeline.StepTimelinePlugin:<runId>`. Cucumber constructs
  plugins itself, so the run id is passed as the plugin argument (the text after
  the colon, same mechanism as `pretty:file.txt`) and the plugin finds its
  `RunTimeline` in `TimelineRegistry`. Do not "simplify" this into a
  ThreadLocal — that only works while execution stays on one thread.
- `TestCaseStarted` publishes the whole scenario as PENDING up front, so the UI
  draws the full timeline immediately rather than growing it line by line.
- Hooks are `TestStep`s too; only `PickleStepTestStep`s are Gherkin lines.
- `TimelineRegistry` entries are removed in a `finally`, and the final timeline
  is copied onto the result first — so /status still shows which step failed
  long after the run ended, without the registry growing.
- **`currentStepIndex` is the failed step once anything fails**, not the last
  step touched: Cucumber emits `TestStepStarted` for skipped steps too, so a
  naive "last touched" points past the failure at a step that never ran.

Events rather than parsing the pretty log: the log would re-derive state
Cucumber already publishes, and would break the first time the formatter
changed.

Verified end to end: three-step scenario polled every 2 s went
`PENDING PENDING PENDING` → `RUNNING PENDING PENDING` →
`FAILED(45154 ms, "Locator expected to be visible") SKIPPED SKIPPED`, with
`currentStepIndex` landing on 0.

## Concurrency model

**One run per executor process.** `simultaneouslyRun` is configurable, but for
deployed executors it is 1. Scale by adding replicas, not by raising it.

Reason: Cucumber carries static state and the Playwright `Browser` instance is
shared. Two concurrent runs in one JVM corrupt each other in ways that surface
as flaky tests, which destroys analyst trust in the platform.

### The stuck-run problem

A hung Playwright call **cannot be killed from Java**. The thread blocks in a
native call; `Thread.interrupt()` does not reach it, and no `try/catch` helps
because nothing is thrown — the code simply never returns. Real causes:
unexpected native dialogs, a never-completing `waitForLoadState`, renderer
crashes, OOM.

This is why executors are replicas rather than one service with a thread pool:

```
orchestrator timeout (10 min)
  → docker restart qa-executor-2
  → executor-1 and executor-3 keep serving
```

`/stop/{id}` covers the cooperative cases. Container restart is the backstop.
**Do not "simplify" this into a single executor with N threads.**

### Recycling

After ~50 runs an executor should report `RECYCLING` so the orchestrator stops
assigning work and restarts it when idle. Cheap insurance against slow leaks
in a long-lived JVM.

### Capacity — what happens when ten scenarios are submitted at once

**Capacity is the number of executor replicas**, because each one takes exactly
one run (`simultaneouslyRun=1`). Three replicas = three concurrent runs. Running
the orchestrator locally against a single executor = one.

**There is no queue.** Submit ten scenarios to a three-executor stack and three
start; the other seven are rejected immediately with **503**, and the analyst is
told everything is busy. Nothing is held for later.

| submitted at once | 3 executors | 1 executor (local) |
|---|---|---|
| 1st | runs | runs |
| 2nd, 3rd | run | 503 |
| 4th … 10th | 503 | 503 |

The chain, end to end:

```
POST /api/v1/runs
  → RunService.start           validates, mints the runId
  → ExecutorPool.dispatch      tries each executor in round-robin order
      → executor busy?         CucumberService answers 409
  → every node refused         Rejection.NO_FREE_EXECUTOR
  → 503
```

`ExecutorPool.dispatch` is deliberately **try-until-accepted**, not "ask
`/nodeStatus` for a free node, then start it": those two calls are a race, and a
busy executor answers 409 anyway, so losing the race just means trying the next
node.

Why no queue: a silently queued run cannot tell the analyst when it will start,
and a UI that says "accepted" for something that has not begun is worse than one
that says "try again". If queuing is ever wanted it belongs in the
**orchestrator** — the executor must keep refusing, because a queue inside a
process that gets restarted as a backstop would lose whatever it held.

### "Why not one service on one port?" — the four answers

This question keeps coming back, and it hides four different questions.

**1. One EXTERNAL port — you already have it.** Analysts reach 8035 and nothing
else; the 9035s are `expose`d inside the Docker network and refused even from
the host. That is the same shape as a Selenium Grid hub with nodes behind it,
and **the orchestrator IS the hub**: it dispatches a run, records which node
took it (`TEST_RUN.executor`), and routes every later status and artifact
request back to that node with `pool.byName`. The ports in the config are
hub-to-node wiring, not exposure.

**2. One process with N threads — no, and the industry agrees.** Playwright's
own test runner executes parallel work as separate OS PROCESSES, not threads,
for the same reason found here: a hung run must be killable without taking the
healthy ones with it. A hung Playwright call blocks in native code, so the only
unit of recovery is the process. With N threads in one JVM, each hang
permanently consumes a worker and the only fix is a restart that kills every
run in flight. There is also the Cucumber static state and the shared
`Browser`, which corrupt concurrent runs in one JVM into flaky tests.

**3. One INTERNAL address with N replicas — still blocked, but by a different
thing now.** `deploy.replicas: 3` behind one service name would let DNS spread
the load, but the orchestrator could then no longer reach a SPECIFIC replica —
and it has to, because a run's live step timeline sits in that process's memory
(`TimelineRegistry`). Move run state into shared storage and the question
disappears: any replica can answer for any run, and artifacts are already on a
shared volume.

This used to be gated on the `TEST_RUN` table existing. It exists now — so the
gate moved: **it is gated on `TEST_RUN` not being a single-writer H2 file.** H2
in file mode takes an exclusive lock, so a second orchestrator process fails to
start rather than corrupting anything (the good failure), and `AUTO_SERVER=TRUE`
is deliberately absent because it exists precisely to allow the thing that must
never happen. Scaling the orchestrator horizontally means moving to Postgres
first; the column types were kept portable for exactly that move.

**4. One service, N runs, REMOTE browsers — the real answer if scale demands
it.** Every objection above traces to one fact: a local Playwright call cannot
be killed from Java. Connect to a browser running elsewhere instead
(`BrowserType.connect`) and that changes — kill the remote browser, the
websocket drops, the Java thread comes back with an exception. The kill switch
that does not exist today appears, and one service on one port can then hold
many runs. The cost is a browser grid to operate, plus reworking whatever in
`Hooks`/`TestContext` assumes one run per JVM.

At today's scale (three parallel runs) the replica model is cheaper than any of
these. The JVM is not what costs: a container is budgeted 2 GB of which Chromium
peaks around 1.5 GB, and three parallel runs need three browsers under every one
of these designs. If the target ever becomes twenty parallel runs, the order is
4 after 3 — persistence first, then remote browsers.

## Isolation rules

- New `BrowserContext` per run, closed in `finally`. A leaked context carries
  cookies and session into the next scenario.
- Per-run working directory, e.g. `/var/qa/runs/{runId}/`. Nothing writes to a
  fixed path.
- Feature file, logs, HTML report, screenshots and traces all live in that run
  directory, on a volume the orchestrator can also read, so results need no
  extra transfer hop.

## State — what is remembered, and where the database is

The orchestrator has a database as of 2026-10-06: **H2 in file mode**, driven by
Spring Data JPA, holding `TEST_RUN` and `TEST_RUN_STEP`. The executor has none
and never will. What survives a restart is now the run history plus what is on
disk.

| where | what it holds | after a restart |
|---|---|---|
| orchestrator **database** | `TEST_RUN`, `TEST_RUN_STEP` — the history the UI lists, with each run's step timeline | **kept** |
| orchestrator memory | `CatalogDiscoveryService.runs`, `VocabularyService.cached` | **gone** |
| executor memory | recent run results, pruned at `MAX_RETAINED_RUNS` | **gone** |
| executor disk | `{featuresFolder}/{runId}/` — feature, log, report, traces | kept |

The gap that closed: the files used to outlive the record that pointed at them.
A `TEST_RUN` row now outlives the process, so a report from last month is still
reachable from the history page.

The gap still open: **discovery runs are not persisted yet**, so a catalog
discovery that finishes after an orchestrator restart is still unrecoverable —
`GET /api/v1/discovery/{id}` misses the map and answers 404. `SCENARIO_DRAFT`
(what the model generated, in how many attempts) is likewise still discarded.
Both are designed and neither is written.

**The database belongs to the orchestrator. The executor never gets one.**

- The executor has to stay **disposable**. The documented remedy for a hung
  Playwright call is restarting the executor process; a process that may be
  killed at any moment must not be the one holding state anyone needs later.
  Its in-memory pool and `MAX_RETAINED_RUNS` pruning already say this out loud.
- The orchestrator already **owns the record**: it mints the `runId`
  (`RunService.start` → `UUID.randomUUID()`) and hands it to the executor.
- Only the orchestrator knows the analyst-facing facts — the scenario text, who
  submitted it, when, against which catalog. The executor only ever knows "a
  feature file ran, here is the outcome".
- Two databases would mean two rows for one run, and two rows can disagree.

**One database, one owner.** Do not give the executors a shared database: that
makes them stateful and destroys the property the whole replica design rests on
— that any executor can be restarted at any time without consequence.

Artifacts stay on disk. A table row holds the run's identity, status and a
reference; the report and traces do not go into the database, because traces
run to tens of megabytes each and are already served correctly through the
artifact proxy. For the same reason `TEST_RUN.last_status_json` stores the
executor's status payload with `output` nulled: the executor inlines the entire
run.log there once a run finishes, and that log is already an artifact.

**Credentials do not go in either.** `LoginStep.forApp` writes a real cif and
userid into the Gherkin, so they ride inside the feature text AND inside
`steps[].text` in the status payload. `Redactor` blanks the quoted arguments of
any step beginning with `login` on all three paths in — `TEST_RUN.feature`,
`last_status_json`, `TEST_RUN_STEP.step_text`. The consequence is deliberate: a
stored scenario cannot be re-run as-is, because re-running against a bank should
require re-entering who you are logging in as.

**What the schema is, and what it is not.** `ddl-auto=update` is the whole
strategy — no Flyway, no Liquibase, no SQL files. The entity classes ARE the
schema, so a `git diff` on one is the only migration review that will ever
happen. Know its limit before relying on it: `update` ADDS tables, columns and
indexes and NEVER drops, renames, widens or retypes. A column that materialises
at the wrong width stays wrong, and on a live database the only fix is an
export/import.

## Scenario lifecycle (orchestrator side, for context)

```
1. POST /api/v1/scenarios/suggest   → LLM drafts Gherkin from screen + intent
2. analyst edits in the browser
3. POST /api/v1/scenarios/validate  → synchronous, ~50 ms, no browser
4. POST /api/v1/runs                → 202, dispatched to a free executor
5. GET  /api/v1/runs/{id}           → polled, optionally pushed to UI via SSE
```

**Validation is not optional.** The LLM invents steps and analysts edit freely.
Without validation an unknown step is discovered only after a browser has
launched and minutes have burned. Validate against step patterns extracted
from `playwright/`, and return suggestions:

```json
{
  "valid": false,
  "errors": [{
    "line": 7,
    "step": "Tutar bilgisini giriniz",
    "message": "Undefined step",
    "suggestions": ["enter amount {string} {string}"]
  }]
}
```

Re-run validation inside `/runs` server-side — the UI is not the only possible
caller.

### Step vocabulary as a single source

Extract step patterns from `@Given/@When/@Then` annotations in `playwright/`
at build time and publish as JSON. That one artifact feeds both the validator
and the LLM prompt. `VOCABULARY.md` is the human-facing view of the same
thing; keeping a third hand-maintained copy guarantees drift.

## Live progress

The executor exposes polling only. The orchestrator polls `/status` every ~2s
and pushes to the browser over SSE. The executor stays ignorant of SSE.

Polling straight from the browser is an acceptable first version — a 2-second
lag is invisible next to a 4-minute UI test.

## Deployment (target)

```yaml
services:
  orchestrator:
    ports: ["8035:8035"]
    volumes:
      - /var/qa/runs:/var/qa/runs
      - /var/run/docker.sock:/var/run/docker.sock   # executor restart
    environment:
      EXECUTORS: "executor-1:9035,executor-2:9035,executor-3:9035"
    networks: [qa-net]

  executor-1:                 # -2, -3 identical
    container_name: qa-executor-1
    expose: ["9035"]
    shm_size: 1gb             # Chromium crashes without this
    mem_limit: 2g
    volumes: [/var/qa/runs:/var/qa/runs]
    environment:
      SIMULTANEOUSLYRUN: 1
    networks: [qa-net]
```

Sizing: Chromium peaks around 1.5 GB per container. Three replicas on 16 GB is
comfortable; beyond that, measure.

### Built and verified

`docker compose build && docker compose up -d` brings up one orchestrator and
three executors, all reporting healthy. Measured image sizes:
**executor 4.97 GB** (Playwright base + browsers + the 236 MB jar),
**orchestrator 444 MB**.

Verified on the running stack:

- executors answer only inside `qa-net`; `localhost:9035` from the host is
  refused, which is the `expose` vs `ports` distinction doing its job
- the orchestrator fetches the vocabulary from `executor-1:9035` by service name
- **dispatch really round-robins**: three simultaneous `POST /api/v1/runs` were
  accepted by executor-1, -2 and -3 respectively, and the fourth got 503
- artifacts land on the shared volume and are readable from the host

Two container-only bugs this shook out — both silent, both worth not
reintroducing:

- **`UPTEST_BROWSER_CHANNEL=""` was unreachable.** The env helper treated blank
  as "unset" and fell back to `msedge`, so containers hunted for an Edge that
  is not installed and every run died at browser launch. The channel now reads
  `System.getenv` directly: *unset* means the VDI default, *set-but-empty*
  means "no channel, use bundled Chromium". Do not route it back through the
  blank-means-default helper.
- **The Playwright base ships no `curl`.** The HEALTHCHECK needs it, so without
  the extra `apt-get install` every executor is marked unhealthy while serving
  perfectly well.

Also: if an image pull is interrupted by a full disk, layers can be left
**zero-length but recorded as complete** — `java` and `/etc/passwd` were both
0 bytes, and re-pulling did nothing because content is addressed by digest.
`docker rmi` then `docker system prune -f`, then pull again.

## Environment notes (corporate)

- **Browser**: `Hooks` reads three env vars so one jar serves both hosts.
  Defaults reproduce the VDI (headed system Edge), so existing IDE/`mvn test`
  runs are unchanged; the container must override all three:

  | var | VDI (default) | container |
  |---|---|---|
  | `UPTEST_HEADLESS` | `false` | `true` |
  | `UPTEST_BROWSER_CHANNEL` | `msedge` | *(empty — bundled Chromium)* |
  | `UPTEST_SLOWMO` | `50` | `0` |

  The VDI also needs `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`, because SSL
  inspection blocks the Chromium download there; the Playwright base image
  ships browsers, so the container does not.
- **Registry**: if `mcr.microsoft.com` is blocked, pull the Playwright image
  outside and push to the internal registry. Usually the longest lead item.
- **Corporate root CA** must be baked into the executor image, into both the
  OS trust store and the JVM cacerts, or Playwright cannot reach the test
  environment over HTTPS.
- **Proxy**: set `HTTP_PROXY` / `HTTPS_PROXY` / `NO_PROXY` as build args and
  runtime env. Test environment hosts belong in `NO_PROXY`.
- **Credentials** (CIF, user id, passwords) come from environment variables —
  never from files in the image, never from scenario text. `DO_NOT_COMMIT/`
  must not reach the executor image; verify `.dockerignore` and Maven resource
  filtering before the first build.

## Hard-won lessons (do not regress)

- **Write feature files as UTF-8 explicitly.** `new PrintWriter(file)` uses
  the platform default; `Senaryo: Müşteri sorgulama` corrupts on a
  differently-configured host. Use
  `Files.writeString(path, feature, StandardCharsets.UTF_8)`.
- **Always overwrite the feature file.** An `if (!file.exists())` guard means
  a re-run silently executes the *previous* scenario. Extremely expensive to
  diagnose.
- **Report paths are per run.** `html:target/cucumber-report.html` is fixed —
  concurrent runs overwrite each other and history is lost. Use
  `html:/var/qa/runs/{runId}/report.html`.
- **Docker volume mounts take host paths.** When the orchestrator shells out
  to Docker, `-v` is resolved by the host daemon, not by the orchestrator
  container's filesystem. Keep the host path in configuration.
- **`@Value` only works on Spring-managed beans.** A `static main` kept for
  local trial runs gets no injection; hardcoded developer paths inside such a
  method are harmless but must never leak into `call()`.
- **`/start` when the pool is full should return 409**, not queue silently.
  `nodeStatus` followed by `start` is a race.

## Conventions

- Communication: Turkish or English. Direct, skeptical, action-oriented,
  copy-pasteable commands.
- Diagnose by measuring, not guessing. Prove the bug, fix it, prove it's fixed.
- Generic over hardcoded, in server code as much as in page objects.
- Prefer boring and reproducible over clever. An intermittently broken run
  costs more analyst trust than a slow one costs patience.

## Open questions

- ~~Is `/stop/{id}` cooperative cancellation or a hard kill?~~ **Cooperative
  only** — `Future.cancel(true)`, whose `interrupt()` never reaches a thread
  blocked in a native Playwright call. Container restart stays the backstop.
- ~~Does `CucumberRunnerResult` carry step-level detail yet?~~ **Yes, done.**
  `StepTimelinePlugin` (a `ConcurrentEventListener`) writes into `RunTimeline`,
  which `/status` reads live from `TimelineRegistry` while the run is going and
  which is copied onto the finished result afterwards — so the UI polls one
  endpoint and one shape throughout. See "Step timeline" below.
- Multi-module split before or after orchestrator work starts?
- Discovery and draft persistence — `TEST_RUN`/`TEST_RUN_STEP` landed, but
  `CatalogDiscoveryService.runs` is still a map, so a catalog discovery that
  finishes after a restart is unrecoverable and its four minutes of browser time
  must be spent again. `SCENARIO_DRAFT` is likewise unwritten. Both are designed;
  see "State, and where the database is".
- Artifact retention policy; traces and screenshots grow fast.
