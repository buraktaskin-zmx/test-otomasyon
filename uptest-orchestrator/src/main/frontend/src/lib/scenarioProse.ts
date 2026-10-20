/*
  The natural-language half of a scenario file: the `Scenario:` title and the
  free-text description beneath it, up to the first step. Read out for the
  "Doğal dil" editor and written back without touching anything else in the
  file -- tags, Feature line and any Gherkin steps stay as they are.
*/

const SCENARIO = /^(\s*)Scenario( Outline)?:\s*(.*)$/;
// A description ends where Gherkin structure starts again.
const STRUCTURE = /^\s*(Given|When|Then|And|But|\*|Examples:|Scenario|Background:|@)/;

/**
 * The tag lines above `Feature:` (`@R-AL-014 @needs:none`), split from the
 * rest. They carry the rule and precondition columns, so the view hides them
 * but a save must put them back exactly as they were.
 */
export function splitTags(feature: string): { tags: string; body: string } {
  const lines = feature.split(/\r?\n/);
  let first = 0;
  while (first < lines.length && /^\s*(@|$)/.test(lines[first] ?? "")) first++;
  const tagLines = lines.slice(0, first).filter((line) => line.trim().startsWith("@"));
  return { tags: tagLines.join("\n"), body: lines.slice(first).join("\n") };
}

export function joinTags(tags: string, body: string): string {
  return tags ? tags + "\n" + body.replace(/^\s*\n/, "") : body;
}

export interface Prose {
  title: string;
  /** The "Ön koşul: …" paragraph's text, without its label; "" when none. */
  precondition: string;
  body: string;
}

// The label is matched loosely: the files are written in ASCII ("On kosul").
const PRECONDITION = /^(ön|on)\s*ko[sş]ul\s*:\s*/i;
const DEFAULT_LABEL = "Ön koşul:";

/** Splits off a leading "Ön koşul:" paragraph, keeping the label the file used. */
function splitPrecondition(text: string): { label: string; precondition: string; body: string } {
  const paragraphs = text.split(/\n\s*\n/);
  const first = paragraphs[0] ?? "";
  const match = first.match(PRECONDITION);
  if (!match) return { label: DEFAULT_LABEL, precondition: "", body: text };
  return {
    label: match[0].trim(),
    precondition: first.slice(match[0].length).trim(),
    body: paragraphs.slice(1).join("\n\n").trim(),
  };
}

function locate(lines: string[]) {
  const head = lines.findIndex((line) => SCENARIO.test(line));
  if (head < 0) return null;
  const match = (lines[head] ?? "").match(SCENARIO);
  if (!match) return null;
  let end = head + 1;
  while (end < lines.length && !STRUCTURE.test(lines[end] ?? "")) end++;
  return { head, end, indent: match[1] ?? "", outline: match[2] ?? "", title: (match[3] ?? "").trim() };
}

export function readProse(feature: string): Prose | null {
  const lines = feature.split(/\r?\n/);
  const at = locate(lines);
  if (!at) return null;
  const text = lines
    .slice(at.head + 1, at.end)
    .map((line) => line.trim())
    .join("\n")
    .trim();
  const { precondition, body } = splitPrecondition(text);
  return { title: at.title, precondition, body };
}

export function writeProse(feature: string, prose: Prose): string {
  const newline = feature.includes("\r\n") ? "\r\n" : "\n";
  const lines = feature.split(/\r?\n/);
  const at = locate(lines);
  if (!at) return feature;

  const head = at.indent + "Scenario" + at.outline + ": " + prose.title.trim();
  const bodyIndent = at.indent + "  ";

  // Keep whichever spelling of the label the file already had.
  const oldText = lines.slice(at.head + 1, at.end).map((line) => line.trim()).join("\n").trim();
  const { label } = splitPrecondition(oldText);
  const text = [
    prose.precondition.trim() ? label + " " + prose.precondition.trim() : "",
    prose.body.trim(),
  ].filter(Boolean).join("\n\n");

  const body = text
    .split(/\r?\n/)
    .map((line) => (line.trim() ? bodyIndent + line.trim() : ""));

  return [
    ...lines.slice(0, at.head),
    head,
    "",
    ...body,
    "",
    ...lines.slice(at.end),
  ].join(newline);
}
