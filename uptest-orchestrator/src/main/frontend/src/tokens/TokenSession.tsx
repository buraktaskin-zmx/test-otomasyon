import { useMemo, useState } from "react";
import { formatDuration, formatTime } from "../lib/format";
import type { AiSession, AiToolCall, AiTraceDetail, AiTurn } from "../api/types";
import { Card, Kpi } from "../dashboard/parts";
import {
  BreakdownBar, CATEGORIES, Estimate, KIND_WORD, LazyText, OutcomeWord, SessionStatus,
  compact, niceMax, offset, partOf, percent, tokens,
} from "./tokenParts";
import { TurnMessages } from "./TurnMessages";

/*
  One session, in full: what it cost, where the prompt went, and every turn
  with the tool calls it asked for. See docs/TOKEN-TAKIBI.md.
*/
export function TokenSession({ data, onOpenSession }: {
  data: AiTraceDetail;
  onOpenSession: (id: string) => void;
}) {
  const s = data.session;
  const [open, setOpen] = useState<Set<number>>(() => new Set());

  const toolsByTurn = useMemo(() => {
    const map = new Map<number | null, AiToolCall[]>();
    for (const call of data.toolCalls) {
      const list = map.get(call.turnSeq) ?? [];
      list.push(call);
      map.set(call.turnSeq, list);
    }
    return map;
  }, [data.toolCalls]);

  const refused = data.toolCalls.filter((c) => c.outcome === "REFUSED").length;
  const failed = data.toolCalls.filter((c) => c.outcome === "ERROR").length;
  const modelMs = data.turns.reduce((sum, t) => sum + t.durationMs, 0);
  const toolMs = data.toolCalls.reduce((sum, c) => sum + (c.durationMs ?? 0), 0);

  const toggle = (seq: number) => setOpen((now) => {
    const next = new Set(now);
    if (next.has(seq)) next.delete(seq); else next.add(seq);
    return next;
  });

  /* A column of the chart opens its turn below and brings it into view. */
  const focus = (seq: number) => {
    setOpen((now) => new Set(now).add(seq));
    window.setTimeout(() => {
      document.getElementById("tk-turn-" + seq)?.scrollIntoView({ block: "start", behavior: "smooth" });
    }, 0);
  };

  return (
    <div className="db-page">
      <SessionHead session={s} parent={data.parent} onOpenSession={onOpenSession} />

      <div className="db-kpis">
        <Kpi label="Girdi" value={compact(s.promptTokens)} sub={tokens(s.promptTokens) + " token · sunucu bildirdi"} />
        <Kpi label="Çıktı" value={compact(s.completionTokens)} sub={tokens(s.completionTokens) + " token"} />
        <Kpi label="Önbellekten"
             value={s.cachedTokens == null ? "—" : compact(s.cachedTokens)}
             sub={s.cachedTokens == null ? "sunucu bildirmiyor" : "girdinin " + percent(s.cachedTokens, s.promptTokens) + "'i"} />
        <Kpi label="Model turu" value={s.turns}
             sub={s.turns ? "modelde " + formatDuration(modelMs) : "henüz tur yok"} />
        <Kpi label="Tool çağrısı" value={s.toolCalls}
             sub={s.toolCalls ? [refused ? refused + " reddedildi" : "", failed ? failed + " hata" : "",
               "araçta " + formatDuration(toolMs)].filter(Boolean).join(" · ") : "tool kullanılmadı"} />
        <Kpi label="Süre" value={formatDuration(s.durationMs)} sub={formatTime(s.startedAt)} />
      </div>

      <div className="db-grid">
        <Card
          title="Bağlam büyümesi"
          aside={data.turns.length ? data.turns.length + " tur" : null}
          note="Her sütun bir model turunun girdisi. Ajan her turda bütün geçmişi yeniden gönderdiği için sütunlar büyür. Sütuna tıklayınca tur aşağıda açılır."
        >
          {data.turns.length === 0 ? <p className="db-empty">Henüz model turu yok.</p> : (
            <>
              <CategoryLegend />
              <ContextChart turns={data.turns} onPick={focus} />
            </>
          )}
        </Card>

        <Card title="Token nereye gitti" aside={data.totals ? <Estimate on={data.totals.exact === false} /> : null}>
          <WhereItWent data={data} />
        </Card>
      </div>

      <Card
        title="Zaman çizelgesi"
        aside={s.status === "RUNNING" ? <span className="tk-live">● canlı</span> : data.turns.length + " tur"}
        note="Bir tur: modele gönderilen girdi, modelin cevabı ve istediği tool çağrıları. Tool sonuçları bir sonraki turun girdisine eklenir."
      >
        {data.turns.length === 0 && data.toolCalls.length === 0 ? (
          <p className="db-empty">{s.status === "RUNNING" ? "İlk model turu bekleniyor…" : "Bu oturumda kaydedilmiş tur yok."}</p>
        ) : (
          <ol className="tk-timeline">
            {(toolsByTurn.get(null) ?? []).length > 0 ? (
              <li className="tk-turn">
                <div className="tk-turn-orphans">Bir tura bağlanamayan tool çağrıları</div>
                <ToolCalls sessionId={s.id} calls={toolsByTurn.get(null) ?? []} start={s.startedAt} />
              </li>
            ) : null}
            {data.turns.map((turn, index) => (
              <TurnRow
                key={turn.seq}
                sessionId={s.id}
                start={s.startedAt}
                turn={turn}
                previous={index > 0 ? data.turns[index - 1] ?? null : null}
                calls={toolsByTurn.get(turn.seq) ?? []}
                open={open.has(turn.seq)}
                onToggle={() => toggle(turn.seq)}
                latest={s.status === "RUNNING" && index === data.turns.length - 1}
              />
            ))}
          </ol>
        )}
      </Card>

      {data.children.length > 0 ? (
        <Card title="Bu oturumdan başlayanlar">
          <ul className="tk-children">
            {data.children.map((child) => (
              <li key={child.id}>
                <button type="button" className="unstyled-button tk-link" onClick={() => onOpenSession(child.id)}>
                  {KIND_WORD[child.kind]} — {child.title || "(adsız)"}
                </button>
                <span className="tk-num">{tokens(child.totalTokens)} token · {child.turns} tur</span>
                <SessionStatus status={child.status} />
              </li>
            ))}
          </ul>
        </Card>
      ) : null}
    </div>
  );
}

