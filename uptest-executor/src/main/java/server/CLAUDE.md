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
- executor → NGC / NGI test environment, via Playwright

Both cross the server boundary. Firewall/proxy access for both must exist
before anything works.

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
│       └── server/        THIS side
│           ├── controller/  CucumberController, CucumberStartRequest,
│           │                ErrorResponse, GlobalExceptionHandler
│           ├── exception/   ErrorCodes, StatusCodeException
│           ├── service/     CucumberService, CucumberRunner, CucumberInput,
│           │                CucumberRunnerResult, ErrorDetail, ResultStatus
│           ├── vocabulary/  VocabularyExtractor (build-time)
│           └── SpringBootApplication.java
│
└── uptest-orchestrator/   validates + dispatches; NEVER runs a test
    └── src/main/java/orchestrator/
        ├── api/          ScenarioController, RunController, VocabularyController
        ├── executor/     ExecutorClient, ExecutorPool
        ├── run/          RunService, RunRecord
        ├── validation/   ScenarioValidator, ValidationResult, ValidationError
        └── vocabulary/   Vocabulary, VocabularyStep, VocabularyService
```

The orchestrator does **not** depend on uptest-executor as a Maven artifact —
that would drag the Playwright driver into an app that never opens a browser.
They talk over HTTP only.

Not yet written: the vLLM `/suggest` endpoint and the analyst UI.

The glue is `com.ykb.uplift.uptest.playwright` — the **package** the step
classes declare, which is what lands on the classpath. It is NOT the source
folder name: `--glue "playwright"` matched a package that never existed at
runtime, so every step came back undefined. `CucumberRunner.GLUE_PACKAGE` and
`RunCucumberTest`'s `GLUE_PROPERTY_NAME` must stay in sync.
Do not assume module boundaries that are not there.

### Orchestrator API (implemented)

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/scenarios/validate` | 200 always; body says valid + per-line errors |
| POST | `/api/v1/runs` | 202 + runId; **422** invalid scenario; **503** all busy |
| GET | `/api/v1/runs` | history, newest first |
| GET | `/api/v1/runs/{id}` | merged orchestrator + executor status |
| PUT | `/api/v1/runs/{id}/stop` | cooperative cancel |
| GET | `/api/v1/vocabulary` | step catalog (for UI help + LLM prompt) |
| POST | `/api/v1/vocabulary/refresh` | re-read after an executor redeploy |
| GET | `/api/v1/runs/{id}/artifacts` | list: report, log, scenario, traces |
| GET | `/api/v1/runs/{id}/artifacts/**` | one file, proxied from its executor |
| GET | `/api/v1/executors` | which nodes are answering |

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

The generator also settles counting arguments: there are **16** steps, not the
17 a hand count produced.

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

## Isolation rules

- New `BrowserContext` per run, closed in `finally`. A leaked context carries
  cookies and session into the next scenario.
- Per-run working directory, e.g. `/var/qa/runs/{runId}/`. Nothing writes to a
  fixed path.
- Feature file, logs, HTML report, screenshots and traces all live in that run
  directory, on a volume the orchestrator can also read, so results need no
  extra transfer hop.

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
- Run history persistence — an in-memory map loses everything on restart. A
  `TEST_RUN` table is likely needed before analysts rely on the platform.
- Artifact retention policy; traces and screenshots grow fast.