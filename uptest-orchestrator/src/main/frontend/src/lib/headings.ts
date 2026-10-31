/*
  "Başlık" helpers shared by the scenario table and the new-scenario form.
*/

/**
 * The form a heading name or a search is compared in: spaces collapsed,
 * lower case, and I/İ/ı/i all one letter -- the same rule as the server's
 * ScenarioStore.headingKey, so the form and the server agree on what
 * "the same heading" means.
 */
export function foldTr(text: string): string {
  return text.trim().replace(/\s+/g, " ").toLocaleLowerCase("tr-TR").replace(/ı/g, "i");
}

/** Whether `text` contains `query`, both folded. An empty query matches everything. */
export function matchesTr(text: string, query: string): boolean {
  const wanted = foldTr(query);
  return wanted === "" || foldTr(text).includes(wanted);
}

/* Which headings are open, remembered for the browser session. A heading
   starts CLOSED; only the open ones are stored. Storage can throw (private
   window, blocked site data), and then the state simply lives as long as
   the page -- never a reason to fail the table. */
const OPEN_KEY = "uptest.headings.open";
let memory: Set<string> | null = null;

export function openHeadings(): Set<string> {
  if (memory) return new Set(memory);
  let read: string[] = [];
  try {
    const raw = window.sessionStorage.getItem(OPEN_KEY);
    const parsed: unknown = raw ? JSON.parse(raw) : [];
    if (Array.isArray(parsed)) read = parsed.filter((id): id is string => typeof id === "string");
  } catch {
    read = [];
  }
  memory = new Set(read);
  return new Set(memory);
}

export function rememberOpenHeadings(open: ReadonlySet<string>): void {
  memory = new Set(open);
  try {
    window.sessionStorage.setItem(OPEN_KEY, JSON.stringify([...open]));
  } catch {
    // Kept in memory only.
  }
}
