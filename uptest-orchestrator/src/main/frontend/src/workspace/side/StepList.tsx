import { EmptyState } from "../../ui/EmptyState";
import { formatDuration } from "../../lib/format";
import type { RunStep, StepStatus } from "../../api/types";

/* app.js:423 -- six icons, one per StepState.Status. */
const STEP_ICON: Record<StepStatus, string> = {
  PASSED: "✓",
  FAILED: "✕",
  RUNNING: "●",
  SKIPPED: "–",
  UNDEFINED: "?",
  PENDING: "",
};

function StepRow({ step, current }: { step: RunStep; current: boolean }) {
  return (
    <li className={"tl-step st-" + step.status + (current ? " is-current" : "")}>
      <span className="tl-icon">{STEP_ICON[step.status]}</span>
      <div className="tl-body">
        <div className="tl-text">
          <span className="tl-kw">{step.keyword}</span> {step.text}
        </div>
        {step.error ? <div className="tl-error">{step.error}</div> : null}
      </div>
      <span className="tl-time">{formatDuration(step.durationMs)}</span>
    </li>
  );
}

/* app.js:392-434 (renderTimeline's step-list half). `current` is -1 (not
   null) once a payload exists but nothing is running -- both mean "no
   step highlighted", so comparing by index handles both uniformly. */
export function StepList({ steps, currentStepIndex }: { steps: RunStep[]; currentStepIndex: number | null }) {
  if (steps.length === 0) {
    return (
      <ol id="timeline" className="timeline">
        <li>
          <EmptyState title="Adımlar bekleniyor…">Cucumber senaryoyu hazırlıyor.</EmptyState>
        </li>
      </ol>
    );
  }

  return (
    <ol id="timeline" className="timeline">
      {steps.map((step, index) => (
        <StepRow key={index} step={step} current={index === currentStepIndex} />
      ))}
    </ol>
  );
}
