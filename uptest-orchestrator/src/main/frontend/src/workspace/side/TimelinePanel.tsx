import { PanelHead } from "./PanelHead";
import { RunFooter } from "./RunFooter";
import { RunHeader } from "./RunHeader";
import { StepList } from "./StepList";
import type { Artifact, RunDetail } from "../../api/types";

/*
  The run, start to finish, in one panel.

  Was "Zaman Çizelgesi" and showed only what a run was doing; starting one
  lived somewhere else entirely, in the editor's header. The two halves of the
  same act were in different places, so watching a run meant looking here and
  starting one meant looking there.

  Çalıştır is now in both places on purpose, and both call the same handler.
  The editor's copy is where you reach for it while reading a scenario; this
  one is where you reach for it when you are already looking at the last run
  and want another.
*/
export function TimelinePanel({
  active,
  run,
  artifacts,
  onStop,
  onRun,
  runDisabled,
}: {
  active: boolean;
  run: RunDetail | null;
  artifacts: Artifact[] | null;
  onStop: () => void;
  onRun: () => void;
  runDisabled: boolean;
}) {
  return (
    <div id="side-timeline" className={"side-panel" + (active ? " is-active" : "")}>
      <PanelHead title="Koşu">
        {/* Its own id: #btnRun belongs to the editor header and a duplicate
            would break both for anything selecting by id. */}
        <button
          id="btnRunPanel"
          className="btn btn-primary"
          type="button"
          onClick={onRun}
          disabled={runDisabled}
        >
          Çalıştır
        </button>
      </PanelHead>
      <RunHeader run={run} onStop={onStop} />
      <StepList steps={run?.steps ?? []} currentStepIndex={run?.currentStepIndex ?? null} />
      {run ? <RunFooter runId={run.runId} steps={run.steps ?? []} artifacts={artifacts} /> : null}
    </div>
  );
}
