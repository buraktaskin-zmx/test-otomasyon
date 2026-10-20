/*
  The natural-language scenario, as five fields:

    başlık     the `Scenario:` title
    kural      the @R-... tags above `Feature:`
    ön koşul   ┐
    adımlar    ├ lists in the scenario's description, one "- " line per item
    beklenen   ┘

  Kept INSIDE the .feature file, not in a second file: the Gherkin replay,
  the registry's rule/precondition columns and "AI ile çalıştır" all read
  this file, and two files describing one scenario would drift apart.

  Older files have the description as free prose. Those are still read --
  an "On kosul:" paragraph becomes the precondition, a "Beklenen:" paragraph
  the expectation, the rest is split into steps by sentence -- and a save
  writes them back in the structured layout.
*/

const SCENARIO = /^(\s*)Scenario( Outline)?:\s*(.*)$/;
// A description ends where Gherkin structure starts again.
const STRUCTURE = /^\s*(Given|When|Then|And|But|\*|Examples:|Scenario|Background:|@)/;

const HEADERS: Array<[Section, RegExp]> = [
  ["preconditions", /^(ön|on)\s*ko[sş]ul(lar)?\s*:\s*(.*)$/i],
  ["steps", /^ad[ıi]mlar\s*:\s*(.*)$/i],
  ["expected", /^beklenen(ler)?\s*:\s*(.*)$/i],
];

const LABEL: Record<Section, string> = {
  preconditions: "Ön koşul:",
  steps: "Adımlar:",
  expected: "Beklenen:",
};

type Section = "preconditions" | "steps" | "expected";

export interface ScenarioDoc {
  title: string;
  rules: string[];
  preconditions: string[];
  steps: string[];
  expected: string[];
}

/**
 * The tag lines above `Feature:` (`@R-AL-014 @needs:none`), split from the
 * rest. They carry the rule and precondition columns, so the Gherkin view
 * hides them but a save must put them back exactly as they were.
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

function locate(lines: string[]) {
  const head = lines.findIndex((line) => SCENARIO.test(line));
  if (head < 0) return null;
  const match = (lines[head] ?? "").match(SCENARIO);
  if (!match) return null;
  let end = head + 1;
  while (end < lines.length && !STRUCTURE.test(lines[end] ?? "")) end++;
  return { head, end, indent: match[1] ?? "", outline: match[2] ?? "", title: (match[3] ?? "").trim() };
}

function headerOf(line: string): { section: Section; rest: string } | null {
  for (const [section, pattern] of HEADERS) {
    const match = line.match(pattern);
    if (match) return { section, rest: (match[match.length - 1] ?? "").trim() };
  }
  return null;
}

function sentences(text: string): string[] {
  return text
    .split(/(?<=\.)\s+/)
    .map((part) => part.trim().replace(/\.$/, "").trim())
    .filter(Boolean);
}

export function readDoc(feature: string): ScenarioDoc | null {
  const lines = feature.split(/\r?\n/);
  const at = locate(lines);
  if (!at) return null;

  const doc: ScenarioDoc = {
    title: at.title,
    rules: splitTags(feature).tags.split(/\s+/)
      .filter((tag) => /^@R-/i.test(tag))
      .map((tag) => tag.slice(1)),
    preconditions: [],
    steps: [],
    expected: [],
  };

  let section: Section | null = null;
  let loose: string[] = [];
  // Whether the current section's last item may still grow by a wrapped line.
  let open = false;

  for (const raw of lines.slice(at.head + 1, at.end)) {
    const line = raw.trim();
    if (!line) {
      open = false;
      continue;
    }
    const header = headerOf(line);
    if (header) {
      section = header.section;
      if (header.rest) {
        doc[section].push(header.rest);
        open = true;
      } else {
        open = false;
      }
      continue;
    }
    if (line.startsWith("- ") || line === "-") {
      if (section) {
        doc[section].push(line.slice(1).trim());
        open = true;
      } else {
        loose.push(line.slice(1).trim());
      }
      continue;
    }
    if (section && open) {
      const items = doc[section];
      const last = items[items.length - 1] ?? "";
      // "…su degerler gorunur:" announces a list written as plain lines.
      if (last.endsWith(":")) items.push(line);
      else items[items.length - 1] = (last + " " + line).trim();
      continue;
    }
    // Prose outside any section: legacy free text, read as steps.
    section = null;
    loose.push(line);
  }

  if (loose.length > 0) {
    doc.steps = [...sentences(loose.join(" ")), ...doc.steps];
  }
  return doc;
}

export function writeDoc(feature: string, doc: ScenarioDoc): string {
  const newline = feature.includes("\r\n") ? "\r\n" : "\n";
  const lines = feature.split(/\r?\n/);
  const at = locate(lines);
  if (!at) return feature;

  const head = at.indent + "Scenario" + at.outline + ": " + doc.title.trim();
  const pad = at.indent + "  ";
  const body: string[] = [];
  for (const section of ["preconditions", "steps", "expected"] as Section[]) {
    // A line typed as "- foo" in a free-text box is still one item, "foo".
    const items = doc[section].map((item) => item.trim().replace(/^[-•*]\s+/, "")).filter(Boolean);
    if (items.length === 0) continue;
    body.push("", pad + LABEL[section], ...items.map((item) => pad + "- " + item));
  }

  const rewritten = [
    ...lines.slice(0, at.head),
    head,
    ...body,
    "",
    ...lines.slice(at.end),
  ].join(newline);

  // Rule tags are replaced; every other tag (@needs:…, @mutating) is kept.
  const { tags, body: rest } = splitTags(rewritten);
  const others = tags.split(/\s+/).filter((tag) => tag && !/^@R-/i.test(tag));
  const rules = doc.rules
    .map((rule) => rule.trim().replace(/^@/, "").replace(/\s+/g, ""))
    .filter(Boolean)
    .map((rule) => "@" + rule);
  return joinTags([...rules, ...others].join(" "), rest);
}

/** The scenario as plain numbered text, for the browsing agent. */
export function docAsText(doc: ScenarioDoc): string {
  const out: string[] = [doc.title];
  if (doc.preconditions.length) out.push("", "Ön koşul:", ...doc.preconditions.map((p) => "- " + p));
  if (doc.steps.length) out.push("", "Adımlar:", ...doc.steps.map((s, i) => i + 1 + ". " + s));
  if (doc.expected.length) out.push("", "Beklenen:", ...doc.expected.map((e) => "- " + e));
  return out.join("\n");
}
