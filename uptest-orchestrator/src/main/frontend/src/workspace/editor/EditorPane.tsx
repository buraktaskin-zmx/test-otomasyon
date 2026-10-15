import { useMemo } from "react";
import { EditorPaneHead } from "./EditorPaneHead";
import { GherkinEditor } from "./GherkinEditor";
import { Gutter } from "./Gutter";
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
  onScroll,
  validationState,
  errors,
  onValidateClick,
  onRunClick,
  runDisabled,
  onGoToLine,
  onApplyFix,
  multiBar,
}: {
  feature: string;
  onChange: (value: string) => void;
  textareaRef: RefObject<HTMLTextAreaElement | null>;
  gutterRef: RefObject<HTMLDivElement | null>;
  onScroll: () => void;
  validationState: ValidationState;
  errors: ValidationError[];
  onValidateClick: () => void;
  onRunClick: () => void;
  runDisabled: boolean;
  onGoToLine: (line: number) => void;
  onApplyFix: (line: number, fix: string) => void;
  multiBar?: ReactNode;
}) {
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
      />
      {multiBar ?? null}
      <div className="editor-wrap">
        <Gutter ref={gutterRef} text={feature} badLines={badLines} />
        <GherkinEditor ref={textareaRef} value={feature} onChange={onChange} onScroll={onScroll} />
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
