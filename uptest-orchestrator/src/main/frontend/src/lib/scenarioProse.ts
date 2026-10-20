/*
  The natural-language half of a scenario file: the `Scenario:` title and the
  free-text description beneath it, up to the first step. Read out for the
  "Doğal dil" editor and written back without touching anything else in the
  file -- tags, Feature line and any Gherkin steps stay as they are.
*/

const SCENARIO = /^(\s*)Scenario( Outline)?:\s*(.*)$/;
// A description ends where Gherkin structure starts again.
const STRUCTURE = /^\s*(Given|When|Then|And|But|\*|Examples:|Scenario|Background:|@)/;

export interface Prose {
  title: string;
  body: string;
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
  const body = lines
    .slice(at.head + 1, at.end)
    .map((line) => line.trim())
    .join("\n")
    .trim();
  return { title: at.title, body };
}

export function writeProse(feature: string, prose: Prose): string {
  const newline = feature.includes("\r\n") ? "\r\n" : "\n";
  const lines = feature.split(/\r?\n/);
  const at = locate(lines);
  if (!at) return feature;

  const head = at.indent + "Scenario" + at.outline + ": " + prose.title.trim();
  const bodyIndent = at.indent + "  ";
  const body = prose.body
    .trim()
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
