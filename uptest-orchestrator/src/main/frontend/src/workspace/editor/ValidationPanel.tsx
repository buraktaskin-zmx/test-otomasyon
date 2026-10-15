import { SuggestionChip } from "./SuggestionChip";
import type { ValidationState } from "../../hooks/useValidation";
import type { ValidationError } from "../../api/types";

/*
  app.js:272-314 (renderValidation), split into its "ok" row vs its list of
  error cards. "idle"/"typing"/"empty" render nothing, matching
  `el.validation.innerHTML = ""` for those same states.
*/
export function ValidationPanel({
  state,
  errors,
  onGoToLine,
  onApplyFix,
}: {
  state: ValidationState;
  errors: ValidationError[];
  onGoToLine: (line: number) => void;
  onApplyFix: (line: number, fix: string) => void;
}) {
  if (state.kind === "valid") {
    return (
      <div className="validation">
        <div className="v-ok">✓ Senaryo geçerli — {state.stepCount} adımın tamamı tanımlı.</div>
      </div>
    );
  }

  if (state.kind === "no-executor") {
    return (
      <div className="validation">
        <div className="v-error">
          <div className="v-error-head">
            <span className="v-msg">
              Hiçbir executor'a ulaşılamadığı için doğrulama yapılamıyor.
            </span>
          </div>
        </div>
      </div>
    );
  }

  if (state.kind !== "invalid" || errors.length === 0) {
    return <div className="validation" />;
  }

  return (
    <div className="validation">
      {errors.map((error, index) => (
        <ErrorCard key={index} error={error} onGoToLine={onGoToLine} onApplyFix={onApplyFix} />
      ))}
    </div>
  );
}

function ErrorCard({
  error,
  onGoToLine,
  onApplyFix,
}: {
  error: ValidationError;
  onGoToLine: (line: number) => void;
  onApplyFix: (line: number, fix: string) => void;
}) {
  return (
    <div className="v-error">
      <div className="v-error-head">
        {error.line ? (
          <button className="v-line" type="button" onClick={() => onGoToLine(error.line)}>
            Satır {error.line}
          </button>
        ) : null}
        <span className="v-msg">{error.message}</span>
        {error.step ? <span className="v-step">{error.step}</span> : null}
      </div>
      {error.suggestions.length > 0 ? (
        <div className="v-suggestions">
          <span className="v-sug-label">Bunu mu demek istediniz:</span>
          {error.suggestions.map((fix, i) => (
            <SuggestionChip key={i} line={error.line || 0} fix={fix} onApply={onApplyFix} />
          ))}
        </div>
      ) : null}
    </div>
  );
}
