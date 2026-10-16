/*
  Pure text-editing helpers, lifted from app.js's goToLine/replaceLine/
  insertAtCursor (app.js:138-179). Kept as pure functions -- no DOM, no
  React -- because EditorPane needs to call them synchronously against a
  `<textarea>` ref both to update the value AND to move the caret in the
  same tick, and a pure (text, pos) -> {text, selection} contract is the
  only shape that lets it do both without fighting React's own render.
*/

export interface Selection {
  start: number;
  end: number;
}

const GHERKIN_KEYWORD = /^(Given|When|Then|And|But)\s+/;

/** Character offset of the start of `line` (1-based) within `text`. */
export function lineStartOffset(text: string, line: number): number {
  const lines = text.split("\n");
  let offset = 0;
  for (let i = 0; i < line - 1 && i < lines.length; i++) {
    offset += (lines[i]?.length ?? 0) + 1;
  }
  return offset;
}

/** The selection a "Satır N" click should produce: the whole line selected,
 *  so a glance at the editor shows what the error is about. */
export function lineSelection(text: string, line: number): Selection {
  const lines = text.split("\n");
  const start = lineStartOffset(text, line);
  const body = lines[line - 1] ?? "";
  return { start, end: start + body.length };
}

/**
 * The rough scroll position goToLine used: line height is fixed by the
 * monospace stack in app.css (font: 13px/1.62 ≈ 21px), and centring six
 * lines above the target reads better than pinning it to the top edge.
 * Kept as a named constant rather than a bare literal, per the plan's note
 * that this coupling must survive the port, not just be copied blind.
 */
export const EDITOR_LINE_HEIGHT_PX = 21;
export const GOTO_LINE_LEAD_LINES = 6;

export function goToLineScrollTop(line: number): number {
  return Math.max(0, (line - GOTO_LINE_LEAD_LINES) * EDITOR_LINE_HEIGHT_PX);
}

/**
 * Rewrites one line to `text`, preserving its original indentation and
 * Gherkin keyword (Given/When/Then/And/But), defaulting to "And" when the
 * line had none (app.js:149-158). Returns null when `line` is out of range,
 * same as the original silently no-opping.
 */
export function replaceLineText(source: string, line: number, text: string): string | null {
  const lines = source.split("\n");
  if (line < 1 || line > lines.length) return null;

  const original = lines[line - 1] ?? "";
  const indentMatch = original.match(/^\s*/);
  const indent = indentMatch ? indentMatch[0] : "";
  const keywordMatch = original.trim().match(GHERKIN_KEYWORD);
  const keyword = keywordMatch ? keywordMatch[0].trim() : "And";

  lines[line - 1] = indent + keyword + " " + text;
  return lines.join("\n");
}

/**
 * Inserts `    And <pattern>` at the caret (app.js:160-178): appended to the
 * current line if it is blank, otherwise on a new line below. Returns the
 * new full text and the caret position to restore afterwards.
 */
export function insertPatternAtCursor(
  source: string,
  cursorPos: number,
  pattern: string,
): { text: string; caret: number } {
  const lineStart = source.lastIndexOf("\n", cursorPos - 1) + 1;
  let lineEnd = source.indexOf("\n", cursorPos);
  if (lineEnd === -1) lineEnd = source.length;

  const current = source.slice(lineStart, lineEnd);
  const insertion = current.trim() === "" ? "    And " + pattern : current + "\n    And " + pattern;

  const text = source.slice(0, lineStart) + insertion + source.slice(lineEnd);
  const caret = lineStart + insertion.length;
  return { text, caret };
}

/* ------------------------------ highlighting ------------------------------

   Gherkin split into coloured pieces, one line at a time.

   Returns DATA, not markup. The highlight layer renders each piece as a
   <span>, so React escapes the text and the whole class of bug that comes
   with building HTML strings out of a scenario -- a label containing "<" or
   "&" turning into markup -- cannot happen here at all.

   Line-based because Gherkin is: a line is a comment, a tag, a declaration, a
   step or a table row, and nothing spans two lines. There is no block state to
   carry, so a line can be coloured without looking at any other.
*/

export interface GherkinToken {
  text: string;
  /** CSS class, or null for text that takes the editor's ordinary colour. */
  cls: string | null;
}

/** Feature:, Scenario:, Examples: and friends. */
const DECLARATION = /^(Feature|Background|Rule|Scenario Outline|Scenario|Examples)(\s*:)/;

/** The step keywords, and the `*` form Cucumber also accepts. */
const STEP_KEYWORD = /^(Given|When|Then|And|But|\*)(?=\s)/;

/** A quoted argument or an Outline placeholder. */
const INLINE = /("[^"]*"|<[^>]*>)/g;

export function tokenizeGherkinLine(line: string): GherkinToken[] {
  const body = line.trimStart();
  const indent = line.slice(0, line.length - body.length);

  if (body.startsWith("#")) return [{ text: line, cls: "gh-comment" }];
  if (body.startsWith("@")) return [{ text: line, cls: "gh-tag" }];
  if (body.startsWith("|")) return [{ text: line, cls: "gh-table" }];

  const declaration = DECLARATION.exec(body);
  if (declaration) {
    return [
      { text: indent, cls: null },
      { text: declaration[0], cls: "gh-decl" },
      ...inline(body.slice(declaration[0].length)),
    ];
  }

  const keyword = STEP_KEYWORD.exec(body);
  if (keyword) {
    return [
      { text: indent, cls: null },
      { text: keyword[0], cls: "gh-kw" },
      ...inline(body.slice(keyword[0].length)),
    ];
  }

  return inline(line);
}

function inline(text: string): GherkinToken[] {
  const out: GherkinToken[] = [];
  let last = 0;

  for (const match of text.matchAll(INLINE)) {
    const at = match.index ?? 0;
    if (at > last) out.push({ text: text.slice(last, at), cls: null });
    out.push({ text: match[0], cls: match[0].startsWith('"') ? "gh-str" : "gh-ph" });
    last = at + match[0].length;
  }
  if (last < text.length) out.push({ text: text.slice(last), cls: null });

  return out.filter((token) => token.text.length > 0);
}
