import { useCallback, useEffect, useRef, useState } from "react";
import {
  goToLineScrollTop,
  insertPatternAtCursor,
  lineSelection,
  replaceLineText,
} from "../lib/gherkin";

/*
  Owns the editor's <textarea>/gutter refs and every operation that needs to
  reach into the real DOM: goToLine (app.js:138-147), replaceLine
  (app.js:149-158), insertAtCursor (app.js:160-178), and the gutter's scroll
  sync (app.js:1126).

  Lives in WorkspaceView, not EditorPane: insertAtCursor is called from the
  vocabulary panel, a SIBLING of the editor in the side pane, so whichever
  component owns the ref has to be a common ancestor of both. EditorPane
  stays presentational, rendering whatever refs and callbacks are handed to
  it (see the plan's state-architecture note on this split).

  Why a `pending` selection + effect instead of calling setSelectionRange
  synchronously: this is a CONTROLLED textarea, so after setFeature() the
  DOM has not yet caught up to the new `value` when the calling function
  returns. The effect below runs after React has committed the new value,
  guaranteeing setSelectionRange's offsets land inside a textarea that
  actually contains the text they were computed against.
*/

interface PendingSelection {
  start: number;
  end: number;
  scrollTop: number;
}

export function useEditorOperations(
  feature: string,
  setFeature: (next: string) => void,
  /** Called after every programmatic edit (replace/insert) with the NEW
   *  text, same call sites app.js's scheduleValidate() occupies -- and,
   *  with the text now in hand, also where Çoklu Üret's per-keystroke
   *  saveActiveDraft() attaches (app.js:1121-1125: fires on every edit, not
   *  only on navigating back to the list). */
  onEdited: (next: string) => void,
) {
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const gutterRef = useRef<HTMLDivElement>(null);
  const highlightRef = useRef<HTMLPreElement>(null);
  const [pending, setPending] = useState<PendingSelection | null>(null);

  useEffect(() => {
    if (!pending) return;
    const editor = textareaRef.current;
    if (!editor) return;
    editor.focus();
    editor.setSelectionRange(pending.start, pending.end);
    editor.scrollTop = pending.scrollTop;
    if (gutterRef.current) gutterRef.current.scrollTop = editor.scrollTop;
    setPending(null);
    // Re-runs after `feature` commits, which is exactly when the pending
    // selection's offsets are valid against the live DOM value.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [feature, pending]);

  /** One-directional sync, same as app.js: the editor is the source of
   *  truth for scroll position, the other layers always mirror it.
   *
   *  The highlight layer needs BOTH axes. The gutter only ever needed
   *  scrollTop, because line numbers do not move sideways -- but the
   *  highlight is a copy of the text, and a long step line scrolls
   *  horizontally. Mirroring only the vertical axis would hold the colours
   *  in register until the first time anyone scrolled right, which is
   *  exactly the kind of bug that gets found by a user and not by us. */
  const syncScroll = useCallback(() => {
    const editor = textareaRef.current;
    if (!editor) return;
    if (gutterRef.current) {
      gutterRef.current.scrollTop = editor.scrollTop;
    }
    if (highlightRef.current) {
      highlightRef.current.scrollTop = editor.scrollTop;
      highlightRef.current.scrollLeft = editor.scrollLeft;
    }
  }, []);

  const goToLine = useCallback(
    (line: number) => {
      const selection = lineSelection(feature, line);
      setPending({ ...selection, scrollTop: goToLineScrollTop(line) });
    },
    [feature],
  );

  const replaceLine = useCallback(
    (line: number, text: string) => {
      const next = replaceLineText(feature, line, text);
      if (next === null) return;
      setFeature(next);
      onEdited(next);
      const selection = lineSelection(next, line);
      setPending({ ...selection, scrollTop: goToLineScrollTop(line) });
    },
    [feature, setFeature, onEdited],
  );

  const insertAtCursor = useCallback(
    (pattern: string) => {
      const cursor = textareaRef.current?.selectionStart ?? feature.length;
      const { text, caret } = insertPatternAtCursor(feature, cursor, pattern);
      setFeature(text);
      onEdited(text);
      setPending({ start: caret, end: caret, scrollTop: textareaRef.current?.scrollTop ?? 0 });
    },
    [feature, setFeature, onEdited],
  );

  return { textareaRef, gutterRef, highlightRef, syncScroll, goToLine, replaceLine, insertAtCursor };
}
