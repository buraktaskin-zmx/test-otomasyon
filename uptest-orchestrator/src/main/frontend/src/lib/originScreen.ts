import type { Origin } from "../api/types";

/*
  THE single most hazardous piece of logic in this app to get wrong -- see
  app.js:300-337 for the 20-line comment this one is a direct port of, and
  the plan's "origin pinning" section for why.

  Short version: a scenario must be validated and run against the screen it
  was GENERATED from, not whichever catalog the analyst's dropdown currently
  shows. The two were once conflated, the model's invented value for a
  readonly field survived validation as a result, and it cost a real browser
  run to notice.

  resolveOrigin takes the live form state as an explicit PARAMETER rather
  than reaching into it itself (the way app.js's originScreen() reached into
  `el.suggestCatalog.value`), specifically so the fallback path stays visible
  at every call site instead of hiding behind a getter. There is no way to
  write the buggy version -- deriving {app,flow} from the live dropdown and
  skipping the pinned value -- without deleting the first parameter.

  pinOrigin (see hooks/useOrigin.ts) is called from exactly one place:
  SuggestPanel, on a successful /suggest or /suggest-many response. Do NOT
  call it from a catalog <select>'s onChange -- that reflex is the bug.
*/
export function resolveOrigin(pinned: Origin | null, fallback: Origin): Origin {
  return pinned ?? fallback;
}
