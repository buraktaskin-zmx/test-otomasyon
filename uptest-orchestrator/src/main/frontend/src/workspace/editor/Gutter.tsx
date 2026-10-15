import { forwardRef, useMemo } from "react";

/*
  Line-number column, synced to the editor's scroll position by the parent
  (EditorPane forwards this ref and sets gutterRef.current.scrollTop on the
  textarea's onScroll -- app.js:1126 does the same one-directional sync).
  Bad lines (from the last validation's errors) get the .ln-bad class
  (app.js:125-136).
*/
export const Gutter = forwardRef<HTMLDivElement, { text: string; badLines: ReadonlySet<number> }>(
  function Gutter({ text, badLines }, ref) {
    const lineCount = useMemo(() => text.split("\n").length, [text]);

    return (
      <div ref={ref} className="gutter" aria-hidden="true">
        {Array.from({ length: lineCount }, (_, i) => i + 1).map((line) =>
          badLines.has(line) ? (
            <span key={line} className="ln-bad">
              {line}
              {"\n"}
            </span>
          ) : (
            line + "\n"
          ),
        )}
      </div>
    );
  },
);
