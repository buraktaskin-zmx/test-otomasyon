import { useCallback, useEffect, useState } from "react";
import { getVocabulary, refreshVocabulary } from "../api/endpoints";
import type { VocabularyStep } from "../api/types";

interface VocabularyState {
  steps: VocabularyStep[];
  source: string | null;
  stepCount: number;
  loaded: boolean;
  failed: boolean;
}

const INITIAL: VocabularyState = { steps: [], source: null, stepCount: 0, loaded: false, failed: false };

/*
  Lives in App.tsx and is shared downward to both the executor pill (which
  triggers `refresh`) and the vocabulary panel (which renders `steps`) --
  one fetch, one source of truth, matching app.js's own comment that a
  second fetch "could disagree with itself".

  `refresh` is the real fix for a lie app.js told: its executor-pill click
  handler only re-GET /vocabulary, which VocabularyService caches forever,
  so the "sözlük yenilendi" toast was never true for a redeployed executor.
  This calls POST /vocabulary/refresh FIRST (which has no `steps` in its own
  response) and only then re-GETs the full list.
*/
export function useVocabulary() {
  const [state, setState] = useState<VocabularyState>(INITIAL);

  const load = useCallback(async (): Promise<boolean> => {
    const result = await getVocabulary();
    if (!result.ok) {
      setState((prev) => ({ ...prev, loaded: true, failed: true }));
      return false;
    }
    setState({
      steps: result.body.steps,
      source: result.body.source,
      stepCount: result.body.stepCount,
      loaded: true,
      failed: false,
    });
    return true;
  }, []);

  const refresh = useCallback(async (): Promise<boolean> => {
    const refreshed = await refreshVocabulary();
    if (!refreshed.ok) {
      setState((prev) => ({ ...prev, failed: true }));
      return false;
    }
    return load();
  }, [load]);

  useEffect(() => {
    void load();
  }, [load]);

  return { ...state, refresh };
}
