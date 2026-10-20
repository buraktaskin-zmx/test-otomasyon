/*
  The login step, written into a scenario from a saved test user -- the same
  rules as the orchestrator's LoginStep.java, so the line in the file is the
  one a run would have written anyway:

    NGC  Given login with cif "<cif>" and userid "<userid>"
    NGI  Given login to ngi with cif "<cif>"

  Any login line already there is replaced, and the new one goes BEFORE THE
  FIRST STEP rather than under the title, because a registered scenario keeps
  its natural-language description between the two and Gherkin refuses free
  text after a step.
*/

const EXISTING_LOGIN = /^[ \t]*(?:Given|When|And|But)[ \t]+login\b.*(?:\r?\n|$)/gm;
const SCENARIO_TITLE = /^([ \t]*)(?:Scenario|Scenario Outline|Senaryo)\s*:.*(?:\r?\n|$)/m;
const STEP = /^[ \t]*(?:Given|When|Then|And|But|\*)[ \t]/m;

export function loginStepFor(app: string, cif: string, userid: string): string {
  return app.toLowerCase() === "ngi"
    ? `Given login to ngi with cif "${cif.trim()}"`
    : `Given login with cif "${cif.trim()}" and userid "${userid.trim()}"`;
}

/** True when the scenario has Gherkin steps besides a login. */
export function hasSteps(feature: string): boolean {
  return STEP.test(feature.replace(EXISTING_LOGIN, ""));
}

/**
 * The feature with its login line set to `step`. A scenario with no Gherkin
 * steps at all is returned untouched: a lone login would turn a
 * natural-language scenario into a one-step run that only logs in.
 */
export function withLogin(feature: string, step: string): string {
  if (!hasSteps(feature)) return feature;
  const without = feature.replace(EXISTING_LOGIN, "");
  const title = SCENARIO_TITLE.exec(without);
  if (!title) return feature;
  const afterTitle = title.index + title[0].length;
  const rest = without.slice(afterTitle);
  const step1 = STEP.exec(rest);
  const at = afterTitle + (step1 ? step1.index : 0);
  const newline = feature.includes("\r\n") ? "\r\n" : "\n";
  return without.slice(0, at) + (title[1] ?? "") + "  " + step + newline + without.slice(at);
}
