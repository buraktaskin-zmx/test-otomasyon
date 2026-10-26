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
  /** Appended for the chat, which turns a finding into a question and has to
   *  know which sort it is without parsing the Turkish prose in `message`.
   *  Serialised as the enum's name. */
  kind: ValidationErrorKind;
  /** The field label this is about, or null when it is not about one. */
  subject: string | null;
}

/** ValidationError.Kind — keep in step with the Java enum. */
export type ValidationErrorKind =
  | "SYNTAX"
  | "UNDEFINED_STEP"
  | "UNKNOWN_LABEL"
  | "WRONG_VERB"
  | "UNEARNED_ASSERTION"
  | "PARAPHRASED_MESSAGE"
  | "UNCATALOGUED_MESSAGE"
  | "ROW_NOT_CLICKABLE"
  | "UNUSED_OUTLINE"
  | "NOTHING_ASSERTED"
  | "UNKNOWN_OPTION";

/** ValidationResult.java record. */
export interface ValidationResult {
  valid: boolean;
  stepCount: number;
  errors: ValidationError[];
}

/* ========================================================================
   Chat -- POST /api/v1/chat, POST /{id}/reply, GET /{id}, DELETE /{id}

   Writing a scenario by talking about it: the generator with its repair turn
   pointed at a person. A validation finding arrives as a QUESTION with the
   legal answers as buttons, instead of going back to the model to guess at.
   ======================================================================== */

/** ChatQuestions.ChatOption record. `valuePrompt` is null unless
 *  `needsValue`, in which case it is the label for the input to show. */
export interface ChatOption {
  id: string;
  label: string;
  needsValue: boolean;
  valuePrompt: string | null;
}

/** ChatQuestions.ChatQuestion record. `options` is never null, but IS empty
 *  for findings whose legal answers are not known -- there `allowsFreeText`
 *  carries the whole interaction. */
export interface ChatQuestion {
  text: string;
  options: ChatOption[];
  allowsFreeText: boolean;
}

/** ChatSession.Message record. */
export interface ChatMessage {
  role: "ai" | "you";
  text: string;
}

/** ChatController.ChatTurn record. `feature` and `validation` are null until
 *  the first generation; `complete` means valid with nothing pending, i.e.
 *  ready to run or to hand to the editor. */
export interface ChatTurn {
  sessionId: string;
  messages: ChatMessage[];
  question: ChatQuestion | null;
  /** The opening text plus every answer. Use this as the Origin's `intent`
   *  when handing the draft to the editor: re-validation looks for expected
   *  values in the intent, and a value supplied mid-conversation lives here
   *  and nowhere else. */
  request: string;
  /** The product this conversation is held under, or null. Put it in the
   *  Origin alongside `request` when handing the draft over, for the same
   *  reason: the editor re-validates, and the accepted message set comes
   *  from here. */
  product: string | null;
  feature: string | null;
  validation: ValidationResult | null;
  complete: boolean;
}

/** ChatController.StartRequest. `userid` is required for NGC, refused
 *  server-side when a cif is given without it -- same rule as /suggest. */
export interface ChatStartRequest {
  app: string;
  flow: string;
  cif: string;
  userid: string;
  text: string;
  product?: string;
  ruleIds?: string[];
}

/* ========================================================================
   Products -- GET /api/v1/products, GET /api/v1/products/{app}/{product}

   What the application must DO, as opposed to what a screen HAS. A catalog
   is discovered from a live screen and so can never carry an oracle; these
   are written by a person. See products/SCHEMA.md.
   ======================================================================== */

/** One rule as ProductController.rule() emits it. `confirmed` is computed
 *  server-side from `status`, and anything unrecognised -- including a
 *  missing status -- is NOT confirmed: a guess must never be promoted to a
 *  fact by omission. */
export interface ProductRule {
  id: string;
  title: string;
  status: string;
  confirmed: boolean;
  description: string;
  fields: string[];
  /** The exact sentences the application prints. Compared character for
   *  character after folding, so these are copied, never paraphrased. */
  messages: string[];
  source: string;
}

