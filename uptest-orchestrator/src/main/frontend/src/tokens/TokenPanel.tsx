import { useCallback, useEffect, useRef, useState } from "react";
import { getAiTrace, getAiTraces } from "../api/endpoints";
import type { AiTraceDetail, AiTraceOverview, DashboardPeriod } from "../api/types";
import { PanelHead } from "../workspace/side/PanelHead";
import { KIND_FILTERS, KIND_WORD } from "./tokenParts";
import { TokenOverview } from "./TokenOverview";
import { TokenSession } from "./TokenSession";

/** Which session another panel wants shown: by id, or the newest walk of a scenario. */
export interface TraceTarget {
  id?: string;
  scenarioPath?: string;
  nonce: number;
}

const PERIODS: Array<[DashboardPeriod, string]> = [
  ["24h", "24 saat"], ["7d", "7 gün"], ["30d", "30 gün"], ["all", "tümü"],
];

/** How often a running session is read again. */
const LIVE_MS = 2000;

/*
  Token Takibi: the sessions list → one session in full.

  Unlike the dashboard this one DOES poll, but only while something is
  running: an agent walk takes minutes and its turns are recorded as they
  happen, so watching one arrive is the point. Once nothing is running the
  page is still, for the dashboard's reason -- a table that moves under the
  reader loses their row.
*/
export function TokenPanel({ active, target }: { active: boolean; target: TraceTarget | null }) {
  const [period, setPeriod] = useState<DashboardPeriod>("7d");
  const [kind, setKind] = useState("");
  const [sessionId, setSessionId] = useState<string | null>(null);
  /* Sessions opened from a session (a walk → its Gherkin), so Geri retraces them. */
  const [trail, setTrail] = useState<string[]>([]);
  const [overview, setOverview] = useState<AiTraceOverview | null>(null);
  const [detail, setDetail] = useState<AiTraceDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const inFlight = useRef(false);

  const load = useCallback(async (quiet = false) => {
    if (inFlight.current) return;
    inFlight.current = true;
    if (!quiet) setLoading(true);
    try {
      if (sessionId) {
        const result = await getAiTrace(sessionId);
        if (result.ok) {
          setDetail(result.body);
          setError(null);
        } else {
          setError(result.error);
        }
      } else {
        const result = await getAiTraces(period, kind);
        if (result.ok) {
          setOverview(result.body);
          setError(null);
        } else {
          setError(result.error);
        }
      }
    } finally {
      inFlight.current = false;
      if (!quiet) setLoading(false);
    }
  }, [sessionId, period, kind]);

  useEffect(() => {
    if (active) void load();
  }, [active, load]);

  const live = active && (sessionId
    ? detail?.session.id === sessionId && detail.session.status === "RUNNING"
    : overview?.running === true);

  useEffect(() => {
    if (!live) return;
    const timer = window.setInterval(() => void load(true), LIVE_MS);
    return () => window.clearInterval(timer);
  }, [live, load]);

  const openSession = useCallback((id: string, remember: boolean) => {
    setTrail((now) => (remember && sessionId ? [...now, sessionId] : remember ? now : []));
    setDetail((now) => (now?.session.id === id ? now : null));
    setSessionId(id);
  }, [sessionId]);

  /* Another panel asked for a session: by id once a walk has answered, or by
     scenario while it is still running -- its id is not known until the walk
     returns, but its row exists from the first second. A few retries cover
     the moment between the click and that row being written. */
  useEffect(() => {
    if (!target) return;
    if (target.id) {
      openSession(target.id, false);
      return;
    }
    let cancelled = false;
    void (async () => {
      for (let attempt = 0; attempt < 4 && !cancelled; attempt++) {
        const result = await getAiTraces("24h", "ai-run");
        if (cancelled) return;
        if (result.ok) {
          const mine = result.body.sessions.filter((s) => s.scenarioPath === target.scenarioPath);
          const match = mine.find((s) => s.status === "RUNNING") ?? mine[0];
          if (match) {
            openSession(match.id, false);
            return;
          }
        }
        await new Promise((resolve) => window.setTimeout(resolve, 1000));
      }
      if (!cancelled) {
        setSessionId(null);
        setKind("ai-run");
      }
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [target?.nonce]);

  const back = () => {
    const previous = trail[trail.length - 1];
    setTrail((now) => now.slice(0, -1));
    setDetail(null);
    setSessionId(previous ?? null);
  };

  const title = sessionId
    ? detail ? (detail.session.title || KIND_WORD[detail.session.kind]) : "AI oturumu"
    : "Token Takibi";

  return (
    <div id="side-tokens" className={"side-panel dashboard-panel tokens-panel" + (active ? " is-active" : "")}>
      <PanelHead title={title}>
        {sessionId ? <button type="button" className="btn btn-ghost" onClick={back}>← Geri</button> : null}
        {sessionId ? null : (
          <span className="db-periods" role="group" aria-label="Dönem">
            {PERIODS.map(([id, word]) => (
              <button key={id} type="button"
                      className={"btn btn-ghost db-chip" + (period === id ? " is-active" : "")}
                      aria-pressed={period === id} onClick={() => setPeriod(id)}>
                {word}
              </button>
            ))}
          </span>
        )}
        <button type="button" className="btn btn-ghost" disabled={loading} onClick={() => void load()}>
          {loading ? "Okunuyor…" : "Yenile"}
        </button>
      </PanelHead>

      <div className="db-body">
        {error ? <p className="product-warn">Token kaydı okunamadı: {error}</p> : null}
        {sessionId ? (
          detail && detail.session.id === sessionId
            ? <TokenSession data={detail} onOpenSession={(id) => openSession(id, true)} />
            : !error ? <p className="db-empty">Okunuyor…</p> : null
        ) : (
          <>
            <div className="db-filters" role="group" aria-label="Oturum türü">
              {KIND_FILTERS.map((filter) => (
                <button key={filter.id || "all"} type="button"
                        className={"btn btn-ghost db-chip" + (kind === filter.id ? " is-active" : "")}
                        aria-pressed={kind === filter.id} onClick={() => setKind(filter.id)}>
                  {filter.word}
                </button>
              ))}
            </div>
            {overview ? <TokenOverview data={overview} onOpen={(s) => openSession(s.id, false)} />
              : !error ? <p className="db-empty">Okunuyor…</p> : null}
          </>
        )}
      </div>
    </div>
  );
}
