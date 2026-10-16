import type { ReactNode } from "react";

/*
  index.html:33-39's pane-head, shared by the ordinary single-scenario
  editor AND Çoklu Üret's list mode -- app.js's showListMode() does not
  hide this row, it only swaps the badge text and disables both buttons
  (app.js:853-866). Extracted so neither caller has to duplicate it.
*/
export function EditorPaneHead({
  badge,
  onValidateClick,
  onRunClick,
  validateDisabled,
  runDisabled,
  extra,
}: {
  badge: ReactNode;
  onValidateClick: () => void;
  onRunClick: () => void;
  validateDisabled: boolean;
  runDisabled: boolean;
  /** Editor-only controls. Absent in Çoklu Üret's list mode, which shares
   *  this head but has no editor behind it to control. */
  extra?: ReactNode;
}) {
  return (
    <div className="pane-head">
      <h2 className="pane-title">Senaryo</h2>
      <div className="pane-actions">
        {extra ?? null}
        {badge}
        <button
          id="btnValidate"
          className="btn btn-ghost"
          type="button"
          disabled={validateDisabled}
          onClick={onValidateClick}
        >
          Doğrula
        </button>
        <button
          id="btnRun"
          className="btn btn-primary"
          type="button"
          disabled={runDisabled}
          onClick={onRunClick}
        >
          Çalıştır
        </button>
      </div>
    </div>
  );
}
