import { useMemo, useState } from "react";
import { EditorPaneHead } from "./EditorPaneHead";
import { GherkinEditor } from "./GherkinEditor";
import { Gutter } from "./Gutter";
import { Highlight } from "./Highlight";
import { ValidationBadge } from "./ValidationBadge";
import { ValidationPanel } from "./ValidationPanel";
import type { ReactNode, RefObject } from "react";
import type { ValidationState } from "../../hooks/useValidation";
import type { ValidationError } from "../../api/types";

/*
  The single-scenario editor view: gutter + textarea + validation panel,
  with the pane-head shared with Çoklu Üret's list mode via EditorPaneHead.

  `multiBar` is rendered between the head and the editor-wrap only while a
  Çoklu Üret draft is open (index.html:45-49) -- null the rest of the time,
  including the whole single-scenario workflow, which must look exactly as
  it did before that feature existed.
*/
export function EditorPane({
  feature,
  onChange,
  textareaRef,
  gutterRef,
  highlightRef,
  onScroll,
  validationState,
  errors,
  onValidateClick,
  onRunClick,
  runDisabled,
  onGoToLine,
  onApplyFix,
  multiBar,
  runFocused,
}: {
  feature: string;
  onChange: (value: string) => void;
  textareaRef: RefObject<HTMLTextAreaElement | null>;
  gutterRef: RefObject<HTMLDivElement | null>;
  highlightRef: RefObject<HTMLPreElement | null>;
  onScroll: () => void;
  validationState: ValidationState;
  errors: ValidationError[];
  onValidateClick: () => void;
  onRunClick: () => void;
  runDisabled: boolean;
  onGoToLine: (line: number) => void;
  onApplyFix: (line: number, fix: string) => void;
  multiBar?: ReactNode;
  /** The Çalıştır panel is open: an empty editor asks for a scenario to test. */
  runFocused: boolean;
}) {
  /* On by default, and switchable: colouring is a reading aid, and a reading
     aid that cannot be turned off is a liability the first time it is wrong
     about something. Local to the editor -- nothing else in the app has an
     opinion about it. */
  const [coloured, setColoured] = useState(true);

  const badLines = useMemo(
    () => new Set(errors.filter((e) => e.line > 0).map((e) => e.line)),
    [errors],
  );

  return (
    <div className="pane pane-editor">
      <EditorPaneHead
        badge={<ValidationBadge state={validationState} />}
        onValidateClick={onValidateClick}
        onRunClick={onRunClick}
        validateDisabled={false}
        runDisabled={runDisabled}
        extra={
          <button
            id="btnColour"
            className="btn btn-ghost"
            type="button"
            aria-pressed={coloured}
            title={coloured ? "Renklendirmeyi kapat" : "Renklendirmeyi aç"}
            onClick={() => setColoured((on) => !on)}
          >
            Renk
          </button>
        }
      />
      {multiBar ?? null}
      <div className="editor-wrap">
        <Gutter ref={gutterRef} text={feature} badLines={badLines} />
        {/* The stack is what holds the two layers in register. Without
            colouring there is nothing to hold, so the textarea goes back to
            being an ordinary child of .editor-wrap and paints its own text. */}
        <div className={"editor-stack" + (coloured ? " is-coloured" : "")}>
          {coloured ? <Highlight text={feature} ref={highlightRef} /> : null}
          <GherkinEditor ref={textareaRef} value={feature} onChange={onChange} onScroll={onScroll} />
          {feature.trim() ? null : (
            <div className="editor-empty" aria-hidden="true">
              {runFocused ? (
                <>
                  <p className="run-empty-title">Test edilecek senaryoyu buraya ekleyin</p>
                  <p className="run-empty-text">
                    Senaryoyu yazın ya da yapıştırın, sonra <b>Çalıştır</b>'a basın.
                  </p>
                </>
              ) : (
                <>
                  <p className="run-empty-title">Senaryolar burada görünecek</p>
                  <p className="run-empty-text">
                    Soldan <b>AI ile Senaryo Üret</b> ile üretin ya da bir senaryoyu buraya
                    yazın veya yapıştırın.
                  </p>
                </>
              )}
            </div>
          )}
        </div>
      </div>
      <ValidationPanel
        state={validationState}
        errors={errors}
        onGoToLine={onGoToLine}
        onApplyFix={onApplyFix}
      />
    </div>
  );
}
