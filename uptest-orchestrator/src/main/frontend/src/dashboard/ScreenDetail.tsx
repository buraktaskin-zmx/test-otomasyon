import { useState } from "react";
import { formatDuration } from "../lib/format";
import type { DashboardScenarioRow, DashboardScreenDetail } from "../api/types";
import { Approx, Card, FailingList, KpiRow, StatusWord, relativeTime, usePaged } from "./parts";

/** Rows per page, as the overview's run table uses. */
const PER_PAGE = 12;

/* Ekran detayı — docs/DASHBOARD.md §5. */

type Filter = "all" | "failed" | "never" | "notRunnable";

const FILTERS: Array<[Filter, string]> = [
  ["all", "tümü"], ["failed", "başarısız"], ["never", "hiç koşmadı"], ["notRunnable", "koşulamaz"],
];

const RISK_WORD: Record<string, string> = {
  tarih: "tarihe bağlı veri",
  yetki: "yetki eksik",
  veri: "veri gerekli",
};

export function ScreenDetail({
  data,
  onRerunFailed,
  onOpenScenario,
}: {
  data: DashboardScreenDetail;
  /** The failed, runnable scenarios of this screen, handed to the run queue. */
  onRerunFailed: (paths: string[]) => void;
  onOpenScenario: (path: string, title: string) => void;
}) {
  const [filter, setFilter] = useState<Filter>("all");
  const approx = data.kpis.approximate;
  const failedPaths = data.scenarios
    .filter((s) => s.runnable && s.lastStatus === "FAILED")
    .map((s) => s.path);
  const rows = data.scenarios.filter((s) => matches(s, filter));
  const paged = usePaged(rows, PER_PAGE, filter);

  return (
    <div className="db-page">
      <p className="db-screen-meta">{data.productName}</p>

      <KpiRow k={data.kpis} librarySub="bu ekranda" />

      <div className="db-grid">
        <Card title="Başlık bazında senaryolar" aside={<Approx on={approx} />}>
          {data.headings.length === 0 ? <p className="db-empty">Bu ekranda senaryo yok.</p> : (
            <div className="db-list-wrap"><ul className="db-screens">
              {data.headings.map((h) => (
                <li key={h.name}>
                  <div className="db-screen">
                    <span className="db-screen-name">{h.name}</span>
                    <span className="db-screen-total">toplam senaryo {h.total}</span>
                  </div>
                </li>
              ))}
            </ul></div>
          )}
        </Card>

        <Card title="Bu ekranda takılan adımlar">
          <FailingList failing={data.failing} />
        </Card>
      </div>

      <Card
        title="Senaryolar"
        aside={rows.length + " / " + data.scenarios.length + " gösteriliyor"}
        note="Satıra tıklayınca senaryonun son 30 günlük koşuları açılır."
      >
        <div className="db-filters">
          {FILTERS.map(([id, word]) => (
            <button
              key={id}
              type="button"
              className={"btn btn-ghost db-chip" + (filter === id ? " is-active" : "")}
              aria-pressed={filter === id}
              onClick={() => setFilter(id)}
            >
              {word}
            </button>
          ))}
          <button
            type="button"
            className="btn btn-primary db-rerun"
            disabled={failedPaths.length === 0}
            title={failedPaths.length === 0 ? "Son durumu başarısız senaryo yok"
              : "Kayıtlı Senaryolar'da seçili açılır; CIF'i görüp Çalıştır'a basın"}
            onClick={() => onRerunFailed(failedPaths)}
          >
            Düşenleri tekrar çalıştır{failedPaths.length ? " (" + failedPaths.length + ")" : ""}
          </button>
        </div>
        <div className="db-table-wrap">
          <table className="registry db-table">
            <thead>
              <tr>
                <th>Senaryo</th>
                <th>Başlık</th>
                <th>Son durum <Approx on={approx} /></th>
                <th>Adım <Approx on={approx} /></th>
                <th>Süre <Approx on={approx} /></th>
                <th>Son 10 koşu</th>
                <th>Son koşu</th>
                <th>Kural</th>
                <th>Ön koşul</th>
              </tr>
            </thead>
            <tbody>
              {paged.slice.map((s) => (
                <tr key={s.path} className="is-clickable" onClick={() => onOpenScenario(s.path, s.title)}>
                  <td>
                    <button type="button" className="unstyled-button db-name">{s.title}</button>
                    <div className="sc-path">{s.path}</div>
                  </td>
                  <td>{s.heading ?? "Başlıksız"}</td>
                  <td><StatusWord status={s.lastStatus} /></td>
                  <td className="h-mono">{s.total ? s.passed + "/" + s.total : "—"}</td>
                  <td className="h-mono">{s.durationMs ? formatDuration(s.durationMs) : "—"}</td>
                  <td><LastTen statuses={s.last10} /></td>
                  <td className="h-mono">
                    {relativeTime(s.lastRunAt)}
                    {s.lastCif ? <div className="sc-path">CIF {s.lastCif}</div> : null}
                  </td>
                  <td className="h-mono">
                    {s.ruleCount}
                    {s.stale ? <span className="sc-stale"> · bayat</span> : null}
                  </td>
                  <td>
                    {s.risks.length === 0 ? "—" : s.risks.map((risk) => (
                      <span key={risk} className="db-risk">{RISK_WORD[risk] ?? risk}</span>
                    ))}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {paged.pager}
      </Card>
    </div>
  );
}

function matches(s: DashboardScenarioRow, filter: Filter): boolean {
  switch (filter) {
    case "failed": return s.lastStatus === "FAILED";
    case "never": return s.lastStatus === "NEVER_RUN";
    case "notRunnable": return s.lastStatus === "NOT_RUNNABLE";
    default: return true;
  }
}

/** Ten dots, oldest left, newest right, in the status colours (DESIGN.md §1). */
function LastTen({ statuses }: { statuses: string[] }) {
  if (statuses.length === 0) return <span className="db-empty">—</span>;
  const word: Record<string, string> = {
    DONE: "başarılı", FAILED: "başarısız", CANCELED: "durduruldu", UNKNOWN: "bilinmiyor",
  };
  const cls: Record<string, string> = {
    DONE: "passed", FAILED: "failed", CANCELED: "canceled", UNKNOWN: "unknown",
  };
  const label = statuses.map((s) => word[s] ?? s).join(", ");
  return (
    <span className="db-dots" role="img" aria-label={"Son koşular, eskiden yeniye: " + label} title={label}>
      {statuses.map((s, i) => <span key={i} className={"db-dot db-seg is-" + (cls[s] ?? "unknown")} />)}
    </span>
  );
}
