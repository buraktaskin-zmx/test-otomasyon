import { useCallback, useRef, useState } from "react";

/*
  Runs one async action at a time and reports whether it is still running, so a
  button can disable itself without any component tracking that by hand.

  The reason this is a hook and not four copies of `btn.disabled = true` is the
  bug it removes: in app.js, run() set disabled and re-enabled it inside .then,
  with no .catch -- so a network failure left the button dead until a reload.
  Here the flag is cleared in a finally, which has no path around it.

  The ref guard matters for the agent buttons specifically: those requests take
  minutes, and a double click would otherwise drive the browser twice.
*/
export function useAsyncAction<A extends unknown[]>(
  action: (...args: A) => Promise<void>,
): { run: (...args: A) => void; busy: boolean } {
  const [busy, setBusy] = useState(false);
  const inFlight = useRef(false);

  const run = useCallback(
    (...args: A) => {
      if (inFlight.current) return;
      inFlight.current = true;
      setBusy(true);
      void action(...args).finally(() => {
        inFlight.current = false;
        setBusy(false);
      });
    },
    [action],
  );

  return { run, busy };
}
