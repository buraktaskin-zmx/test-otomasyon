import type { ReactNode } from "react";

/** The .empty / .empty-title pair app.css already styles, in one place. */
export function EmptyState({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="empty">
      <div className="empty-title">{title}</div>
      {children}
    </div>
  );
}
