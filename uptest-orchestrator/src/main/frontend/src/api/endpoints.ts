import { api, asArray } from "./client";
import type { ApiResult } from "./client";
import type {
  Artifact,
  CatalogsResponse,
  ChatReplyRequest,
  ChatStartRequest,
  ChatTurn,
  DiscoverRequest,
  DiscoverStarted,
  DiscoverStatus,
  ExecutorNode,
  ExploreAnswerResponse,
  ExploreCatalogResponse,
  ExploreRequest,
  ExploreToolsResponse,
  LlmHelloResponse,
  Origin,
  RunLogin,
  ProductDetail,
  ProductRegistry,
  ProductsResponse,
  SaveUserRequest,
  ScenarioDetail,
  ScenarioEdit,
  TestUser,
  TestUsersResponse,
  RunDetail,
  RunStarted,
  SaveCatalogResponse,
  StopAccepted,
  SuggestManyRequest,
  SuggestManyResponse,
  SuggestRequest,
  SuggestResponse,
  ValidationResult,
  VocabularyRefreshResponse,
  VocabularyResponse,
} from "./types";
import type { ProductSummary } from "./types";

/*
  All 24 orchestrator endpoints, named once, each a thin wrapper around api().
  Nothing else in this app builds a URL by hand.

  A handful of these answer with a DIFFERENT body shape depending on status
  code (startRun's 202 vs its 422 is the clearest example: success is
  RunStarted, but a 422 is a bare ValidationResult, not an ErrorBody). Per
  client.ts's ApiResult, the `ok:false` branch always types `body` as
  `unknown` -- the type parameter only describes the SUCCESS body. Callers
  that need to read a non-2xx body narrow it themselves by checking
  `result.status`, as documented at each call below.
*/

/* ---------------------------- vocabulary ---------------------------- */

export const getVocabulary = () =>
  api<VocabularyResponse>("GET", "/api/v1/vocabulary");

/** POST /vocabulary/refresh -- re-reads the step list from executors. The
 *  executor pill's click handler must call this, not just re-GET, or the
 *  "sözlük yenilendi" toast keeps lying (VocabularyService caches forever
 *  until this is called). */
export const refreshVocabulary = () =>
  api<VocabularyRefreshResponse>("POST", "/api/v1/vocabulary/refresh");

/* ---------------------------- executors ------------------------------ */

export async function getExecutors(): Promise<ApiResult<ExecutorNode[]>> {
  const result = await api<unknown>("GET", "/api/v1/executors");
  if (!result.ok) return result;
  return { ...result, body: asArray<ExecutorNode>(result.body, "GET /api/v1/executors") };
}

/* ---------------------------- validate + run -------------------------- */

/** Always 200 on success, valid:true OR valid:false -- 422 does not exist
 *  on this endpoint. A 503 ({error,hint}) means no executor is reachable;
 *  the badge must read "executor yok", not a generic failure. */
export const validateScenario = (feature: string, where: Origin) =>
  api<ValidationResult>("POST", "/api/v1/scenarios/validate", { feature, ...where });

/**
 * 202 -> RunStarted. 422 -> the BARE ValidationResult (not wrapped in
 * {error:...} -- read `result.body as ValidationResult` when
 * `result.status === 422`). 503 -> ErrorBody, two distinct messages
 * depending on whether no executor exists at all vs. all are busy.
 */
/** `login` is the saved test user the analyst picked, or undefined to run
 *  the scenario with whatever login its own text carries. The orchestrator
 *  writes the Gherkin line from it -- see RunService.start. */
export const startRun = (feature: string, where: Origin, login?: RunLogin) =>
  api<RunStarted>("POST", "/api/v1/runs", { feature, ...where, ...login });

/* ------------------------------- runs --------------------------------- */

/** `limit` mirrors the server default (uptest.run.list-limit=50) when
 *  omitted; pass up to 500 (server-clamped). */
export async function getRuns(limit?: number): Promise<ApiResult<RunDetail[]>> {
  const path = "/api/v1/runs" + (limit ? "?limit=" + encodeURIComponent(String(limit)) : "");
  const result = await api<unknown>("GET", path);
  if (!result.ok) return result;
  return { ...result, body: asArray<RunDetail>(result.body, "GET " + path) };
}

export const getRun = (runId: string) =>
  api<RunDetail>("GET", "/api/v1/runs/" + encodeURIComponent(runId));

export async function getRunArtifacts(runId: string): Promise<ApiResult<Artifact[]>> {
  const path = "/api/v1/runs/" + encodeURIComponent(runId) + "/artifacts";
  const result = await api<unknown>("GET", path);
  if (!result.ok) return result;
  return { ...result, body: asArray<Artifact>(result.body, "GET " + path) };
}

