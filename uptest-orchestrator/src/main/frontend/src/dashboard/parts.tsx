import { useEffect, useState } from "react";
import type { ReactNode } from "react";
import type { DashboardFailing, DashboardKpis } from "../api/types";

/*
  The dashboard's small pieces.

  CHART MARKS ARE BLUE AND AMBER, not the green/red of the status pills, and
  that is measured rather than taste: for a deuteranope green↔red separate by
  ΔE 6.4, which is inside the "needs a second encoding" band, while blue↔amber
  separate by 22.2. The pills keep green/red — they carry a word, they are
  shared with Geçmiş, and one pill is read on its own, never against another.
  Every bar still prints its counts in words, and segments keep a 2px gap.
*/

export type Segment = "passed" | "failed" | "canceled" | "unknown" | "running" | "never" | "notRunnable";

export const SEGMENT_WORD: Record<Segment, string> = {
  passed: "başarılı",
  failed: "başarısız",
  canceled: "durduruldu",
  unknown: "bilinmiyor",
  running: "çalışıyor",
  never: "hiç koşmadı",
  notRunnable: "koşulamaz",
};

/** A run or scenario status as the pill classes app.css already has. */
export function StatusWord({ status }: { status: string | null }) {
  const map: Record<string, { word: string; pill: string }> = {
    DONE: { word: "başarılı", pill: "DONE" },
    FAILED: { word: "başarısız", pill: "FAILED" },
    CANCELED: { word: "durduruldu", pill: "CANCELED" },
    RUNNING: { word: "çalışıyor", pill: "RUNNING" },
    UNKNOWN: { word: "bilinmiyor", pill: "UNKNOWN" },
    NEVER_RUN: { word: "hiç koşmadı", pill: "UNKNOWN" },
    NOT_RUNNABLE: { word: "koşulamaz", pill: "UNKNOWN" },
  };
  const entry = map[status ?? "UNKNOWN"] ?? map.UNKNOWN!;
  return <span className={"pill pill-" + entry.pill}>{entry.word}</span>;
}

/** "≈": the number rests on matching runs to scenarios by title (stage 1). */
export function Approx({ on }: { on: boolean }) {
  if (!on) return null;
  return (
    <span className="db-approx" title="Yaklaşık: koşular senaryoya adıyla eşleştiriliyor (aşama 1)">
      ≈
    </span>
  );
}

export function Kpi({
  label,
  value,
  sub,
  approx,
  tone,
}: {
  label: string;
  value: ReactNode;
  sub?: ReactNode;
  approx?: boolean;
  tone?: "ok" | "fail";
}) {
  return (
    <div className="db-kpi">
      <div className="db-kpi-label">
        {label} <Approx on={Boolean(approx)} />
      </div>
      <div className={"db-kpi-value" + (tone ? " is-" + tone : "")}>{value}</div>
      {sub ? <div className="db-kpi-sub">{sub}</div> : null}
    </div>
  );
}


/** "+9" / "-3" / "" — the change against the previous period. */
export function delta(now: number, before: number): string {
  const d = now - before;
  return d === 0 ? "" : (d > 0 ? "+" : "") + d;
}

/** Library and period KPIs, as both pages show them. */
export function KpiRow({ k, librarySub }: { k: DashboardKpis; librarySub: ReactNode }) {
  return (
    <div className="db-kpis">
      <div className="db-kpi-group" aria-label="Kütüphane — dönemden bağımsız">
        <Kpi label="Toplam senaryo" value={k.scenarios} sub={librarySub} />
        <Kpi label="Koşulabilir" value={k.runnable} />
      </div>
      {/* Per RUN, not per scenario: the same scenario run four times reads 4
          here. The old cards counted each scenario once, by its LAST run, so
          repeating a run changed nothing on screen and the dashboard looked
          like it was not recording. Coverage ("how much of the library has
          been touched") is a different question and lives in the screen
          table, where it cannot be mistaken for a run count. */}
      <div className="db-kpi-group" aria-label="Seçili dönem">
        <Kpi
          label="Çalıştırılan"
          approx={k.approximate}
          value={<>{k.runs} <small>{delta(k.runs, k.previous.runs)}</small></>}
        />
        <Kpi
          label="Başarılı"
          approx={k.approximate}
          tone="ok"
          value={<>{k.runsPassed} <small>{delta(k.runsPassed, k.previous.passed)}</small></>}
        />
        <Kpi
          label="Başarısız"
          approx={k.approximate}
          tone="fail"
          value={<>{k.runsFailed} <small>{delta(k.runsFailed, k.previous.failed)}</small></>}
        />
      </div>
    </div>
  );
}

