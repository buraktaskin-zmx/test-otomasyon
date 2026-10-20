import { PanelHead } from "./PanelHead";
import { RunFooter } from "./RunFooter";
import { RunHeader } from "./RunHeader";
import { StepList } from "./StepList";
import { VocabPanel } from "./VocabPanel";
import type { Artifact, RunDetail, VocabularyStep } from "../../api/types";

/** Which half of the run panel is showing. */
export type RunTab = "vocab" | "timeline";

/*
  Yazarken bakılan sözlük ve koşarken bakılan çizelge, tek panelde iki sekme.

  They share a place because they share a moment. The run gate refuses a
  scenario whose step is not in the vocabulary, and the first thing anyone
  does about that is read the vocabulary — so the refusal now lands in the
  same panel as the answer instead of sending the analyst to a seventh rail
  item. That forced switch (WorkspaceView's 422 branch) is also why the
  active tab is owned by the parent rather than kept here: a panel cannot
  switch itself from a decision taken somewhere else.

  No Çalıştır here: the editor header's button is the one place a run starts.
*/
export function TimelinePanel({
  active,
  tab,
  onTabChange,
  run,
  artifacts,
  onStop,
  steps,
  vocabularyLoaded,
  vocabularyFailed,
  onInsert,
}: {
  active: boolean;
  tab: RunTab;
  onTabChange: (next: RunTab) => void;
  run: RunDetail | null;
  artifacts: Artifact[] | null;
  onStop: () => void;
  steps: VocabularyStep[];
  vocabularyLoaded: boolean;
  vocabularyFailed: boolean;
  onInsert: (pattern: string) => void;
}) {
  const onVocab = tab === "vocab";

  return (
    <div id="side-timeline" className={"side-panel timeline-panel" + (active ? " is-active" : "")}>
      <PanelHead title="ÇALIŞTIR">
        <div className="run-tabs" role="tablist">
          <button
            type="button"
            role="tab"
            aria-selected={onVocab}
            className={"btn " + (onVocab ? "btn-primary" : "btn-ghost")}
            onClick={() => onTabChange("vocab")}
          >
            Adım Sözlüğü
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={!onVocab}
            className={"btn " + (onVocab ? "btn-ghost" : "btn-primary")}
            onClick={() => onTabChange("timeline")}
          >
            Zaman Çizelgesi
          </button>
        </div>
      </PanelHead>

      {onVocab ? (
        <VocabPanel
          steps={steps}
          loaded={vocabularyLoaded}
          failed={vocabularyFailed}
          onInsert={onInsert}
        />
      ) : (
        <>
          <p className="vocab-intro">
            Koşu sırasında adımlar canlı akar. Başarısız adım kendi hata günlüğünü
            açar; çıktılar altta.
          </p>
          {run ? (
            <>
              <RunHeader run={run} onStop={onStop} />
              <StepList steps={run.steps ?? []} currentStepIndex={run.currentStepIndex ?? null} />
              <RunFooter runId={run.runId} steps={run.steps ?? []} artifacts={artifacts} />
            </>
          ) : (
            <div className="run-empty">
              <span className="run-empty-ring" aria-hidden="true" />
              <p className="run-empty-title">Henüz çalıştırılmadı</p>
              <p className="run-empty-text">
                Çalıştır'a bastığınızda adımlar burada canlı akar.
              </p>
            </div>
          )}
        </>
      )}
    </div>
  );
}