/** A row of GET /api/v1/products -- enough for a picker and the gap report,
 *  deliberately WITHOUT the rules themselves (a product may carry fifty and
 *  three pickers load this list). */
export interface ProductSummary {
  app: string;
  product: string;
  name: string;
  /** The business area above the product in the tree ("Ödemeler"), or "".
   *  NOT derived from `app`: NGC and NGI are applications, and the business
   *  areas cut across them. */
  category: string;
  description: string;
  /** How many .feature files are registered, counted without parsing them. */
  scenarioCount: number;
  flows: string[];
  /** Flows this product claims that nobody has catalogued yet. Not an
   *  error -- a product is specified before its screens are walked, so this
   *  is the work queue. */
  missingCatalogs: string[];
  ruleCount: number;
  assumptionCount: number;
  messageCount: number;
  hasOpenQuestions: boolean;
  /** Rule ids that appeared twice; the first won. Reported rather than
   *  silently resolved, or the file would disagree with the screen. */
  duplicateIds: string[];
}

/* ========================================================================
   Test users -- GET/POST /api/v1/users, DELETE /api/v1/users/{id}

   Saved TEST-ENVIRONMENT logins. The password really is returned to the
   browser: the four generation panels have to put one into a request, and
   withholding it would only force the analyst to retype what they just saved.
   ======================================================================== */

export interface TestUser {
  /** Derived server-side from the name, or the cif when there is none. */
  id: string;
  /** Optional. A login is identified by its cif, so a label is not required. */
  name: string;
  /** What a picker shows: the name, falling back to the cif. */
  label: string;
  cif: string;
  /** NGC needs one, NGI does not -- empty is legitimate. */
  userid: string;
  password: string;
  role: string;
  note: string;
}

export interface TestUsersResponse {
  /** Absolute path of the YAML the server reads and writes. */
  file: string;
  users: TestUser[];
}

/** `id` empty = add, set = replace the row with that id. */
export interface SaveUserRequest {
  id: string;
  name: string;
  cif: string;
  userid: string;
  password: string;
  role: string;
  note: string;
}

export interface ProductsResponse {
  root: string;
  products: ProductSummary[];
}

/** A rule chip on a scenario row. `known: false` means the .feature carries
 *  a @R-... tag naming a rule the product no longer has -- reported rather
 *  than dropped, because a scenario pointing at a deleted rule LOOKS bound
 *  and is not. */
export interface ScenarioRuleRef {
  id: string;
  known: boolean;
  title: string;
  confirmed: boolean;
  /** The rule reads differently now than when this scenario was last
   *  reviewed. NOT set for a rule that was deleted -- `known: false` already
   *  says that, and two complaints about one fact is one too many. */
  stale: boolean;
}

/** Can this scenario run today?
 *
 *  READY      -- the data exists or can be produced.
 *  SEED       -- the test creates it first; a precondition, not a blocker.
 *  MISSING    -- nobody has it yet. Somebody must supply it.
 *  BLOCKED    -- an open question forbids it. Somebody must ANSWER first.
 *  UNDECLARED -- the file carries no @needs tag at all, so nobody knows.
 *
 *  The last two are the ones worth separating. MISSING is a request to whoever
 *  provisions test users; BLOCKED is a request to an analyst, and until it is
 *  answered the scenario cannot even be WRITTEN correctly, because what it
 *  should assert is unknown. UNDECLARED is neither -- it is an unfinished
 *  scenario, and showing it green would queue a run that cannot survive. */
export type Readiness = "READY" | "SEED" | "MISSING" | "BLOCKED" | "UNDECLARED";

/** One @needs:ALIAS, resolved against the product's test-data.yaml.
 *  `blockedBy` carries an open question's id (e.g. "S-18") and outranks
 *  `how`: a synthetic TCKN is producible and still unusable while nobody
 *  knows whether the application accepts one. */
export interface ScenarioNeed {
  id: string;
  known: boolean;
  /** The need in a few words ("Geçerli TCKN") -- what the column prints.
   *  The id is for machines; a reader scanning fifty rows wants the need. */
  label: string;
  readiness: Readiness;
  how: string;
  description: string;
  blockedBy: string;
  /** test-data.yaml defines this alias differently than it did at review time. */
  stale: boolean;
}

