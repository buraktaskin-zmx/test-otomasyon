import type { Origin, ValidationResult } from "../../api/types";

/*
  The app's internal per-draft shape -- app.js:737-751. NOT identical to
  the wire's DraftScenario: it drops `index` (array position already is
  the index) and `log` (never rendered per-draft, same as app.js), and adds
  `origin`, pinned once at generation time so opening any card later
  validates/runs against the SCREEN IT WAS GENERATED FOR, exactly like the
  single-scenario origin pinning in lib/originScreen.ts.
*/
export interface Draft {
  label: string;
  feature: string;
  validation: ValidationResult;
  attempts: number;
  values: Record<string, string>;
  ticked: string[];
  excluded: string[];
  origin: Origin;
}
