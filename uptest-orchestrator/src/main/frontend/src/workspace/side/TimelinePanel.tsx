import { RunFooter } from "./RunFooter";
import { RunHeader } from "./RunHeader";
import { StepList } from "./StepList";
import type { Artifact, RunDetail } from "../../api/types";

/* app.js:178-184 (index.html) composed with renderTimeline. */
export function TimelinePanel({
  active,
  run,
  artifacts,
  onStop,
}: {
  active: boolean;
  run: RunDetail | null;
  artifacts: Artifact[] | null;
  onStop: () => void;
}) {
  return (
    <div id="side-timeline" className={"side-panel" + (active ? " is-active" : "")}>
      <RunHeader run={run} onStop={onStop} />
      <StepList steps={run?.steps ?? []} currentStepIndex={run?.currentStepIndex ?? null} />
      {run ? <RunFooter runId={run.runId} steps={run.steps ?? []} artifacts={artifacts} /> : null}
    </div>
  );
}
