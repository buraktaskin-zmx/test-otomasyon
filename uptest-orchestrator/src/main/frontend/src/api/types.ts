/*
  The orchestrator's wire shapes, read from the Java source itself (not from
  app.js) and cross-checked twice: once via an exploration pass over every
  controller, once by opening RunController/ScenarioController/
  VocabularyController/DiscoveryController/LlmController/ExploreController and
  every record they return and reading the field order by eye. Where a plan
  document narrates the reasoning behind a shape, it is quoted below so the
  next edit here does not have to re-derive it.

  No Jackson customisation exists anywhere in the orchestrator (no
  @JsonProperty/@JsonInclude, no ObjectMapper config) -- so null fields are
  serialized as JSON null, not omitted. That is why so much here is `| null`
  rather than `?`: a field marked optional would let a caller skip a null
  check the server can actually hand it.

  THREE endpoints answer with a BARE ARRAY, not a wrapped object:
  GET /api/v1/runs, GET /api/v1/runs/{id}/artifacts, GET /api/v1/executors.
  RunController's own Javadoc is explicit about why: wrapping the run list in
  a Spring Page would make `.filter` undefined and the history pane would
  silently render "Kayıt yok" with nothing logged. client.ts's asArray()
  enforces this at runtime; these types say Array, never a wrapper object.
*/

/* ========================================================================
   Shared primitives
   ======================================================================== */

/** TestRunEntity.status is a plain varchar, not @Enumerated -- "so the day
 *  the executor adds a state, Hibernate does not throw on every old row." */
export type RunStatus = "RUNNING" | "DONE" | "FAILED" | "CANCELED" | "UNKNOWN";

/** StepState.Status, executor side. All six are reachable; Cucumber's own
 *  AMBIGUOUS and PENDING outcomes are folded into FAILED before this wire
 *  shape is built, so a step never arrives in either of those two states. */
export type StepStatus =
  | "PENDING"
  | "RUNNING"
  | "PASSED"
  | "FAILED"
  | "SKIPPED"
  | "UNDEFINED";

/** Discovery can additionally report this -- single-key body, see
 *  DiscoverStatus below. Never appears on a normal run. */
export type DiscoveryOnlyStatus = "UNREACHABLE";

/* ========================================================================
   Vocabulary -- GET /api/v1/vocabulary, POST /api/v1/vocabulary/refresh
   ======================================================================== */

/** VocabularyStep.java record, five components in this order. There is no
 *  `type` field anywhere on a step -- `parameters` (the placeholder types) is
 *  the closest thing, and `skeleton()` is a Java-side method, not a wire
 *  field (Jackson 3 serializes record components, not arbitrary accessors). */
export interface VocabularyStep {
  pattern: string;
  keyword: string;
  parameters: string[];
  className: string;
  method: string;
}

/** Three keys, not just {steps}. `source` is `String.valueOf(...)`, so a
 *  null source executor would arrive as the literal string "null", never
 *  JSON null. */
export interface VocabularyResponse {
  source: string;
  stepCount: number;
  steps: VocabularyStep[];
}

/** refresh() answers two keys only -- no `steps` array, unlike the GET. */
export interface VocabularyRefreshResponse {
  source: string;
  stepCount: number;
}

/* ========================================================================
   Executors -- GET /api/v1/executors
   ======================================================================== */

/** VocabularyController.describe(): exactly {name, reachable}. There is no
 *  url, busy, or lastSeen on the wire -- the executor's own /nodeStatus
 *  (busy-vs-free) is never proxied by the orchestrator. */
export interface ExecutorNode {
  name: string;
  reachable: boolean;
}

/* ========================================================================
   Validation -- POST /api/v1/scenarios/validate, embedded in POST /runs 422
   ======================================================================== */

/** ValidationError.java record. Field order on the wire is
 *  (line, step, message, suggestions) -- NOT (line, message, step, ...).
 *  `line` is a primitive `long`, so it is always present. `step` is the one
 *  nullable field (ValidationError.syntax/unusedOutline pass null).
 *  `suggestions` is never null, always at least []. */
