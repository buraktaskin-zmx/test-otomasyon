import type { ErrorBody } from "./types";

/*
  One rule: this never throws.

  The old api() never threw on an HTTP STATUS, which is why every caller
  branches on 422 and 503 instead of catching. But a transport failure -- the
  orchestrator stopped, the laptop slept, the proxy dropped -- rejects the
  fetch promise, and run() had no .catch, so the "Calistir" button was left
  disabled forever with no message. The failure was invisible and permanent.

  So transport failures become a result like any other, carrying status 0.
  A caller cannot forget to handle them, because the discriminated union below
  gives it no body to read until it has checked `ok`.
*/

export type ApiResult<T> =
  | { ok: true; status: number; body: T }
  | { ok: false; status: number; body: unknown; error: string };

/** status 0 means the request never reached the server. */
export const TRANSPORT_FAILURE = 0;

function messageFor(status: number, body: unknown): string {
  const named = body as ErrorBody | null;
  if (named && typeof named === "object") {
    if (typeof named.error === "string" && named.error) return named.error;
    if (typeof named.detail === "string" && named.detail) return named.detail;
  }
  if (status === TRANSPORT_FAILURE) return "Sunucuya ulaşılamadı";
  return "HTTP " + status;
}

export async function api<T>(
  method: string,
  path: string,
  body?: unknown,
): Promise<ApiResult<T>> {
  const options: RequestInit = { method, headers: {} };
  if (body !== undefined) {
    options.headers = { "Content-Type": "application/json; charset=utf-8" };
    options.body = JSON.stringify(body);
  }

  let response: Response;
  try {
    response = await fetch(path, options);
  } catch (cause) {
    return {
      ok: false,
      status: TRANSPORT_FAILURE,
      body: null,
      error: cause instanceof Error ? cause.message : String(cause),
    };
  }

  /*
    Read as text first, then try JSON: some error paths answer with a plain
    string and some answer with nothing at all, and response.json() throws on
    both. The old client did the same thing for the same reason.
  */
  const text = await response.text().catch(() => "");
  let parsed: unknown = null;
  if (text) {
    try {
      parsed = JSON.parse(text);
    } catch {
      parsed = text;
    }
  }

  if (!response.ok) {
    return {
      ok: false,
      status: response.status,
      body: parsed,
      error: messageFor(response.status, parsed),
    };
  }
  return { ok: true, status: response.status, body: parsed as T };
}

/*
  GET /api/v1/runs and the artifacts listing answer with a BARE ARRAY, and the
  server means it: RunController records that wrapping the run list in a Spring
  Page would blank the history pane without any error. If that ever changes,
  this turns it into one visible empty state instead of a silent blank -- and
  the console line names the endpoint that moved.
*/
export function asArray<T>(value: unknown, what: string): T[] {
  if (Array.isArray(value)) return value as T[];
  if (value != null) {
    console.error("[uptest] " + what + ": bir dizi bekleniyordu, şu geldi:", value);
  }
  return [];
}
