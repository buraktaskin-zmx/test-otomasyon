import { formatDuration } from "../lib/format";
import type { AiSession, AiTraceDay, AiTraceOverview } from "../api/types";
import { Card, Kpi, relativeTime, usePaged } from "../dashboard/parts";
import { KIND_WORD, SessionStatus, compact, niceMax, percent, tokens } from "./tokenParts";

const SESSIONS_PER_PAGE = 12;

/* The list page: what the period cost, day by day, by kind, and every session. */
export function TokenOverview({ data, onOpen }: {
  data: AiTraceOverview;
  onOpen: (session: AiSession) => void;
}) {
  const k = data.kpis;
  const paged = usePaged(data.sessions, SESSIONS_PER_PAGE, data.kind + data.period);
  const kindTotal = data.byKind.reduce((sum, row) => sum + row.tokens, 0);

  return (
    <div className="db-page">
      <div className="db-kpis">
        <Kpi
          label="Toplam token"
          value={compact(k.totalTokens)}
          sub={<>girdi {tokens(k.promptTokens)} · çıktı {tokens(k.completionTokens)}</>}
        />
        <Kpi label="Oturum" value={k.sessions} sub={k.aiRuns ? k.aiRuns + " AI ile çalıştır" : "AI ile çalıştır yok"} />
        <Kpi
          label="Ort. / AI ile çalıştır"
          value={k.avgPerAiRun == null ? "—" : compact(k.avgPerAiRun)}
          sub={k.avgPerAiRun == null ? "biten ajan koşusu yok" : tokens(k.avgPerAiRun) + " token"}
        />
        <Kpi
          label="Önbellekten"
          value={k.cachedTokens == null ? "—" : compact(k.cachedTokens)}
          sub={k.cachedTokens == null
            ? "sunucu bildirmiyor"
            : "girdinin " + percent(k.cachedTokens, k.promptTokens) + "'i önbellekten"}
        />
        <Kpi
          label="En pahalı oturum"
          value={k.mostExpensive ? compact(k.mostExpensive.totalTokens) : "—"}
          sub={k.mostExpensive ? (
            <button type="button" className="unstyled-button tk-link" onClick={() => onOpen(k.mostExpensive!)}>
              {k.mostExpensive.title || KIND_WORD[k.mostExpensive.kind]}
            </button>
          ) : "bu dönemde oturum yok"}
        />
      </div>

      <div className="db-grid">
        <Card
          title="Günlük token"
          aside={<span className="db-legend"><span className="db-legend-item tk-in">girdi</span><span className="db-legend-item tk-out">çıktı</span></span>}
          note="Çubuk = o gün başlayan oturumların girdi ve çıktı token'ı. Sayı sunucunun bildirdiği kesin rakamdır."
        >
          <DailyTokens days={data.daily} />
        </Card>

        <Card title="Türe göre" aside={"toplam " + compact(kindTotal)}>
          {data.byKind.length === 0 ? <p className="db-empty">Bu dönemde AI çağrısı yok.</p> : (
            <ul className="tk-kinds">
              {data.byKind.map((row) => (
                <li key={row.kind}>
                  <div className="tk-kind-head">
                    <span className="db-screen-name">{KIND_WORD[row.kind] ?? row.kind}</span>
                    <span className="tk-num">{tokens(row.tokens)} <span className="tk-dim">· {row.sessions} oturum · {percent(row.tokens, kindTotal)}</span></span>
                  </div>
                  <div className="tk-meter" aria-hidden="true">
                    <span style={{ width: (kindTotal ? (100 * row.tokens) / kindTotal : 0) + "%" }} />
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>

      <Card
        title="Oturumlar"
        aside={data.running ? <span className="tk-live">● canlı — 2 sn'de bir yenileniyor</span> : "en yeni üstte"}
        note={data.truncated ? "Liste en yeni " + data.sessions.length + " oturumu gösteriyor; toplamlar hepsini sayar." : undefined}
      >
        {data.sessions.length === 0 ? (
          <p className="db-empty">Bu dönemde kaydedilmiş AI oturumu yok. Kayıtlı Senaryolar'da bir senaryoyu "AI ile çalıştır"ın, burada görünür.</p>
        ) : (
          <>
            <div className="db-table-wrap">
              <table className="registry db-table tk-sessions">
                <thead>
                  <tr>
                    <th>Oturum</th>
                    <th>Tür</th>
                    <th>Tur</th>
                    <th>Tool</th>
                    <th>Girdi</th>
                    <th>Çıktı</th>
                    <th>Toplam</th>
                    <th>Süre</th>
                    <th>Durum</th>
                    <th>Ne zaman</th>
                  </tr>
                </thead>
                <tbody>
                  {paged.slice.map((s) => (
                    <tr key={s.id} className="is-clickable" onClick={() => onOpen(s)}>
                      <td>
                        <button type="button" className="unstyled-button db-name tk-row-open" title={s.title ?? ""}
                                onClick={(event) => { event.stopPropagation(); onOpen(s); }}>
                          {s.title || "(adsız)"}
                        </button>
                        <div className="sc-path">{[s.product, s.flow, s.cif].filter(Boolean).join(" · ") || s.models || ""}</div>
                      </td>
                      <td>{KIND_WORD[s.kind] ?? s.kind}</td>
                      <td className="h-mono">{s.turns}</td>
                      <td className="h-mono">{s.toolCalls || "—"}</td>
                      <td className="h-mono">{tokens(s.promptTokens)}</td>
                      <td className="h-mono">{tokens(s.completionTokens)}</td>
                      <td className="h-mono tk-strong">
                        {tokens(s.totalTokens)}
                        {s.kind === "ai-run" && data.aiRunBudget > 0 && s.totalTokens > data.aiRunBudget ? (
                          <div className="tk-over" title={"Hedef " + tokens(data.aiRunBudget) + " token"}>bütçe aşıldı</div>
                        ) : null}
                      </td>
                      <td className="h-mono">{formatDuration(s.durationMs)}</td>
                      <td><SessionStatus status={s.status} /></td>
                      <td className="h-mono">{relativeTime(s.startedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {paged.pager}
          </>
        )}
      </Card>
    </div>
  );
}

/** Input and output per day, stacked, with a y-axis in tokens. */
function DailyTokens({ days }: { days: AiTraceDay[] }) {
  const max = niceMax(Math.max(0, ...days.map((d) => d.prompt + d.completion)));
  return (
    <div className="db-chart">
      <div className="db-axis tk-axis" aria-hidden="true">
        <span>{compact(max)}</span>
        <span>{compact(Math.round(max / 2))}</span>
        <span>0</span>
      </div>
      <div className="db-plot">
        <div className="db-lines" aria-hidden="true"><span /><span /><span /></div>
        <div className="db-daily">
          {days.map((d) => {
            const total = d.prompt + d.completion;
            const label = new Date(d.date + "T12:00:00").toLocaleDateString("tr-TR", { day: "numeric", month: "short" });
            const describe = tokens(total) + " token: girdi " + tokens(d.prompt) + ", çıktı " + tokens(d.completion)
              + " · " + d.sessions + " oturum";
            return (
              <div key={d.date} className="db-day" title={label + " — " + describe} role="img" aria-label={label + ", " + describe}>
                <span className="db-day-total">{total ? compact(total) : ""}</span>
                <span className="db-day-col" style={{ height: (100 * total) / max + "%" }}>
                  {d.prompt > 0 ? <span className="db-seg tk-in" style={{ flexGrow: d.prompt }} /> : null}
                  {d.completion > 0 ? <span className="db-seg tk-out" style={{ flexGrow: d.completion }} /> : null}
                </span>
                <span className="db-day-label">{label}</span>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
