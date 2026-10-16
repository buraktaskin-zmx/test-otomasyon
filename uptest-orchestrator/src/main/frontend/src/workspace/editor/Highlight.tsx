import { Fragment } from "react";
import { tokenizeGherkinLine } from "../../lib/gherkin";
import type { RefObject } from "react";

/*
  The coloured copy of the scenario, sitting exactly behind the textarea.

  A <textarea> cannot colour its own contents, so the standard answer is two
  layers: this one paints, the textarea takes the typing with its text made
  transparent and only its caret left visible. That only works while the two
  agree about EVERY metric -- font, size, line-height, padding, tab-size,
  white-space -- so those live in one CSS rule that names both, rather than
  being written twice and drifting by a pixel.

  aria-hidden, and never focusable: a screen reader must hear the textarea,
  which holds the real text. This is decoration over the top of it.

  Scrolling is driven from the textarea (see useEditorOperations.syncScroll).
  Both axes, not just the vertical one the gutter needs: a long step line
  scrolls sideways, and a highlight layer that only tracked scrollTop would
  slide out of register the moment it did.
*/
export function Highlight({ text, ref }: { text: string; ref: RefObject<HTMLPreElement | null> }) {
  // A trailing newline has no line after it to render, so the last line of a
  // text ending in "\n" would have nothing to sit against and the layer would
  // come up one line short of the textarea's scroll height.
  const lines = text.split("\n");

  return (
    <pre ref={ref} className="editor-highlight" aria-hidden="true">
      {lines.map((line, index) => (
        <Fragment key={index}>
          {tokenizeGherkinLine(line).map((token, piece) =>
            token.cls === null ? (
              <Fragment key={piece}>{token.text}</Fragment>
            ) : (
              <span key={piece} className={token.cls}>
                {token.text}
              </span>
            ),
          )}
          {index < lines.length - 1 ? "\n" : null}
        </Fragment>
      ))}
    </pre>
  );
}
