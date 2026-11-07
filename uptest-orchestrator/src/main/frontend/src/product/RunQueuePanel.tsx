import { useState } from "react";
import { artifactHref } from "../api/endpoints";
import { MAX_PARALLEL } from "../hooks/useRunQueue";
import type { QueueItem, QueueStatus, useRunQueue } from "../hooks/useRunQueue";

/*
  The bar under the scenario table: what is selected and Çalıştır, then the
  queue as it runs -- Durdur while anything is waiting or running, Temizle
  once it is all over. Opens from the bottom and folds away to its one-line
  summary, so a long queue never hides the table it was started from.

  A new selection wins over a finished queue's results: they stay readable
  until something else is chosen, and Geçmiş keeps every run regardless.
*/

/** Colour from the pills the history already uses; the word is Turkish. */
const LABEL: Record<QueueStatus, { word: string; pill: string }> = {
  queued: { word: "sırada", pill: "UNKNOWN" },
  starting: { word: "başlıyor", pill: "RUNNING" },
  running: { word: "çalışıyor", pill: "RUNNING" },
  stopping: { word: "durduruluyor", pill: "RUNNING" },
  passed: { word: "başarılı", pill: "DONE" },
  failed: { word: "başarısız", pill: "FAILED" },
  stopped: { word: "durduruldu", pill: "CANCELED" },
  refused: { word: "reddedildi", pill: "FAILED" },
};

const FINISHED: ReadonlySet<QueueStatus> = new Set(["passed", "failed", "stopped", "refused"]);

export function RunQueuePanel({
  queue,
  selectedCount,
  loginLabel,
  blockedReason,
  onRun,
  onClearSelection,
}: {
  queue: ReturnType<typeof useRunQueue>;
  selectedCount: number;
  /** "CIF QZ06395288", shown so nobody starts four logins as the wrong user. */
  loginLabel: string;
  /** Why Çalıştır cannot be pressed right now, or null. */
  blockedReason: string | null;
  onRun: () => void;
  onClearSelection: () => void;
}) {
  const [open, setOpen] = useState(true);
  const { items, busy } = queue;
  const selecting = !busy && selectedCount > 0;
  if (!selecting && items.length === 0) return null;

  const done = items.filter((item) => FINISHED.has(item.status)).length;
  const failed = items.filter((item) => item.status === "failed" || item.status === "refused").length;
  const title = selecting
    ? selectedCount + " senaryo seçildi"
    : busy ? "Koşu kuyruğu çalışıyor" : "Koşu kuyruğu bitti";

  return (
    <section className="rq-panel" aria-label="Koşu kuyruğu">
      <div className="rq-head">
        <button
          type="button"
          className="rq-toggle unstyled-button"
          aria-expanded={open}
          onClick={() => setOpen((was) => !was)}
        >
          <span className="sc-group-caret" aria-hidden="true">{open ? "▼" : "▲"}</span>
          <span className="rq-title">{title}</span>
        </button>
        {selecting ? (
          <span className="rq-summary">{loginLabel}</span>
        ) : (
          <>
            <span className="rq-summary">
              {done} / {items.length} bitti{failed ? " · " + failed + " başarısız" : ""}
            </span>
            <span
              className="rq-progress"
              role="progressbar"
              aria-valuemin={0}
              aria-valuemax={items.length}
              aria-valuenow={done}
              aria-label="Biten senaryolar"
            >
              <span className="rq-progress-bar" style={{ width: (items.length ? (100 * done) / items.length : 0) + "%" }} />
            </span>
          </>
        )}
        <span className="rq-actions">
          {selecting ? (
            <>
              <button type="button" className="btn btn-ghost" onClick={onClearSelection}>
                Seçimi temizle
              </button>
              <button
                type="button"
                className="btn btn-primary"
                disabled={blockedReason !== null}
                title={blockedReason ?? undefined}
                onClick={onRun}
              >
                Çalıştır
              </button>
            </>
          ) : busy ? (
            <button type="button" className="btn btn-ghost" onClick={queue.stop}>
              Durdur
            </button>
          ) : (
            <button type="button" className="btn btn-ghost" onClick={queue.clear}>
              Temizle
            </button>
          )}
        </span>
      </div>

      {open && blockedReason && selecting ? <p className="product-warn rq-note">{blockedReason}</p> : null}

      {open && !selecting && items.length > 0 ? (
        <div className="rq-body">
          <table className="registry rq-table">
            <thead>
              <tr>
                <th>#</th>
                <th>Senaryo</th>
                <th>Executor</th>
                <th>Durum</th>
                <th>Adım</th>
                <th aria-label="Çıktılar" />
              </tr>
            </thead>
            <tbody>
              {items.map((item, index) => <QueueRowView key={item.path} item={item} index={index} />)}
            </tbody>
          </table>
        </div>
      ) : null}

      {open ? (
        <p className="rq-note">
          En fazla {MAX_PARALLEL} senaryo aynı anda koşar, diğerleri sırada bekler. Bir senaryo
          düşerse kuyruk durmaz. Yalnızca Gherkin'i olan senaryolar seçilebilir.
        </p>
      ) : null}
    </section>
  );
}

function QueueRowView({ item, index }: { item: QueueItem; index: number }) {
  const label = LABEL[item.status];
  const finished = FINISHED.has(item.status);
  return (
    <tr>
      <td className="h-mono">{String(index + 1).padStart(2, "0")}</td>
      <td>
        <div className="rq-name" lang="tr">{item.title}</div>
        {item.error ? <div className="sc-problem">{item.error}</div> : null}
      </td>
      <td className="h-mono">{item.executor || "—"}</td>
      <td><span className={"pill pill-" + label.pill}>{label.word}</span></td>
      <td className="h-mono">{item.total ? (item.passed ?? 0) + " / " + item.total : "—"}</td>
      <td className="h-links">
        {item.runId && finished ? (
          <>
            <a className="artifact" target="_blank" rel="noopener" href={artifactHref(item.runId, "report.html")}>
              Rapor
            </a>
            <a className="artifact" target="_blank" rel="noopener" href={artifactHref(item.runId, "run.log")}>
              Günlük
            </a>
          </>
        ) : null}
      </td>
    </tr>
  );
}
