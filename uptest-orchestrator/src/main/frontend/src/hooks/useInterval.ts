import { useEffect, useRef } from "react";

/*
  A repeating timer whose callback can change without restarting the clock.

  Passing the callback straight to setInterval would freeze the first closure
  and read stale state forever; adding it to the dependency array would tear the
  timer down and rebuild it on every render, so a component that re-renders
  often would never reach the end of an interval. The ref is the way out of both.

  Pass delay = null to stop.
*/
export function useInterval(callback: () => void, delay: number | null): void {
  const saved = useRef(callback);

  useEffect(() => {
    saved.current = callback;
  }, [callback]);

  useEffect(() => {
    if (delay === null) return;
    const id = window.setInterval(() => saved.current(), delay);
    return () => window.clearInterval(id);
  }, [delay]);
}