export interface ValidationError {
  line: number;
  step: string | null;
  message: string;
  suggestions: string[];
}

/** ValidationResult.java record. */
export interface ValidationResult {
  valid: boolean;
  stepCount: number;
  errors: ValidationError[];
}

/* ========================================================================
   Runs -- POST/GET /api/v1/runs, GET /api/v1/runs/{id}, .../artifacts, /stop
   ======================================================================== */

/** The screen a scenario belongs to -- the app/flow/intent it was pinned to
 *  at GENERATION time, not whatever the catalog dropdown currently shows.
 *  Travels with every validate and every run; see lib/originScreen.ts for
 *  the one place this is read. Losing this distinction is what let an
 *  invented value survive validation and cost a real browser run. */
export interface Origin {
  app: string;
  flow: string;
  intent: string;
}

/** StepState's five getters, in Jackson's serialization order. `durationMs`
 *  is null while the step has not finished -- "the UI shows a spinner, not a
 *  0." `error` is only the throwable's first line. */
export interface RunStep {
  keyword: string;
  text: string;
  status: StepStatus;
  durationMs: number | null;
  error: string | null;
}

/** ErrorDetail -- either exitCode (from an exit-code failure) or type (from
 *  a caught Throwable) is set, never both; message may accompany either. */
export interface ErrorDetail {
  type: string | null;
  message: string | null;
  exitCode: number | null;
}

/** The executor's raw CucumberRunnerResult, round-tripped through
 *  last_status_json. `output` is ALWAYS null here -- RunStore.storable()
 *  nulls it deliberately before persisting; the full run.log is one click
 *  away as "Günlük" instead. `steps` here is the SAME array as the
 *  top-level `steps` on RunDetail. Do not rely on this object's own key
 *  order beyond what is typed; it is whatever the executor's Jackson emitted. */
export interface ExecutorResult {
  status: RunStatus | null;
  error: ErrorDetail | null;
  output: string | null;
  steps: RunStep[] | null;
  currentStepIndex: number | null;
}

/**
 * RunService.describe()'s exact output -- eight keys, this exact order,
 * pinned by RunServiceTest against a literal JSON string. Used VERBATIM for
 * both a GET /runs list element and a GET /runs/{id} detail response; they
 * are byte-identical in shape, so one type serves both.
 *
 * `currentStepIndex` is -1 (not null) once a payload exists and nothing is
 * running; it is null only when there is no executorResult at all yet. Both
 * mean "no step is current" -- treat them the same in the UI.
 */
export interface RunDetail {
  runId: string;
  name: string;
  executor: string;
  submittedAt: string;
  status: RunStatus | null;
  currentStepIndex: number | null;
  steps: RunStep[] | null;
  executorResult: ExecutorResult | null;
}

/** POST /api/v1/runs 202 body. Has `stepCount` -- POST /discovery's 202 does
 *  NOT (compare DiscoverStarted below); do not share one type for both. */
export interface RunStarted {
  runId: string;
  executor: string;
  stepCount: number;
}

/** PUT /api/v1/runs/{id}/stop 202 body. `requested` is always the literal
 *  string "stop". Cooperative cancellation only -- a hung Playwright call
 *  survives it; the UI copy must say "durdurma isteği gönderildi", not
 *  "durduruldu". */
export interface StopAccepted {
  runId: string;
  requested: "stop";
}

/** ArtifactService.Artifact(name, sizeBytes, contentType) -- three fields,
 *  not two. `sizeBytes` is a primitive long, -1 on an IOException reading
 *  the size (never null). `name` keeps `/` separators for a nested path like
 *  "traces/x.zip" -- encode each segment separately when building a URL,
 *  never the whole name, or Tomcat rejects the resulting %2F with 400. */
export interface Artifact {
  name: string;
  sizeBytes: number;
  contentType: string;
}

/* ========================================================================
   Scenarios -- catalogs, validate request, single + batch generation
   ======================================================================== */

export interface ValidateRequest extends Origin {
  feature: string;
}

