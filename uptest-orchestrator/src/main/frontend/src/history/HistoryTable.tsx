import { artifactHref } from "../api/endpoints";
import { formatDuration, formatTime, shortRunId } from "../lib/format";
import { StatusPill } from "../ui/StatusPill";
import type { RunDetail } from "../api/types";

function HistoryRow({ run }: { run: RunDetail }) {
  const steps = run.steps ?? [];
  const passed = steps.filter((s) => s.status === "PASSED").length;
  const totalMs = steps.reduce((sum, s) => sum + (s.durationMs ?? 0), 0);

  return (
    <tr>
      <td>
        <StatusPill status={run.status} />
      </td>
      <td className="h-name">{run.name || "(isimsiz)"}</td>
      <td className="h-mono">{run.executor || "—"}</td>
      <td className="h-mono">{formatTime(run.submittedAt)}</td>
      {/* 0 renders as a dash, not as "0 ms": a run with no timings yet has no
          duration to report, which is a different thing from being instant. */}
      <td className="h-mono">{formatDuration(totalMs || null)}</td>
      <td className="h-mono">{steps.length ? passed + " / " + steps.length : "—"}</td>
      <td className="h-mono">{shortRunId(run.runId)}</td>
      <td className="h-links">
        <a
          className="artifact"
          target="_blank"
          rel="noopener"
          href={artifactHref(run.runId, "report.html")}
        >
          Rapor
        </a>
        <a
          className="artifact"
          target="_blank"
          rel="noopener"
          href={artifactHref(run.runId, "run.log")}
        >
          Günlük
        </a>
      </td>
    </tr>
  );
}

export function HistoryTable({ runs }: { runs: RunDetail[] }) {
  return (
    <table className="history">
      <thead>
        <tr>
          <th>Durum</th>
          <th>Senaryo</th>
          <th>Executor</th>
          <th>Başlangıç</th>
          <th>Süre</th>
          <th>Adım</th>
          <th>Koşu</th>
          <th>Dosyalar</th>
        </tr>
      </thead>
      <tbody>
        {runs.map((run) => (
          <HistoryRow key={run.runId} run={run} />
        ))}
      </tbody>
    </table>
  );
}