function SessionHead({ session: s, parent, onOpenSession }: {
  session: AiSession;
  parent: AiSession | null;
  onOpenSession: (id: string) => void;
}) {
  return (
    <div className="tk-head">
      <div className="tk-head-line">
        <span className="tk-kind">{KIND_WORD[s.kind] ?? s.kind}</span>
        <SessionStatus status={s.status} />
        {s.models ? <span className="tk-dim">{s.models}</span> : null}
      </div>
      {s.scenarioPath ? <div className="sc-path">{[s.app, s.product, s.scenarioPath].filter(Boolean).join(" / ")}</div> : null}
      <div className="tk-head-meta">
        {s.cif ? <span>CIF <b className="h-mono">{s.cif}</b></span> : null}
        {s.tapeId ? <span title="runs/mcp altındaki eylem bandı">bant <span className="h-mono">{s.tapeId.slice(0, 8)}</span></span> : null}
        <span>oturum <span className="h-mono">{s.id.slice(0, 8)}</span></span>
        {parent ? (
          <span>
            şundan başladı:{" "}
            <button type="button" className="unstyled-button tk-link" onClick={() => onOpenSession(parent.id)}>
              {KIND_WORD[parent.kind]} — {parent.title || parent.id.slice(0, 8)}
            </button>
          </span>
        ) : null}
      </div>
      {s.error ? <p className="product-warn tk-head-error">{s.error}</p> : null}
    </div>
  );
}

function CategoryLegend() {
  return (
    <span className="db-legend tk-legend">
      {CATEGORIES.map((c) => (
        <span key={c.key} className={"db-legend-item tk-" + c.key} title={c.hint}>{c.word}</span>
      ))}
    </span>
  );
}