/** One horizontal bar of status segments with its counts in words underneath. */
export function StackBar({ parts, total }: { parts: Array<[Segment, number]>; total: number }) {
  const shown = parts.filter(([, n]) => n > 0);
  return (
    <>
      <div className="db-bar" role="img"
           aria-label={shown.map(([s, n]) => n + " " + SEGMENT_WORD[s]).join(", ") || "veri yok"}>
        {shown.map(([s, n]) => (
          <span key={s} className={"db-seg is-" + s} style={{ flexGrow: n }}
                title={n + " " + SEGMENT_WORD[s]} />
        ))}
        {total === 0 ? <span className="db-seg is-never" style={{ flexGrow: 1 }} /> : null}
      </div>
      <div className="db-bar-legend">
        {shown.map(([s, n]) => (
          <span key={s} className={"db-count is-" + s}>{n} {SEGMENT_WORD[s]}</span>
        ))}
      </div>
    </>
  );
}

export function Card({ title, aside, children, note }: {
  title: string;
  aside?: ReactNode;
  children: ReactNode;
  note?: ReactNode;
}) {
  return (
    <section className="db-card">
      <div className="db-card-head">
        <h3 className="db-card-title">{title}</h3>
        {aside ? <span className="db-card-aside">{aside}</span> : null}
      </div>
      {children}
      {note ? <p className="db-note">{note}</p> : null}
    </section>
  );
}

/** A card whose data does not exist yet: says so instead of a made-up number. */
export function Pending({ title, stage, text }: { title: string; stage: string; text: string }) {
  return (
    <Card title={title} aside={<span className="db-stage">{stage}</span>}>
      <p className="db-empty">{text}</p>
    </Card>
  );
}

/**
 * A page of a long list: the slice, and the controls under it.
 *
 * Paging rather than a taller card, because these lists grow without bound
 * (fifty runs, thirty failing steps) and a card that grows with them pushes
 * everything below it off the screen.
 */
export function usePaged<T>(items: T[], size: number, resetKey?: unknown) {
  const [page, setPage] = useState(0);
  const pages = Math.max(1, Math.ceil(items.length / size));
  // A filter changed under us: page 3 of the old list is not page 3 of this one.
  useEffect(() => setPage(0), [resetKey, items.length]);
  const current = Math.min(page, pages - 1);
  return {
    slice: items.slice(current * size, current * size + size),
    pager: items.length > size
      ? <Pager page={current} pages={pages} total={items.length} size={size} onPage={setPage} />
      : null,
  };
}

function Pager({ page, pages, total, size, onPage }: {
  page: number;
  pages: number;
  total: number;
  size: number;
  onPage: (page: number) => void;
}) {
  return (
    <div className="db-pager">
      <span className="db-pager-count">
        {page * size + 1}–{Math.min(total, (page + 1) * size)} / {total}
      </span>
      <button type="button" className="btn btn-ghost" disabled={page === 0}
              onClick={() => onPage(page - 1)} aria-label="Önceki sayfa">
        ‹ Önceki
      </button>
      <span className="db-pager-count">{page + 1} / {pages}</span>
      <button type="button" className="btn btn-ghost" disabled={page >= pages - 1}
              onClick={() => onPage(page + 1)} aria-label="Sonraki sayfa">
        Sonraki ›
      </button>
    </div>
  );
}

export function FailingList({ failing, pageSize = 5 }: { failing: DashboardFailing; pageSize?: number }) {
  const { slice, pager } = usePaged(failing.steps, pageSize);
  if (failing.steps.length === 0 && failing.loginFailures === 0) {
    return <p className="db-empty">Bu dönemde düşen adım yok.</p>;
  }
  return (
    <>
      <ul className="db-failing">
        {slice.map((step) => (
          <li key={step.step}>
            <div className="db-failing-step">{step.step}</div>
            {step.lastError ? <div className="db-failing-why">{step.lastError}</div> : null}
          </li>
        ))}
        {/* Always visible, on every page: it is a total, not a row of the list. */}
        {failing.loginFailures > 0 ? (
          <li className="is-login">
            <div className="db-failing-step">Giriş başarısız</div>
          </li>
        ) : null}
      </ul>
      {pager}
    </>
  );
}

export function relativeTime(iso: string | null): string {
  if (!iso) return "—";
  const ms = Date.now() - new Date(iso).getTime();
  if (isNaN(ms)) return "—";
  const min = Math.round(ms / 60000);
  if (min < 1) return "şimdi";
  if (min < 60) return min + " dk önce";
  const hours = Math.round(min / 60);
  if (hours < 24) return hours + " sa önce";
  return Math.round(hours / 24) + " gün önce";
}