export interface StartRunRequest extends Origin {
  feature: string;
}

/** ScenarioController.catalogs(): `root` is the server's absolute catalogs
 *  directory (Windows backslashes when the server is Windows) -- shown in
 *  the save row so "ngc/x" reads as "ngc/x.yaml → C:\...\catalogs". */
export interface CatalogsResponse {
  root: string;
  catalogs: string[];
}

export interface SaveCatalogRequest {
  app: string;
  flow: string;
  yaml: string;
}

export interface SaveCatalogResponse {
  catalog: string;
}

/** ScenarioController.SuggestRequest -- note the lowercase `userid`
 *  (contrast ExploreRequest.userId below, which has a capital I; the two
 *  endpoints really do disagree and it is not a typo to "fix"). */
export interface SuggestRequest extends Origin {
  cif: string;
  userid: string;
}

/** SuggestService.Suggestion record -- FIVE fields. `validation` (the nested
 *  ValidationResult, carrying the per-line errors when valid is false) is
 *  the one most easily dropped; it is not redundant with `valid`. */
export interface SuggestResponse {
  feature: string;
  valid: boolean;
  validation: ValidationResult;
  attempts: number;
  log: string[];
}

/** ScenarioController.SuggestManyRequest. `count` is boxed server-side so a
 *  missing value defaults to 5 rather than becoming a batch of zero; the
 *  client may omit it the same way. */
export interface SuggestManyRequest extends Origin {
  cif: string;
  userid: string;
  count?: number;
}

/**
 * One row of a batch response -- deliberately FLATTENED (not nested as
 * {..., suggestion: {...}}) so a draft card reads the same `feature`/
 * `valid`/`validation`/`attempts`/`log` fields a single /suggest response
 * has, plus the variation that tells this draft apart from its siblings.
 * Ten keys, this order, from ScenarioController.suggestMany().
 */
export interface DraftScenario {
  index: number;
  label: string;
  values: Record<string, string>;
  ticked: string[];
  excluded: string[];
  feature: string;
  valid: boolean;
  validation: ValidationResult;
  attempts: number;
  log: string[];
}

export interface SuggestManyResponse {
  count: number;
  tokens: number;
  notes: string[];
  scenarios: DraftScenario[];
}

/* ========================================================================
   Discovery -- POST/GET /api/v1/discovery (dead markup today; revived here)
   ======================================================================== */

export interface DiscoverStage {
  /** May be omitted/empty: "a screen that is only confirmed, not filled." */
  values?: Record<string, string>;
  button: string;
}

/** DiscoveryController.DiscoverRequestBody. Casing: lowercase `userid`
 *  (matches ScenarioController.SuggestRequest, NOT ExploreRequest.userId). */
export interface DiscoverRequest {
  app: string;
  cif: string;
  /** Required only when app is "NGC" (case-insensitive); the server 400s if
   *  missing for NGC and ignores it for NGI. */
  userid?: string;
  mainMenu: string;
  subMenu: string;
  entryRow?: string;
  entryButton?: string;
  stages?: DiscoverStage[];
}

/** 202 body -- NOTE: no `stepCount`, unlike POST /runs's RunStarted. */
export interface DiscoverStarted {
  runId: string;
  executor: string;
}

/**
 * GET /api/v1/discovery/{id} -- NOT RunDetail's shape. This is the
 * executor's raw CucumberRunnerResult (status/error/output/steps/
 * currentStepIndex) with no runId/name/submittedAt/executorResult wrapper.
 *
 * Two discovery-specific quirks neither of which applies to a normal run:
 * - `output` is NOT nulled here (unlike RunStore.storable for normal runs),
 *   so a finished discovery payload carries the ENTIRE run.log inline.
 * - `status` can be the single value "UNREACHABLE", in which case the body
 *   is `{status:"UNREACHABLE"}` ALONE -- no steps, no currentStepIndex, no
 *   error, no output. Check for this shape before reading anything else.
 *
 * Once status is terminal (DONE/FAILED/CANCELED), exactly ONE of
 * `catalogYaml`/`catalogError` is added by attachCatalog() -- never both,
 * never neither, on a terminal response.
 */
