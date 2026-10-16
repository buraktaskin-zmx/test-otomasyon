import { useState } from "react";
import { PanelHead } from "./PanelHead";
import { EmptyState } from "../../ui/EmptyState";
import { StepPattern } from "../../ui/StepPattern";
import { filterVocabulary, groupVocabulary } from "../../lib/vocabGroups";
import type { VocabularyStep } from "../../api/types";

/* app.js:182-225 (loadVocabulary/renderVocabulary) + index.html:71-77. */
export function VocabPanel({
  active,
  steps,
  loaded,
  failed,
  onInsert,
}: {
  active: boolean;
  steps: VocabularyStep[];
  loaded: boolean;
  failed: boolean;
  onInsert: (pattern: string) => void;
}) {
  const [search, setSearch] = useState("");
  const filtered = filterVocabulary(steps, search);
  const grouped = groupVocabulary(filtered);

  return (
    <div id="side-vocab" className={"side-panel" + (active ? " is-active" : "")}>
      <PanelHead title="Adım Sözlüğü" />
      <div className="search-row">
        <input
          id="vocabSearch"
          className="search"
          type="search"
          placeholder="Adım ara…"
          aria-label="Adım ara"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
      </div>
      <div id="vocabList" className="vocab-list">
        {!loaded ? null : failed ? (
          <EmptyState title="Sözlük alınamadı">Hiçbir executor'a ulaşılamıyor.</EmptyState>
        ) : filtered.length === 0 ? (
          <EmptyState title="Eşleşen adım yok">
            {search} aramasına karşılık gelen bir adım bulunamadı.
          </EmptyState>
        ) : (
          grouped.map(({ group, steps: groupSteps }) => (
            <div key={group.title}>
              <div className="vocab-group-title">{group.title}</div>
              {groupSteps.map((step) => (
                <button
                  key={step.pattern}
                  className="vocab-item"
                  type="button"
                  title="Senaryoya ekle"
                  onClick={() => onInsert(step.pattern)}
                >
                  <StepPattern pattern={step.pattern} />
                </button>
              ))}
            </div>
          ))
        )}
      </div>
    </div>
  );
}
