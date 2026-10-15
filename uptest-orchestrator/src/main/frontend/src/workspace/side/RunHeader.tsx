import { StatusPill } from "../../ui/StatusPill";
import { formatTime, shortRunId } from "../../lib/format";
import type { RunDetail } from "../../api/types";

/* app.js:401-411 + the "Henüz çalıştırılmadı" empty state (index.html:253-255). */
export function RunHeader({ run, onStop }: { run: RunDetail | null; onStop: () => void }) {
  if (!run) {
    return <div className="run-header run-header-empty">Henüz çalıştırılmadı.</div>;
  }

  return (
    <div className="run-header">
      <StatusPill status={run.status} />
      <span className="mono">{shortRunId(run.runId)}</span>
      <span>{run.executor}</span>
      <span>{formatTime(run.submittedAt)}</span>
      {run.status === "RUNNING" ? (
        <button className="btn btn-ghost" type="button" onClick={onStop} title="Kooperatif: asılı bir adımı öldürmez">
          Durdur
        </button>
      ) : null}
    </div>
  );
}