/** One row of the Kayıtlı Senaryolar table: a scenario in the database, with
 *  its rules and needs resolved against the product's YAML. */
export interface RegisteredScenario {
  /** "<screen>/<file name>" -- the row's identity, and the file an export writes. */
  path: string;
  /** The screen -- the first path segment, i.e. the folder it sits in. */
  flow: string;
  title: string;
  description: string;
  stepCount: number;
  /** Non-null when the file would not parse as Gherkin. Listed anyway: a
   *  scenario that silently vanishes is worse than one shown as broken. */
  problem: string | null;
  /** Tags that are neither a rule nor a need: @mutating, @regression. */
  tags: string[];
  rules: ScenarioRuleRef[];
  needs: ScenarioNeed[];
  /** The file says @needs:none -- "checked, there are none". NOT the same
   *  as an empty `needs` with no tag, which is UNDECLARED. */
  needsNone: boolean;
  /** The worst of `needs` -- a row is as ready as its least ready need. */
  readiness: Readiness;
  /** The scenario's own "Ön koşul" items, as written in it. */
  preconditions: string[];
  /** Any rule or alias this scenario names has moved since it was reviewed. */
  stale: boolean;
}

/** The natural-language half of a scenario, as the database keeps it. */
export interface ScenarioDocFields {
  title: string;
  rules: string[];
  preconditions: string[];
  steps: string[];
  expected: string[];
}

/** One field of a rule or alias that reads differently now. */
export interface SourceFieldChange {
  /** "description", "status", "messages", or "deger:<liste adı>". */
  field: string;
  before: string;
  after: string;
}

/** A line of the scenario still carrying a value the rule has dropped. */
export interface SourceMention {
  value: string;
  /** "Ön koşul" | "Adımlar" | "Beklenen" | "Gherkin". */
  where: string;
  line: string;
}

/** A rule or alias that moved since the scenario was last reviewed.
 *
 *  Reported, never applied. The same difference means two opposite things:
 *  the application changed and the rule caught up (the scenario should
 *  follow), or the rule was written down wrong and has been corrected (the
 *  scenario should change AND the run should stay red, over a defect that is
 *  still there). Only a person can tell which. */
export interface SourceChange {
  /** "RULE" | "ALIAS". */
  kind: string;
  id: string;
  /** "CHANGED" | "GONE". */
  state: string;
  title: string;
  fields: SourceFieldChange[];
  /** Values the rule listed then and does not list now. */
  removed: string[];
  added: string[];
  mentions: SourceMention[];
}

/** GET .../scenario?path= -- the row plus the scenario both ways. */
export interface ScenarioDetail extends RegisteredScenario {
  /** The whole .feature (tags, description, steps) -- what a run is given. */
  feature: string;
  /** The Gherkin half alone: Feature, Scenario and steps. */
  gherkin: string;
  doc: ScenarioDocFields;
  /** Sent back with an edit; a stale one is refused with 409. */
  version: number;
  changes: SourceChange[];
}

/** PUT .../scenario?path= -- only the fields sent are changed. */
export interface ScenarioEdit {
  gherkin?: string;
  title?: string;
  rules?: string[];
  preconditions?: string[];
  steps?: string[];
  expected?: string[];
  version?: number;
  /** The analyst has read what the rules say now: re-stamp and clear the
   *  badge. Left out, the stamp is untouched -- a save made for some other
   *  reason must not clear a badge nobody looked at. */
  reviewed?: boolean;
}

/** A screen under a product. `declared: false` means scenarios sit in a
 *  folder the product.yaml never listed -- a screen somebody forgot to
 *  declare, which is only visible because both sources are merged. */
export interface ProductScreen {
  flow: string;
  /** product.yaml `screen_names:`, falling back to `flow`. */
  name: string;
  catalog: string | null;
  declared: boolean;
  scenarioCount: number;
}

