import { useCallback, useEffect, useState } from "react";
import { getDbTools, reconnectDb } from "../api/endpoints";

interface DbToolsState {
  /** false when the endpoint 404s -- uptest.db.enabled=false, so the panel
   *  is hidden entirely rather than offering a button that 404s. */
  available: boolean;
  count: number;
  tools: string[];
  /** The panel is on but the MCP server did not start; this says why. */
  error: string;
  loaded: boolean;
}

const EMPTY: DbToolsState = { available: false, count: 0, tools: [], error: "", loaded: true };

export function useDbTools() {
  const [state, setState] = useState<DbToolsState>({
    available: true, count: 0, tools: [], error: "", loaded: false,
  });

  const load = useCallback(async () => {
    const result = await getDbTools();
    if (!result.ok) {
      setState(EMPTY);
      return;
    }
    setState({
      available: true,
      count: result.body.count,
      tools: result.body.tools ?? [],
      error: result.body.error ?? "",
      loaded: true,
    });
  }, []);

  /* Restarts the Python process. The first connection is made lazily and its
     failure is remembered, so after fixing the cause (VPN, path) nothing
     short of this -- or a server restart -- would try again. */
  const reconnect = useCallback(async () => {
    const result = await reconnectDb();
    if (!result.ok) {
      setState(EMPTY);
      return false;
    }
    setState({
      available: true,
      count: result.body.count,
      tools: result.body.tools ?? [],
      error: result.body.error ?? "",
      loaded: true,
    });
    return !result.body.error;
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  return { ...state, reload: load, reconnect };
}