export interface DiscoverStatus {
  status: RunStatus | DiscoveryOnlyStatus;
  error?: ErrorDetail | null;
  /** NOT nulled on this endpoint, unlike RunDetail.executorResult.output. */
  output?: string | null;
  steps?: RunStep[];
  currentStepIndex?: number;
  /** Present only once status is terminal, and only when the draft was
   *  found: header(request) + the scanned fields, as YAML text. */
  catalogYaml?: string;
  /** Present only once status is terminal, and only when the draft was
   *  NOT found -- mutually exclusive with catalogYaml. */
  catalogError?: string;
}

/* ========================================================================
   Explore / Ajan -- POST /api/v1/explore[/catalog], GET /api/v1/explore/tools
   ======================================================================== */

/** ExploreController.Instruction record. Casing: `userId` has a CAPITAL I
 *  here -- contrast SuggestRequest.userid and DiscoverRequest.userid, both
 *  lowercase. This is a genuine inconsistency across the three endpoints,
 *  confirmed by reading all three records side by side; do not "fix" it by
 *  unifying the casing, since each name must match its own endpoint. Every
 *  field except `text` may be legitimately absent (a flow already logged in
 *  needs none of them). */
export interface ExploreRequest {
  text: string;
  url?: string;
  cif?: string;
  userId?: string;
  password?: string;
  smsCode?: string;
  mainMenu?: string;
}

/** POST /api/v1/explore 200 body -- ONE key. */
export interface ExploreAnswerResponse {
  answer: string;
}

/** POST /api/v1/explore/catalog 200 body -- TWO keys, neither optional:
 *  `catalog` is never null (yamlOnly() falls back to ""), `problems` may be
 *  an empty array but is never absent. */
export interface ExploreCatalogResponse {
  catalog: string;
  problems: string[];
}

export interface ExploreTool {
  name: string;
  description: string;
  /** A JSON-encoded SCHEMA AS A STRING (String.valueOf of the real schema
   *  object) -- JSON.parse it client-side if the shape is ever needed. */
  inputSchema: string;
}

/** GET /api/v1/explore/tools. An empty `tools` array (count: 0) means the
 *  model was never given a browser -- the single diagnostic that tells
 *  apart "the model is bad at this" from "MCP never started". */
export interface ExploreToolsResponse {
  count: number;
  tools: ExploreTool[];
}

/* ========================================================================
   LLM health -- GET /api/v1/llm/hello
   ======================================================================== */

/** Always 200; the two shapes are told apart by which keys are present.
 *  `prompt` is always echoed back. Success has `answer` and no `error`;
 *  failure has `error`+`rootCause` and NO `answer` key at all -- check for
 *  `answer` before trusting a 200 here. */
export interface LlmHelloResponse {
  prompt: string;
  answer?: string;
  error?: string;
  rootCause?: string;
}

/* ========================================================================
   Error bodies -- at least five distinct shapes across the API, plus the
   bare Boot /error body for anything unhandled and the bare ValidationResult
   for a 422. All fields optional here on purpose: a caller reads whichever
   of these happen to be present rather than asserting a single fixed shape.
   ======================================================================== */

export interface ErrorBody {
  error?: string;
  /** Present on a 503 that wants to say what to do about it, e.g. "Retry
   *  shortly; each executor runs one scenario at a time." */
  hint?: string;
  /** Present on "no catalog for app/flow" -- the list of catalogs that DO
   *  exist, so the UI can suggest one instead of a bare 400. */
  available?: string[];
  /** Present on a 502/500 wrapping a caught exception. */
  detail?: string;
  /** Present only on ScenarioController's 502 (suggest/suggest-many) -- the
   *  fully-qualified root cause, several causes deeper than `detail`.
   *  ExploreController's 502 has `error`+`detail` only, no `rootCause`. */
  rootCause?: string;
  /** Present only on SuggestManyRequest's count validation 400. */
  count?: number;
}
