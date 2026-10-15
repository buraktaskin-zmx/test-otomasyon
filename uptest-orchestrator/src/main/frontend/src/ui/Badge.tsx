import type { ReactNode } from "react";

export type BadgeVariant = "idle" | "ok" | "fail" | "busy";

/** Wraps app.css's `.badge.badge-{idle,ok,fail,busy}` family. One component
 *  for every badge in the app -- ValidationBadge, SuggestStatus's valid/
 *  invalid pill, DraftCard's rozet -- instead of each building its own
 *  className string. */
export function Badge({ variant, children }: { variant: BadgeVariant; children: ReactNode }) {
  return <span className={"badge badge-" + variant}>{children}</span>;
}
