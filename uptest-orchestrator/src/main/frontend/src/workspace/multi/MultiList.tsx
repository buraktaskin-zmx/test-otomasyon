import { DraftCard } from "./DraftCard";
import type { Draft } from "./draft";

/* Covers the editor rather than sitting beside it -- the list IS the view
   while no draft is open. */
export function MultiList({
  drafts,
  onOpen,
}: {
  drafts: Draft[];
  onOpen: (index: number) => void;
}) {
  return (
    <div id="multiList" className="multi-list">
      <div className="multi-head">
        <span className="multi-head-title">Üretilen senaryolar</span>
        <span className="multi-head-count">{drafts.length} senaryo</span>
      </div>
      <div className="multi-cards">
        {drafts.map((draft, index) => (
          <DraftCard key={index} index={index} draft={draft} onOpen={onOpen} />
        ))}
      </div>
    </div>
  );
}
