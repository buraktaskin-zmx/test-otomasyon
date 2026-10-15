import { useCallback, useEffect, useState } from "react";
import { getExploreTools } from "../api/endpoints";
import type { ExploreTool } from "../api/types";

interface ToolsState {
  /** false when the endpoint 404s -- uptest.explore.enabled=false, so the
   *  three /explore* endpoints do not exist at all. Caller hides the whole
   *  Ajan tab on this, rather than rendering a tab whose buttons 404. */
  available: boolean;
  count: number;
  tools: ExploreTool[];
  loaded: boolean;
}

export function useExploreTools() {
  const [state, setState] = useState<ToolsState>({ available: true, count: 0, tools: [], loaded: false });

  const load = useCallback(async () => {
    const result = await getExploreTools();
    if (!result.ok) {
      setState({ available: false, count: 0, tools: [], loaded: true });
      return;
    }
    setState({ available: true, count: result.body.count, tools: result.body.tools, loaded: true });
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  return { ...state, reload: load };
}
