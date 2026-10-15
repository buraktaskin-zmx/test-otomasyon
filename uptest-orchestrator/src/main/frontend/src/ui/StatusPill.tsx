import type { RunStatus } from "../api/types";

/*
  app.js built this string in four separate places ('pill pill-' + esc(status)),
  which is exactly the kind of duplication that lets two call sites drift. One
  component, two callers today: the timeline header and the history grid.

  An absent status renders as UNKNOWN rather than as an empty pill, because
  app.css only styles the five named variants.
*/
export function StatusPill({ status }: { status: RunStatus | null | undefined }) {
  const value: RunStatus = status ?? "UNKNOWN";
  return <span className={"pill pill-" + value}>{value}</span>;
}
