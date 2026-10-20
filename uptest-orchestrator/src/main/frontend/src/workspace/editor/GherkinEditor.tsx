import { forwardRef } from "react";

/** Thin wrapper around the textarea itself (index.html:52-53) -- the actual
 *  editing machinery (selection, scroll sync, insert/replace) lives in
 *  hooks/useEditorOperations.ts, which owns this ref. */
export const GherkinEditor = forwardRef<
  HTMLTextAreaElement,
  { value: string; onChange: (value: string) => void; onScroll: () => void; readOnly?: boolean }
>(function GherkinEditor({ value, onChange, onScroll, readOnly }, ref) {
  return (
    <textarea
      ref={ref}
      id="editor"
      className="editor"
      readOnly={readOnly}
      spellCheck={false}
      autoComplete="off"
      aria-label="Gherkin senaryo düzenleyici"
      value={value}
      onChange={(event) => onChange(event.target.value)}
      onScroll={onScroll}
    />
  );
});
