import { useState } from "react";
import { artifactHref } from "../api/endpoints";
import { formatDuration } from "../lib/format";
import type { DashboardDay, DashboardOverview, DashboardScreenBar } from "../api/types";
import { Approx, Card, FailingList, KpiRow, Pending, StackBar, StatusWord, relativeTime } from "./parts";
import type { Segment } from "./parts";
import { SEGMENT_WORD } from "./parts";

/* Genel Bakış — docs/DASHBOARD.md §4. */
export function Overview({
  data,
  onOpenScreen,
  onPickCif,
  onOpenScenario,
}: {
  data: DashboardOverview;
  onOpenScreen: (screen: DashboardScreenBar) => void;
  onPickCif: (cif: string) => void;
  onOpenScenario: (app: string, product: string, path: string, title: string) => void;
}) {
  const [day, setDay] = useState<string | null>(null);
  const screens = new Set(data.screens.map((s) => s.app + "/" + s.product + "/" + s.flow));
  const runs = day
    ? data.recentRuns.filter((run) => localDate(run.submittedAt) === day)
    : data.recentRuns;

  return (
    <div className="db-page">
      <KpiRow k={data.kpis} librarySub={screens.size + " ekran"} />

      <div className="db-grid">
        <Card
          title="Günlük çalıştırmalar"
          note="Çubuk = o gün başlayan koşu sayısı (senaryo değil). Bir güne tıklayınca aşağıdaki liste o güne süzülür."
        >
          <DailyChart days={data.daily} selected={day} onSelect={(d) => setDay(d === day ? null : d)} />
        </Card>

        <Card title="Ekran bazında senaryolar" aside={<>toplam {data.kpis.scenarios} <Approx on={data.kpis.approximate} /></>}>
          {data.screens.length === 0 ? (
            <p className="db-empty">Kütüphanede senaryo yok.</p>
          ) : (
            <ul className="db-screens">
              {data.screens.map((screen) => (
                <li key={screen.app + screen.product + screen.flow}>
                  <button type="button" className="db-screen unstyled-button" onClick={() => onOpenScreen(screen)}>
                    <span className="db-screen-name">{screen.name}</span>
                    <span className="db-screen-total">{screen.total}</span>
                  </button>
                  <StackBar total={screen.total} parts={screenParts(screen)} />
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Card
          title="Son çalıştırmalar"
          aside={day ? <button type="button" className="btn btn-ghost" onClick={() => setDay(null)}>{day} · tümü</button>
            : "son " + data.recentRuns.length + " koşu · en yeni üstte"}
          note="CIF ve tetik, bu sürümden sonra başlayan koşularda dolar; eski koşularda boş kalır."
        >
          {runs.length === 0 ? <p className="db-empty">Bu dönemde koşu yok.</p> : (
            <div className="db-table-wrap">
              <table className="registry db-table">
                <thead>
                  <tr>
                    <th>Senaryo</th>
                    <th>Ekran</th>
                    <th>CIF</th>
                    <th>Tetik</th>
                    <th>Durum</th>
                    <th>Adım</th>
                    <th>Süre</th>
                    <th>Ne zaman</th>
                    <th aria-label="Çıktılar" />
                  </tr>
                </thead>
                <tbody>
                  {runs.map((run) => (
                    <tr key={run.runId}>
                      <td>
                        <div className="db-name">{run.scenario || "(isimsiz)"}</div>
                        <div className="sc-path">{run.path ?? "kayıtlı bir senaryoyla eşleşmedi"}</div>
                      </td>
                      <td>{run.screen ?? "—"}</td>
                      <td className="h-mono">{run.cif ?? "—"}</td>
                      <td>{run.trigger === "queue" ? "kuyruk" : run.trigger === "single" ? "tekli" : "—"}</td>
                      <td><StatusWord status={run.status} /></td>
                      <td className="h-mono">{run.total ? run.passed + "/" + run.total : "—"}</td>
                      <td className="h-mono">{run.durationMs ? formatDuration(run.durationMs) : "—"}</td>
                      <td className="h-mono">{relativeTime(run.submittedAt)}</td>
                      <td className="h-links">
                        <a className="artifact" target="_blank" rel="noopener" href={artifactHref(run.runId, "report.html")}>Rapor</a>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>

        <Card
          title="En çok takılan adımlar"
          note="Sayılar koşu sayısıdır. Açıklamalar executor'ın kendi hata mesajının ilk satırıdır."
        >
          <FailingList failing={data.failing} />
        </Card>
      </div>

      <Card title="CIF bazında özet" aside="satıra tıklayınca dashboard o CIF'e süzülür"
            note="Yalnızca CIF'i kaydedilmiş koşular (bu sürümden sonra başlayanlar) sayılır.">
        {data.cifs.length === 0 ? (
          <p className="db-empty">Bu dönemde CIF'i kaydedilmiş koşu yok.</p>
        ) : (
          <div className="db-table-wrap">
            <table className="registry db-table">
              <thead>
                <tr>
                  <th>CIF · test kullanıcısı</th>
                  <th>Koşu</th>
                  <th>Başarılı</th>
                  <th>Başarısız</th>
                  <th>Girişte düşen</th>
                  <th>Son koşu</th>
                </tr>
              </thead>
              <tbody>
                {data.cifs.map((row) => (
                  <tr key={row.cif} className="is-clickable" onClick={() => onPickCif(row.cif)}>
                    <td>
                      <button type="button" className="unstyled-button db-name">{row.cif}</button>
                      {row.name ? <div className="sc-path">{row.name}</div> : null}
                    </td>
                    <td className="h-mono">{row.runs}</td>
                    <td className="h-mono db-count is-passed">{row.passed}</td>
                    <td className="h-mono db-count is-failed">{row.failed}</td>
                    <td className="h-mono">{row.loginFailures}</td>
                    <td className="h-mono">{relativeTime(row.lastRunAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <div className="db-grid">
        <Card title="Kararsız senaryolar" aside={<Approx on={data.kpis.approximate} />}
              note="Bu dönemde hem geçen hem düşen senaryolar.">
          {data.flaky.length === 0 ? <p className="db-empty">Bu dönemde kararsız senaryo yok.</p> : (
            <ul className="db-failing">
              {data.flaky.map((f) => (
                <li key={f.app + f.product + f.path}>
                  <button type="button" className="unstyled-button db-failing-step db-link"
                          onClick={() => onOpenScenario(f.app, f.product, f.path, f.title)}>
                    {f.title}
                  </button>
                  <div className="db-failing-why">
                    {f.screen}{f.lastFailedStep ? " · son düştüğü adım: " + f.lastFailedStep : ""}
                  </div>
                  <span className="db-failing-count">{f.passed} geçti · {f.failed} düştü</span>
                </li>
              ))}
            </ul>
          )}
        </Card>
        <Pending title="AI üretim hattı" stage="aşama 3" text="AI üretim metrikleri henüz kaydedilmiyor." />
      </div>
    </div>
  );
}

function screenParts(s: DashboardScreenBar): Array<[Segment, number]> {
  return [["passed", s.passed], ["failed", s.failed], ["never", s.neverRun], ["notRunnable", s.notRunnable]];
}

/** The analysts' day, as the server cut it (Europe/Istanbul). */
function localDate(iso: string): string {
  return new Date(iso).toLocaleDateString("sv-SE", { timeZone: "Europe/Istanbul" });
}

const DAY_SEGMENTS: Array<[Segment, keyof DashboardDay]> = [
  ["passed", "passed"], ["failed", "failed"], ["canceled", "canceled"],
  ["unknown", "unknown"], ["running", "running"],
];

function DailyChart({ days, selected, onSelect }: {
  days: DashboardDay[];
  selected: string | null;
  onSelect: (date: string) => void;
}) {
  const totals = days.map((d) => d.passed + d.failed + d.canceled + d.unknown + d.running);
  const max = Math.max(1, ...totals);
  return (
    <>
      <div className="db-legend">
        {DAY_SEGMENTS.slice(0, 4).map(([s]) => (
          <span key={s} className={"db-legend-item is-" + s}>{SEGMENT_WORD[s]}</span>
        ))}
      </div>
      <div className="db-daily">
        {days.map((d, i) => {
          const label = new Date(d.date + "T12:00:00").toLocaleDateString("tr-TR", { day: "numeric", month: "short" });
          const parts = DAY_SEGMENTS.filter(([, key]) => (d[key] as number) > 0);
          const describe = totals[i] + " koşu: " + parts.map(([s, key]) => d[key] + " " + SEGMENT_WORD[s]).join(", ");
          return (
            <button
              key={d.date}
              type="button"
              className={"db-day unstyled-button" + (selected === d.date ? " is-selected" : "")}
              title={label + " — " + describe}
              aria-label={label + ", " + describe}
              aria-pressed={selected === d.date}
              onClick={() => onSelect(d.date)}
            >
              <span className="db-day-total">{totals[i] || ""}</span>
              <span className="db-day-col" style={{ height: (100 * (totals[i] ?? 0)) / max + "%" }}>
                {parts.map(([s, key]) => (
                  <span key={s} className={"db-seg is-" + s} style={{ flexGrow: d[key] as number }} />
                ))}
              </span>
              <span className="db-day-label">{label}</span>
            </button>
          );
        })}
      </div>
    </>
  );
}
