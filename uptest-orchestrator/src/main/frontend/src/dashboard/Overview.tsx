import { useState } from "react";
import { artifactHref } from "../api/endpoints";
import { formatDuration } from "../lib/format";
import type { DashboardDay, DashboardOverview, DashboardScreenBar } from "../api/types";
import { Approx, Card, FailingList, KpiRow, StackBar, relativeTime, usePaged } from "./parts";
import type { Segment } from "./parts";
import { SEGMENT_WORD } from "./parts";

/** Rows per page. One screenful, so a card never pushes the next one away. */
const RUNS_PER_PAGE = 8;

/* Genel Bakış — docs/dashboard/README.md §4. */
export function Overview({
  data,
  onOpenScreen,
  onPickCif,
}: {
  data: DashboardOverview;
  onOpenScreen: (screen: DashboardScreenBar) => void;
  onPickCif: (cif: string) => void;
}) {
  const [day, setDay] = useState<string | null>(null);
  const screens = new Set(data.screens.map((s) => s.app + "/" + s.product + "/" + s.flow));
  const runs = day
    ? data.recentRuns.filter((run) => localDate(run.submittedAt) === day)
    : data.recentRuns;
  const paged = usePaged(runs, RUNS_PER_PAGE, day);

  return (
    <div className="db-page">
      <KpiRow k={data.kpis} librarySub={screens.size + " ekran"} />

      <div className="db-grid">
        <Card
          title="Günlük çalıştırmalar"
          aside={<DayLegend />}
          note="Çubuk = o gün başlayan koşu sayısı (senaryo değil). Bir güne tıklayınca aşağıdaki liste o güne süzülür."
        >
          <DailyChart days={data.daily} selected={day} onSelect={(d) => setDay(d === day ? null : d)} />
        </Card>

        <Card title="Ekran bazında senaryolar" aside={<>toplam {data.kpis.scenarios} <Approx on={data.kpis.approximate} /></>}>
          {data.screens.length === 0 ? (
            <p className="db-empty">Kütüphanede senaryo yok.</p>
          ) : (
            <div className="db-list-wrap">
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
            </div>
          )}
        </Card>
      </div>

      {/* Full width, one under the other: the run table has nine columns and
          a failing step's text is a whole Gherkin line -- side by side they
          each got half a panel and wrapped. */}
      <Card
          title="Son çalıştırmalar"
          aside={day
            ? <button type="button" className="btn btn-ghost" onClick={() => setDay(null)}>{day} · tümü</button>
            : "en yeni üstte"}
          note="CIF, bu sürümden sonra başlayan koşularda dolar; eski koşularda boş kalır."
        >
          {runs.length === 0 ? <p className="db-empty">Bu dönemde koşu yok.</p> : (
            <>
              <div className="db-table-wrap">
                <table className="registry db-table">
                  <thead>
                    <tr>
                      <th>Senaryo</th>
                      <th>Ekran</th>
                      <th>CIF</th>
                      <th>Adım</th>
                      <th>Süre</th>
                      <th>Ne zaman</th>
                      <th aria-label="Çıktılar" />
                    </tr>
                  </thead>
                  <tbody>
                    {paged.slice.map((run) => (
                      <tr key={run.runId}>
                        <td>
                          <div className="db-name" title={run.scenario}>{run.scenario || "(isimsiz)"}</div>
                          <div className="sc-path">{run.path ?? "kayıtlı bir senaryoyla eşleşmedi"}</div>
                        </td>
                        <td title={run.screen ?? ""}>{run.screen ?? "—"}</td>
                        <td className="h-mono">{run.cif ?? "—"}</td>
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
              {paged.pager}
            </>
          )}
      </Card>

      <Card
        title="En çok takılan adımlar"
        note="Açıklamalar executor'ın kendi hata mesajının ilk satırıdır."
      >
        <FailingList failing={data.failing} pageSize={6} />
      </Card>

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
                    <td className="h-mono">{relativeTime(row.lastRunAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

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

function DayLegend() {
  return (
    <span className="db-legend">
      {DAY_SEGMENTS.slice(0, 3).map(([s]) => (
        <span key={s} className={"db-legend-item is-" + s}>{SEGMENT_WORD[s]}</span>
      ))}
    </span>
  );
}

/**
 * The days as columns, with a y-axis: without one a bar is a shape, and the
 * question ("is thirty a lot?") needs a scale to answer.
 */
function DailyChart({ days, selected, onSelect }: {
  days: DashboardDay[];
  selected: string | null;
  onSelect: (date: string) => void;
}) {
  const totals = days.map((d) => d.passed + d.failed + d.canceled + d.unknown + d.running);
  const peak = Math.max(1, ...totals);
  // A round top, so the axis reads 30 rather than 28.
  const step = peak <= 5 ? 1 : peak <= 20 ? 5 : peak <= 60 ? 10 : 25;
  const max = Math.ceil(peak / step) * step;

  return (
    <div className="db-chart">
      <div className="db-axis" aria-hidden="true">
        <span>{max}</span>
        <span>{Math.round(max / 2)}</span>
        <span>0</span>
      </div>
      <div className="db-plot">
        <div className="db-lines" aria-hidden="true"><span /><span /><span /></div>
        <div className="db-daily">
          {days.map((d, i) => {
            const total = totals[i] ?? 0;
            const label = new Date(d.date + "T12:00:00")
              .toLocaleDateString("tr-TR", { day: "numeric", month: "short" });
            const parts = DAY_SEGMENTS.filter(([, key]) => (d[key] as number) > 0);
            const describe = total + " koşu"
              + (parts.length ? ": " + parts.map(([s, key]) => d[key] + " " + SEGMENT_WORD[s]).join(", ") : "");
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
                <span className="db-day-total">{total || ""}</span>
                <span className="db-day-col" style={{ height: (100 * total) / max + "%" }}>
                  {parts.map(([s, key]) => (
                    <span key={s} className={"db-seg is-" + s} style={{ flexGrow: d[key] as number }} />
                  ))}
                </span>
                <span className="db-day-label">{label}</span>
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
}
