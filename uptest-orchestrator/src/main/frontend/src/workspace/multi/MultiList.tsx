import { DraftCard } from "./DraftCard";
import type { Draft } from "./draft";

/* app.js:822-847 (the list shell + notes). Covers the editor rather than
   sitting beside it -- the list IS the view while no draft is open. */
export function MultiList({
  drafts,
  notes,
  onOpen,
}: {
  drafts: Draft[];
  notes: string[];
  onOpen: (index: number) => void;
}) {
  return (
    <div id="multiList" className="multi-list">
      <div className="multi-head">
        <span className="multi-head-title">Üretilen senaryolar</span>
        <span className="multi-head-count">{drafts.length} senaryo</span>
      </div>
      {notes.length > 0 ? (
        <div className="multi-notes">
          {notes.map((note, i) => (
            <div className="multi-note" key={i}>
              {note}
            </div>
          ))}
        </div>
      ) : null}
      <div className="multi-cards">
        {drafts.map((draft, index) => (
          <DraftCard key={index} index={index} draft={draft} onOpen={onOpen} />
        ))}
      </div>
    </div>
  );
}
