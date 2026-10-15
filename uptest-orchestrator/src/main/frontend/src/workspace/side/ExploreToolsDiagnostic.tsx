import { useState } from "react";
import type { ExploreTool } from "../../api/types";

/*
  New -- nothing in app.js surfaces this. count:0 is the ONE diagnostic that
  tells apart "the model is bad at this" from "MCP never started", which
  today only shows up as every Ajan run failing with no explanation. A
  collapsed strip by default so it does not compete for attention with the
  form above it; expands to the tool names on click.
*/
export function ExploreToolsDiagnostic({
  loaded,
  count,
  tools,
}: {
  loaded: boolean;
  count: number;
  tools: ExploreTool[];
}) {
  const [expanded, setExpanded] = useState(false);
  if (!loaded) return null;

  return (
    <div className="suggest-status">
      <button
        className="s-line unstyled-button"
        type="button"
        onClick={() => setExpanded((prev) => !prev)}
        title={count === 0 ? "Tarayıcı hiç başlatılmamış" : "Araç listesini göster"}
      >
        {count === 0 ? "MCP: 0 araç — tarayıcı yok" : "MCP: " + count + " araç"}
      </button>
      {expanded && tools.length > 0 ? (
        <div className="s-line mono">{tools.map((t) => t.name).join(", ")}</div>
      ) : null}
    </div>
  );
}
