import { useState } from "react";
import { EmptyState } from "../../ui/EmptyState";
import { StepPattern } from "../../ui/StepPattern";
import { filterVocabulary, groupVocabulary } from "../../lib/vocabGroups";
import type { VocabularyStep } from "../../api/types";

/*
  Adım sözlüğü — artık kendi paneli değil, koşu panelinin bir sekmesi.

  Moved because of where it is actually used. The vocabulary is read while
  WRITING a scenario and while FIXING one the run gate refused, and the
  refusal already sends the analyst to this side of the screen. Its own rail
  item meant a closed list sat one click away from the editor it serves, and
  the rail grew a seventh item for a reference table.

  So this renders the body only: no wrapper, no PanelHead. The parent owns
  the header, because the header is now shared with Zaman Çizelgesi.
*/
export function VocabPanel({
  steps,
  loaded,
  failed,
  onInsert,
}: {
  steps: VocabularyStep[];
  loaded: boolean;
  failed: boolean;
  onInsert: (pattern: string) => void;
}) {
  const [search, setSearch] = useState("");
  const filtered = filterVocabulary(steps, search);
  const grouped = groupVocabulary(filtered);

  return (
    <>
      <p className="vocab-intro">
        Kapalı sözlük: senaryolarda yalnızca bu {steps.length} adım kullanılabilir.
        Bir adıma tıklayınca editöre eklenir.
      </p>

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
    </>
  );
}