/** One column per turn, its input split by source, on a token axis. */
function ContextChart({ turns, onPick }: { turns: AiTurn[]; onPick: (seq: number) => void }) {
  const max = niceMax(Math.max(0, ...turns.map((t) => t.promptTokens ?? 0)));
  // A label under every column when there is room, every n-th when there is not.
  const every = Math.max(1, Math.ceil(turns.length / 16));
  return (
    <div className="db-chart">
      <div className="db-axis tk-axis" aria-hidden="true">
        <span>{compact(max)}</span>
        <span>{compact(Math.round(max / 2))}</span>
        <span>0</span>
      </div>
      <div className="db-plot">
        <div className="db-lines" aria-hidden="true"><span /><span /><span /></div>
        <div className="db-daily tk-turns">
          {turns.map((t, i) => {
            const prompt = t.promptTokens ?? 0;
            const split = t.breakdown;
            const parts = split
              ? CATEGORIES.map((c) => [c, partOf(split, c.key)] as const).filter(([, n]) => n > 0)
              : [];
            const describe = "Tur " + t.seq + ": girdi " + tokens(t.promptTokens) + ", çıktı " + tokens(t.completionTokens)
              + (t.cachedTokens != null ? ", önbellek " + tokens(t.cachedTokens) : "")
              + (parts.length ? " — " + parts.map(([c, n]) => c.word + " " + tokens(n)).join(", ") : " — kırılım yok")
              + (t.requestedToolCalls ? " · " + t.requestedToolCalls + " tool çağrısı istedi" : "");
            return (
              <button
                key={t.seq}
                type="button"
                className="db-day unstyled-button"
                title={describe}
                aria-label={describe}
                onClick={() => onPick(t.seq)}
              >
                <span className="db-day-col" style={{ height: (100 * prompt) / max + "%" }}>
                  {parts.length
                    ? parts.map(([c, n]) => <span key={c.key} className={"db-seg tk-" + c.key} style={{ flexGrow: n }} />)
                    : prompt > 0 ? <span className="db-seg tk-unsplit" style={{ flexGrow: 1 }} /> : null}
                </span>
                <span className="db-day-label">{i % every === 0 || i === turns.length - 1 ? t.seq : ""}</span>
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
}

/** The session's prompt by source, then the tools by what their results cost. */
function WhereItWent({ data }: { data: AiTraceDetail }) {
  const totals = data.totals;
  const resultTotal = data.toolStats.reduce((sum, t) => sum + (t.resultTokens ?? 0), 0);
  return (
    <>
      {totals ? (
        <>
          <BreakdownBar split={totals} prompt={data.session.promptTokens} />
          {totals.missingTurns > 0 ? (
            <p className="db-note">{totals.missingTurns} tur henüz sayılmadı ya da sayılamaz (görsel model); yukarıdaki parçalara girmedi.</p>
          ) : null}
        </>
      ) : (
        <p className="db-empty">
          {data.session.turns === 0 ? "Henüz tur yok."
            : "Kırılım henüz hesaplanmadı. Sayım arka planda yapılır; birkaç saniye içinde gelir. Görsel model turları kırılmaz."}
        </p>
      )}

      {data.toolStats.length > 0 ? (
        <div className="db-table-wrap tk-tools-wrap">
          <table className="registry db-table tk-tool-table">
            <thead>
              <tr>
                <th>Tool</th>
                <th>Çağrı</th>
                <th title="Modelin geri aldığı sonuçların token'ı">Token</th>
                <th>Pay</th>
                <th>Süre</th>
              </tr>
            </thead>
            <tbody>
              {data.toolStats.map((t) => (
                <tr key={t.tool}>
                  <td>
                    <div className="db-name h-mono">{t.tool}</div>
                    {t.refused || t.errors ? (
                      <div className="sc-path">{[t.refused ? t.refused + " reddedildi" : "", t.errors ? t.errors + " hata" : ""].filter(Boolean).join(" · ")}</div>
                    ) : null}
                  </td>
                  <td className="h-mono">{t.calls}</td>
                  <td className="h-mono" title={tokens(t.resultChars) + " karakter"}>
                    {t.resultTokens == null ? "sayılıyor…" : tokens(t.resultTokens)}
                  </td>
                  <td>
                    <div className="tk-meter tk-meter-inline" aria-label={percent(t.resultTokens ?? 0, resultTotal)}>
                      <span style={{ width: (resultTotal ? (100 * (t.resultTokens ?? 0)) / resultTotal : 0) + "%" }} />
                    </div>
                  </td>
                  <td className="h-mono">{formatDuration(t.durationMs)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </>
  );
}

function TurnRow({ sessionId, start, turn, previous, calls, open, onToggle, latest }: {
  sessionId: string;
  start: string;
  turn: AiTurn;
  previous: AiTurn | null;
  calls: AiToolCall[];
  open: boolean;
  onToggle: () => void;
  latest: boolean;
}) {
  const growth = previous && turn.promptTokens != null && previous.promptTokens != null
    ? turn.promptTokens - previous.promptTokens : null;
  const names = Array.from(new Set(calls.map((c) => c.tool)));
  return (
    <li className={"tk-turn" + (open ? " is-open" : "") + (turn.error ? " is-error" : "")} id={"tk-turn-" + turn.seq}>
      <button type="button" className="tk-turn-head unstyled-button" aria-expanded={open} onClick={onToggle}>
        <span className="tk-turn-seq">#{turn.seq}</span>
        <span className="tk-turn-at h-mono">{offset(start, turn.startedAt)}</span>
        <span className="tk-turn-nums h-mono">
          <span title="Girdi token (sunucu bildirdi)">↑ {tokens(turn.promptTokens)}</span>
          {growth != null && growth > 0 ? <span className="tk-dim" title="Bir önceki tura göre girdi artışı">+{tokens(growth)}</span> : null}
          <span title="Çıktı token">↓ {tokens(turn.completionTokens)}</span>
          {turn.cachedTokens ? <span className="tk-dim" title="Önbellekten gelen girdi">⟲ {tokens(turn.cachedTokens)}</span> : null}
        </span>
        <span className="tk-turn-tools">
          {names.length ? names.map((n) => <span key={n} className="tk-chip h-mono">{n}{calls.filter((c) => c.tool === n).length > 1 ? " ×" + calls.filter((c) => c.tool === n).length : ""}</span>)
            : turn.error ? <span className="tk-error">hata</span>
            : <span className="tk-dim">{turn.finishReason === "stop" ? "son cevap" : turn.finishReason ?? ""}</span>}
        </span>
        <span className="tk-turn-ms h-mono">{formatDuration(turn.durationMs)}</span>
        {latest ? <span className="tk-new">yeni</span> : null}
      </button>

      {open ? (
        <div className="tk-turn-body">
          <div className="tk-turn-meta">
            {turn.model ? <span>model <b>{turn.model}</b></span> : null}
            <span>{turn.messageCount} mesaj ({turn.newMessageCount} yeni)</span>
            {turn.toolDefCount ? <span>{turn.toolDefCount} tool tanımı</span> : null}
            {turn.finishReason ? <span>bitiş: <span className="h-mono">{turn.finishReason}</span></span> : null}
          </div>
          {turn.error ? <p className="product-warn">{turn.error}</p> : null}
          {turn.breakdown ? <BreakdownBar split={turn.breakdown} prompt={turn.promptTokens} /> : null}

          {turn.responseChars > 0 || turn.responseSha ? (
            <LazyText sessionId={sessionId} sha={turn.responseSha} preview={turn.responsePreview}
                      chars={turn.responseChars} label="Modelin cevabı" />
          ) : null}
          {turn.reasoningChars > 0 ? (
            <LazyText sessionId={sessionId} sha={turn.reasoningSha} chars={turn.reasoningChars} label="Modelin düşüncesi (reasoning)" />
          ) : null}

          {calls.length ? (
            <>
              <div className="tk-text-label">Bu turun istediği tool çağrıları</div>
              <ToolCalls sessionId={sessionId} calls={calls} start={start} />
            </>
          ) : null}

          <TurnMessages sessionId={sessionId} seq={turn.seq} count={turn.messageCount} />
        </div>
      ) : null}
    </li>
  );
}

function ToolCalls({ sessionId, calls, start }: { sessionId: string; calls: AiToolCall[]; start: string }) {
  const [open, setOpen] = useState<Set<number>>(() => new Set());
  return (
    <ul className="tk-calls">
      {calls.map((c) => {
        const expanded = open.has(c.seq);
        return (
          <li key={c.seq} className={"tk-call" + (c.outcome !== "OK" ? " is-" + c.outcome.toLowerCase() : "")}>
            <button type="button" className="tk-call-head unstyled-button" aria-expanded={expanded}
                    onClick={() => setOpen((now) => {
                      const next = new Set(now);
                      if (next.has(c.seq)) next.delete(c.seq); else next.add(c.seq);
                      return next;
                    })}>
              <span className="tk-turn-seq">{c.seq}</span>
              <span className="h-mono tk-call-name">{c.tool}</span>
              <OutcomeWord outcome={c.outcome} />
              <span className="tk-call-args h-mono" title={c.argsPreview ?? ""}>{c.argsPreview}</span>
              <span className="h-mono tk-dim">{offset(start, c.startedAt)}</span>
              <span className="h-mono" title="Modelin geri aldığı sonucun token'ı">
                {c.resultTokens == null ? tokens(c.resultChars) + " kr" : tokens(c.resultTokens) + " tk"}
                <Estimate on={c.resultExact === false} />
              </span>
              <span className="h-mono tk-dim">{formatDuration(c.durationMs)}</span>
            </button>
            {expanded ? (
              <div className="tk-call-body">
                <LazyText sessionId={sessionId} sha={c.argsSha} preview={c.argsPreview} chars={c.argsChars} label="Argümanlar" />
                <LazyText sessionId={sessionId} sha={c.resultSha} preview={c.resultPreview} chars={c.resultChars}
                          label="Sonuç (tam hâli — model kırpılmış hâlini gördü)" />
              </div>
            ) : null}
          </li>
        );
      })}
    </ul>
  );
}
