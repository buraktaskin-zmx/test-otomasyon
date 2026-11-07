import { useEffect, useRef } from "react";
import type { KeyboardEvent } from "react";

/*
  "Emin misiniz?" before something that cannot be undone.

  Focus starts on Vazgeç, not on the destructive button: an Enter pressed out
  of habit must cancel, never delete. Esc and a click outside the card cancel
  too; Tab stays between the two buttons while the dialog is open, and focus
  goes back to whatever opened it when it closes.
*/
export function ConfirmDialog({
  title,
  detail,
  confirmLabel,
  busy,
  onConfirm,
  onCancel,
}: {
  title: string;
  /** What is about to go, so the analyst reads WHICH one before confirming. */
  detail?: string;
  confirmLabel: string;
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const cancelRef = useRef<HTMLButtonElement>(null);
  const confirmRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    cancelRef.current?.focus();
    return () => opener?.focus?.();
  }, []);

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === "Escape") {
      event.preventDefault();
      if (!busy) onCancel();
    } else if (event.key === "Tab") {
      event.preventDefault();
      const next = document.activeElement === cancelRef.current ? confirmRef.current : cancelRef.current;
      next?.focus();
    }
  };

  return (
    <div
      className="confirm-backdrop"
      onClick={() => {
        if (!busy) onCancel();
      }}
    >
      <div
        className="confirm-card"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="confirm-title"
        aria-describedby={detail ? "confirm-detail" : undefined}
        onClick={(event) => event.stopPropagation()}
        onKeyDown={onKeyDown}
      >
        <h2 id="confirm-title" className="confirm-title">{title}</h2>
        {detail ? <p id="confirm-detail" className="confirm-detail" lang="tr">{detail}</p> : null}
        <div className="confirm-actions">
          <button ref={cancelRef} type="button" className="btn btn-ghost" disabled={busy} onClick={onCancel}>
            Vazgeç
          </button>
          <button ref={confirmRef} type="button" className="btn btn-danger" disabled={busy} onClick={onConfirm}>
            {busy ? "Siliniyor…" : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
