import { Badge } from "../../ui/Badge";
import { previewOf } from "../../lib/multiDepth";
import { VariationChips } from "./VariationChips";
import type { Draft } from "./draft";

/* app.js:822-847 (one card). A real <button>, not a clickable div -- it was
   already written as one in app.js and theme.css's :focus-visible rule
   picks it up for free since .multi-card already resets button chrome in
   app.css (font/color: inherit). */
export function DraftCard({
  index,
  draft,
  onOpen,
}: {
  index: number;
  draft: Draft;
  onOpen: (index: number) => void;
}) {
  return (
    <button className="multi-card" type="button" onClick={() => onOpen(index)}>
      <span className="multi-card-head">
        <span className="multi-num">{index + 1}</span>
        <span className="multi-card-title">{draft.label}</span>
        {draft.validation.valid ? (
          <Badge variant="ok">{draft.validation.stepCount} adım</Badge>
        ) : (
          <Badge variant="fail">{draft.validation.errors.length} hata</Badge>
        )}
      </span>
      <span className="multi-chips">
        <VariationChips draft={draft} />
      </span>
      <span className="multi-preview">{previewOf(draft.feature)}</span>
    </button>
  );
}
