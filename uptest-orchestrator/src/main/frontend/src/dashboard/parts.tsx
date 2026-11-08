import type { ReactNode } from "react";
import type { DashboardFailing, DashboardKpis } from "../api/types";

/*
  The dashboard's small pieces. Colours are the STATUS tokens (DESIGN.md §1),
  never blue, and never alone: every bar has its counts in words beside it,
  and segments are separated by a gap, because green and red are only 6.4 ΔE
  apart for a deuteranope (measured with the dataviz validator).
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

export function percent(rate: number | null | undefined): string {
  return rate == null ? "—" : "%" + Math.round(rate * 100);
}

/** "+9" / "-3" / "" — the change against the previous period. */
export function delta(now: number, before: number): string {
  const d = now - before;
  return d === 0 ? "" : (d > 0 ? "+" : "") + d;
}

/** Library and period KPIs, as both pages show them. */
export function KpiRow({ k, librarySub }: { k: DashboardKpis; librarySub: ReactNode }) {
  const pointDelta = k.successRate != null && k.previous.successRate != null
    ? Math.round((k.successRate - k.previous.successRate) * 100) : null;
  return (
    <div className="db-kpis">
      <div className="db-kpi-group" aria-label="Kütüphane — dönemden bağımsız">
        <Kpi label="Toplam senaryo" value={k.scenarios} sub={librarySub} />
        <Kpi label="Koşulabilir" value={k.runnable} sub={k.notRunnable + " koşulamaz (yalnız doğal dil)"} />
      </div>
      <div className="db-kpi-group" aria-label="Seçili dönem">
        <Kpi
          label="Çalıştırılan"
          approx={k.approximate}
          value={k.ran}
          sub={"koşulabilirin " + percent(k.runnable ? k.ran / k.runnable : null)
            + " · " + k.neverRun + " hiç koşmadı"}
        />
        <Kpi
          label="Başarılı"
          approx={k.approximate}
          tone="ok"
          value={<>{k.passed} <small>{delta(k.passed, k.previous.passed)}</small></>}
          sub="son durumu geçen senaryo"
        />
        <Kpi
          label="Başarısız"
          approx={k.approximate}
          tone="fail"
          value={<>{k.failed} <small>{delta(k.failed, k.previous.failed)}</small></>}
          sub={k.failedSteps + " farklı adımda"}
        />
        <Kpi
          label="Başarı oranı"
          approx={k.approximate}
          value={<>{percent(k.successRate)} {pointDelta ? <small>{(pointDelta > 0 ? "+" : "") + pointDelta}p</small> : null}</>}
          sub={"önceki dönem " + percent(k.previous.successRate)}
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

export function FailingList({ failing }: { failing: DashboardFailing }) {
  if (failing.steps.length === 0 && failing.loginFailures === 0) {
    return <p className="db-empty">Bu dönemde düşen adım yok.</p>;
  }
  return (
    <ul className="db-failing">
      {failing.steps.map((step) => (
        <li key={step.step}>
          <div className="db-failing-step">{step.step}</div>
          {step.lastError ? <div className="db-failing-why">{step.lastError}</div> : null}
          <span className="db-failing-count">
            {step.runs}× · {step.scenarios} senaryo
          </span>
        </li>
      ))}
      {failing.loginFailures > 0 ? (
        <li className="is-login">
          <div className="db-failing-step">Giriş başarısız</div>
          <span className="db-failing-count">{failing.loginFailures} koşu</span>
        </li>
      ) : null}
    </ul>
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