/*
  Artifact downloads are plain links, never fetched: report.html is served
  with Content-Security-Policy: sandbox allow-scripts and must open in its
  own tab or download (for .zip), never be read via fetch(). Each path
  segment is encoded SEPARATELY so "traces/x.zip" keeps its slash -- encoding
  the whole name would turn "/" into "%2F", which Tomcat rejects with 400
  before the orchestrator's handler even runs.
*/
export function artifactHref(runId: string, name: string): string {
  const encoded = name.split("/").map(encodeURIComponent).join("/");
  return "/api/v1/runs/" + encodeURIComponent(runId) + "/artifacts/" + encoded;
}

/** 202 -> StopAccepted. Cooperative only: a hung Playwright call survives
 *  this. UI copy must say a stop was REQUESTED, not that the run stopped --
 *  confirm it did by polling for status CANCELED. */
export const stopRun = (runId: string) =>
  api<StopAccepted>("PUT", "/api/v1/runs/" + encodeURIComponent(runId) + "/stop");

/* ------------------------------ catalogs ------------------------------- */

export const getCatalogs = () =>
  api<CatalogsResponse>("GET", "/api/v1/scenarios/catalogs");

export const saveCatalog = (app: string, flow: string, yaml: string) =>
  api<SaveCatalogResponse>("POST", "/api/v1/scenarios/catalogs", { app, flow, yaml });

/* ---------------------------- test users ------------------------------- */

export const getTestUsers = () =>
  api<TestUsersResponse>("GET", "/api/v1/users");

/** Adds, or replaces the row with the same id. Answers with the saved row,
 *  whose `id` the server derived -- never guess it here. */
export const saveTestUser = (user: SaveUserRequest) =>
  api<TestUser>("POST", "/api/v1/users", user);

export const deleteTestUser = (id: string) =>
  api<{ deleted: string }>("DELETE", "/api/v1/users/" + encodeURIComponent(id));

/* ------------------------------ products ------------------------------- */

/** Every product, without its rules -- three pickers load this, and a
 *  product may carry fifty rules none of them show. */
export const getProducts = () =>
  api<ProductsResponse>("GET", "/api/v1/products");

/** One product in full: rules, free text, and what it is missing. 404 with
 *  an ErrorBody carrying `detail` when the files will not parse, which is
 *  the only way an analyst finds out why a product they just wrote is not
 *  in the picker. */
export const getProduct = (app: string, product: string) =>
  api<ProductDetail>(
    "GET",
    "/api/v1/products/" + encodeURIComponent(app) + "/" + encodeURIComponent(product),
  );

/** The registered scenarios of one product, with their rule bindings, data
 *  preconditions and last run. Re-read every time it is opened: a .feature
 *  is edited by hand in an IDE while this screen is open, and a registry
 *  showing yesterday's step count is worse than none. */
/** One scenario's Gherkin plus its resolved row. The path travels as a
 *  query parameter because it contains slashes; the server still checks
 *  containment, because a path from a browser is never trusted. */
export const getScenario = (app: string, product: string, path: string) =>
  api<ScenarioDetail>(
    "GET",
    "/api/v1/products/" + encodeURIComponent(app) + "/"
      + encodeURIComponent(product) + "/scenario?path=" + encodeURIComponent(path),
  );

/** "+ Ana menü": a new product folder whose category is `name`. */
export const createMenu = (app: string, name: string) =>
  api<ProductSummary>("POST", "/api/v1/products", { app, name });

/** "+ Ekran ekle": declares a screen on a product; answers with its flow. */
export const addScreen = (app: string, product: string, name: string) =>
  api<{ flow: string }>(
    "POST",
    "/api/v1/products/" + encodeURIComponent(app) + "/" + encodeURIComponent(product) + "/screens",
    { name },
  );

/** "+" beside a screen: a new scenario file there; answers with its path. */
export const createScenarioFile = (app: string, product: string, flow: string, feature: string) =>
  api<{ path: string }>(
    "POST",
    "/api/v1/products/" + encodeURIComponent(app) + "/" + encodeURIComponent(product) + "/scenarios",
    { flow, feature },
  );

/** "Taşı": moves a scenario to another declared screen of the same app. */
export const moveScenario = (app: string, product: string, path: string,
                             toProduct: string, toFlow: string) =>
  api<{ path: string }>(
    "POST",
    "/api/v1/products/" + encodeURIComponent(app) + "/" + encodeURIComponent(product)
      + "/scenario/move?path=" + encodeURIComponent(path),
    { product: toProduct, flow: toFlow },
  );

/** ExploreController's error code when the operator closed the agent's browser. */
export const BROWSER_CLOSED = "BROWSER_CLOSED";

/** Saves an edit to a scenario in the database; answers like getScenario.
 *  409 when someone else saved it first. */
