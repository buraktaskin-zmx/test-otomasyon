import { VariationChips } from "./VariationChips";
import type { Draft } from "./draft";

/* index.html:45-49. Visible only while a draft from a batch is open in the
   editor -- tells the analyst which of N drafts they are looking at and
   how to get back to the list. */
export function MultiBar({
  position,
  total,
  draft,
  onBack,
}: {
  position: number;
  total: number;
  draft: Draft;
  onBack: () => void;
}) {
  return (
    <div id="multiBar" className="multi-bar">
      <button id="btnBackToList" className="btn btn-ghost multi-back" type="button" onClick={onBack}>
        ← Tümü
      </button>
      <span id="multiPosition" className="multi-position">
        Senaryo {position} / {total}
      </span>
      <span id="multiChips" className="multi-chips">
        <VariationChips draft={draft} />
      </span>
    </div>
  );
}
