import { useCallback, useState } from "react";
import { resolveOrigin } from "../lib/originScreen";
import type { Origin } from "../api/types";

/*
  The only module that touches origin pinning. See lib/originScreen.ts for
  the invariant itself; this is just React state wrapped around it.

  pinOrigin must be called from exactly one place in the whole app:
  SuggestPanel (and MultiPanel) on a successful generation. Never from a
  catalog <select>'s onChange.
*/
export function useOrigin() {
  const [pinned, setPinned] = useState<Origin | null>(null);

  const pinOrigin = useCallback((origin: Origin) => setPinned(origin), []);
  const clearOrigin = useCallback(() => setPinned(null), []);
  const resolve = useCallback((fallback: Origin) => resolveOrigin(pinned, fallback), [pinned]);

  return { pinned, pinOrigin, clearOrigin, resolve };
}
