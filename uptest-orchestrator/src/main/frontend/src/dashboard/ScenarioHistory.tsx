import { artifactHref } from "../api/endpoints";
import { formatDuration } from "../lib/format";
import type { DashboardHistory } from "../api/types";
import { Card, StatusWord, relativeTime } from "./parts";

/* Senaryo geçmişi — docs/DASHBOARD.md §6: every run of one scenario, 30 days. */
export function ScenarioHistory({ data }: { data: DashboardHistory }) {
  return (
    <div className="db-page">
      <p className="db-screen-meta">
        {data.screen} · {data.heading ?? "Başlıksız"} · <code>{data.path}</code>
        {data.rules.length ? " · kurallar: " + data.rules.join(", ") : ""}
      </p>
      <Card title="Koşular" aside={"son 30 gün · " + data.runs.length + " koşu"}
            note="CIF, tetik ve kuyruk, bu sürümden sonra başlayan koşularda dolar.">
        {data.runs.length === 0 ? <p className="db-empty">Son 30 günde bu senaryonun koşusu yok.</p> : (
          <div className="db-table-wrap">
            <table className="registry db-table">
              <thead>
                <tr>
                  <th>Ne zaman</th>
                  <th>Durum</th>
                  <th>CIF</th>
                  <th>Tetik</th>
                  <th>Executor</th>
                  <th>Adım</th>
                  <th>Süre</th>
                  <th>Düşen adım</th>
                  <th aria-label="Çıktılar" />
                </tr>
              </thead>
              <tbody>
                {data.runs.map((run) => (
                  <tr key={run.runId}>
                    <td className="h-mono" title={new Date(run.submittedAt).toLocaleString("tr-TR")}>
                      {relativeTime(run.submittedAt)}
                    </td>
                    <td><StatusWord status={run.status} /></td>
                    <td className="h-mono">{run.cif ?? "—"}</td>
                    <td>
                      {run.trigger === "queue" ? "kuyruk" : run.trigger === "single" ? "tekli" : "—"}
                      {run.batchId ? <div className="sc-path">#{run.batchId.slice(0, 8)}</div> : null}
                    </td>
                    <td className="h-mono">{run.executor}</td>
                    <td className="h-mono">{run.total ? run.passed + "/" + run.total : "—"}</td>
                    <td className="h-mono">{run.durationMs ? formatDuration(run.durationMs) : "—"}</td>
                    <td>
                      {run.failedStep ? <div className="db-failing-step">{run.failedStep}</div> : "—"}
                      {run.failedError ? <div className="db-failing-why">{run.failedError}</div> : null}
                    </td>
                    <td className="h-links">
                      <a className="artifact" target="_blank" rel="noopener" href={artifactHref(run.runId, "report.html")}>Rapor</a>
                      <a className="artifact" target="_blank" rel="noopener" href={artifactHref(run.runId, "run.log")}>Günlük</a>
                    </td>
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