export interface ProductRegistry extends ProductSummary {
  screens: ProductScreen[];
  scenarios: RegisteredScenario[];
}

/** GET /api/v1/products/{app}/{product} -- the summary plus everything a
 *  person needs to read. `openQuestions` is shown here and NEVER sent to the
 *  model: its purpose is to mark where an assumption would be invented, and
 *  handing it to something that invents plausible text defeats it. */
export interface ProductDetail extends ProductSummary {
  rules: ProductRule[];
  stateMachine: string;
  roles: string;
  openQuestions: string;
}

/** ChatController.ReplyRequest. Either a button (`optionId`, plus `value`
 *  when that button asked for one) or something typed (`text`). */
export interface ChatReplyRequest {
  optionId?: string;
  value?: string;
  text?: string;
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
  /** The product whose rules the draft was written against, or "" for none.
   *  Travels for the same reason `intent` does: the validator decides which
   *  asserted sentences count as catalogued, and a scenario that legitimately
   *  asserts a product's error message would be flagged the moment it reached
   *  the editor or the run gate without this. */
  product?: string;
}

/** The saved login a run is started UNDER. Deliberately NOT part of `Origin`:
 *  Origin is what a scenario WAS WRITTEN against and is persisted into
 *  TEST_RUN's own columns, while a login is who happens to be running it this
 *  time. Folding the two together would put a cif on the one object in this
 *  file that gets stored, which is exactly what `Redactor` exists to prevent.
 *
 *  The orchestrator turns this into the Gherkin login line (NGC takes both
 *  halves, NGI the cif alone), so nothing here decides which step to write. */
export interface RunLogin {
  cif: string;
  userid: string;
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
  /** The rule ids the analyst ticked. Shapes the PROMPT only -- the validator
   *  sees the product's whole message set regardless, which is why this is
   *  NOT part of Origin. */
  ruleIds?: string[];
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
  ruleIds?: string[];
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
  /** The scenario's own BEKLENEN lines. They are where the rendered THEN
   *  comes from: the agent never asserts, so an expectation derived from the
   *  walk would say the application is right because the application said so.
   *  The tape is used only to check the label was on the screen the walk
   *  ended on, and to report a value that disagrees. */
  expected?: string[];
}

/** POST /api/v1/explore 200 body.
 *
 *  `gherkin` is the walk itself, rendered back into the step vocabulary from
 *  the recorded tape. A PROPOSAL and never a save: an agent that skipped a
 *  step produces a scenario that skips it too, which is exactly what one
 *  recorded walk was caught doing.
 *
 *  Two things it deliberately does NOT contain. No login line -- RunService
 *  grafts that from the CIF chosen at run time, because the login is the one
 *  part of a scenario that belongs to whoever runs it rather than to the
 *  scenario. And no assertion -- the agent never asserts, it reads a snapshot
 *  and describes it, so the THEN comes from the analyst's own expectation.
 *
 *  `notes` says what could not be expressed. Empty is normal; silent is not. */
export interface ExploreAnswerResponse {
  answer: string;
  tapeId: string;
  gherkin: string;
  notes: string[];
}

/** POST /api/v1/explore/gherkin -- TapeGherkinController.Request. Made AFTER
 *  the walk has answered, so the analyst reads the answer while the model
 *  writes the scenario from the recorded tape. */
export interface TapeGherkinRequest {
  tapeId: string;
  app?: string;
  flow?: string;
  mainMenu?: string;
  /** The scenario as the analyst wrote it (docAsText). */
  scenario: string;
  expected?: string[];
}

/** POST /api/v1/explore/gherkin 200 body. Same `gherkin` shape as the walk's
 *  own answer: step lines, no keywords, no login line.
 *
 *  `source` says who wrote it: "ai" is the model working from the tape and
 *  checked word by word against it; "kural" is the code renderer's draft,
 *  shown when the model could not be used -- the first note says why.
 *  `notes` carries the checks the AI draft still fails, one per line. */
export interface TapeGherkinResponse {
  gherkin: string;
  notes: string[];
  valid: boolean;
  source: "ai" | "kural";
  attempts: number;
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