export const saveScenarioEdit = (app: string, product: string, path: string, edit: ScenarioEdit) =>
  api<ScenarioDetail>(
    "PUT",
    "/api/v1/products/" + encodeURIComponent(app) + "/"
      + encodeURIComponent(product) + "/scenario?path=" + encodeURIComponent(path),
    edit,
  );

/** Writes every scenario out as .feature files under products/. Never deletes;
 *  files the database no longer has come back in `notInDatabase`. */
export const exportScenarios = () =>
  api<{ written: number; unchanged: number; notInDatabase: string[] }>(
    "POST", "/api/v1/products/export");

export const getProductScenarios = (app: string, product: string) =>
  api<ProductRegistry>(
    "GET",
    "/api/v1/products/" + encodeURIComponent(app) + "/"
      + encodeURIComponent(product) + "/scenarios",
  );

/* ----------------------------- generation ------------------------------ */

export const suggestScenario = (request: SuggestRequest) =>
  api<SuggestResponse>("POST", "/api/v1/scenarios/suggest", request);

/** Slow by nature -- sequential, each scenario its own generate-validate-
 *  repair round, so 5 scenarios realistically take 1-3 minutes. Kept
 *  synchronous server-side; the caller must disable its button for the
 *  duration, not assume this resolves quickly. */
export const suggestMany = (request: SuggestManyRequest) =>
  api<SuggestManyResponse>("POST", "/api/v1/scenarios/suggest-many", request);

/* --------------------------------- chat ---------------------------------

   One model call per turn, so seconds -- the same cost as a single
   /suggest, because it IS /suggest with the repair turn pointed at the
   analyst instead of at the model. Nothing here drives a browser. */

export const startChat = (request: ChatStartRequest) =>
  api<ChatTurn>("POST", "/api/v1/chat", request);

export const replyToChat = (sessionId: string, request: ChatReplyRequest) =>
  api<ChatTurn>("POST", `/api/v1/chat/${encodeURIComponent(sessionId)}/reply`, request);

export const getChat = (sessionId: string) =>
  api<ChatTurn>("GET", `/api/v1/chat/${encodeURIComponent(sessionId)}`);

/** Sessions also expire on their own (uptest.chat.idle-timeout); this is the
 *  explicit "I am done" so an abandoned transcript does not wait for it. */
export const endChat = (sessionId: string) =>
  api<{ closed: string }>("DELETE", `/api/v1/chat/${encodeURIComponent(sessionId)}`);

/* -------------------------------- agent --------------------------------- */

/** Minutes long -- a real browser is driven and the model re-reads the page
 *  between every action. */
export const explore = (request: ExploreRequest) =>
  api<ExploreAnswerResponse>("POST", "/api/v1/explore", request);

export const exploreForCatalog = (request: ExploreRequest) =>
  api<ExploreCatalogResponse>("POST", "/api/v1/explore/catalog", request);

/** count:0 means the model was never handed a browser -- the one diagnostic
 *  that tells apart "bad model output" from "MCP never started". This
 *  endpoint 404s outright (not a typed error body) when
 *  uptest.explore.enabled=false -- treat any non-ok result here as "hide the
 *  whole Ajan tab", not just "show an error". */
export const getExploreTools = () =>
  api<ExploreToolsResponse>("GET", "/api/v1/explore/tools");

/* ------------------------------ discovery -------------------------------- */

/** 202 -> DiscoverStarted (no stepCount, unlike RunStarted). Shares the
 *  SAME executor pool/slot as a normal run -- a discovery run in progress
 *  can make POST /runs answer 503, and this is invisible in GET /runs by
 *  design (discovery never writes to run history). */
export const startDiscovery = (request: DiscoverRequest) =>
  api<DiscoverStarted>("POST", "/api/v1/discovery", request);

/** Poll this, NOT GET /runs/{id} -- discovery has its own registry
 *  (in-memory only, lost on orchestrator restart) and its own response
 *  shape (raw CucumberRunnerResult, not RunDetail's eight-key envelope). */
export const getDiscoveryStatus = (id: string) =>
  api<DiscoverStatus>("GET", "/api/v1/discovery/" + encodeURIComponent(id));

/*
  The draft catalog can also be read mid-run via the ordinary artifact
  proxy, because CatalogDiscoveryService rewrites catalog-draft.yaml after
  every screen: "a flow that dies on screen three still leaves screens one
  and two on disk." Reuses the run-artifact URL builder -- discovery runs
  live in the same per-run directory layout as an ordinary run.
*/
export const discoveryDraftHref = (runId: string) => artifactHref(runId, "catalog-draft.yaml");

/* -------------------------------- model health --------------------------- */

/** Always 200; branch on whether `answer` is present, not on status. */
export const llmHello = (prompt?: string) =>
  api<LlmHelloResponse>(
    "GET",
    "/api/v1/llm/hello" + (prompt ? "?prompt=" + encodeURIComponent(prompt) : ""),
  );
