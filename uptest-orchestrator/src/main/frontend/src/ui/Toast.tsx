import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";

/* One toast at a time, bottom centre, auto-dismissed. Same timing and the same
   aria-live region app.js used, so a screen reader behaves identically. */

const DISMISS_MS = 3800;

export type ShowToast = (message: string, isError?: boolean) => void;

const ToastContext = createContext<ShowToast | null>(null);

interface ToastState {
  message: string;
  isError: boolean;
  /* Bumped on every call so the same text twice still restarts the timer. */
  seq: number;
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toast, setToast] = useState<ToastState | null>(null);
  const timer = useRef<number | null>(null);
  const seq = useRef(0);

  const show = useCallback<ShowToast>((message, isError = false) => {
    seq.current += 1;
    setToast({ message, isError, seq: seq.current });
  }, []);

  useEffect(() => {
    if (!toast) return;
    if (timer.current !== null) window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setToast(null), DISMISS_MS);
    return () => {
      if (timer.current !== null) window.clearTimeout(timer.current);
    };
  }, [toast]);

  const className = toast
    ? "toast is-shown" + (toast.isError ? " is-error" : "")
    : "toast";

  return (
    <ToastContext.Provider value={show}>
      {children}
      <div id="toast" className={className} role="status" aria-live="polite">
        {toast?.message ?? ""}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ShowToast {
  const show = useContext(ToastContext);
  if (!show) throw new Error("useToast, ToastProvider içinde kullanılmalı");
  return show;
}
