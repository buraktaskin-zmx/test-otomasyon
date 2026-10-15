# Scenario Generation — Reproducible Recipe

How to make an LLM generate one `.feature` file. Same inputs, same prompt,
every time — so runs are repeatable and reviewable.

> **This describes the recipe by hand. The pipeline is now code.**
> `POST /api/v1/scenarios/suggest` does all of it: `PromptBuilder` assembles the
> system and user prompts, `SuggestService` calls the model, `ScenarioValidator`
> checks the draft and feeds its errors back for one repair turn. Read this file
> to understand WHY the prompt is shaped this way, or to reproduce a generation
> outside the platform — but **`PromptBuilder` is the authority on the actual
> prompt text**, and the two will drift. When they disagree, the code is right.

> **For MANY scenarios from one catalog**, read
> [`docs/COKLU-SENARYO-AKISI.md`](../docs/COKLU-SENARYO-AKISI.md) instead.
> `POST /api/v1/scenarios/suggest-many` does not call the model once with "write
> five" — `VariationPlanner` computes N data combinations in Java from this
> catalog's own option lists and constraint keys, and each one becomes its own
> single-scenario generation. That document also explains where a catalog comes
> from in the first place (the MCP agent and the DOM scan) and why both are kept.

## Inputs (three, always)

1. **`VOCABULARY.md`** — the closed step grammar. Goes in the system prompt.
2. **A catalog** — `catalogs/<app>/<flow>.yaml`. The screen's labels, options,
   accounts, buttons, message. Goes in the user prompt.
3. **Login + intent** — supplied *only* at generation time:
   - **Login credentials** (CIF, user id). **Never stored in catalogs or this
     repo's generation layer.** Pasted into the user prompt per run.
   - **Test intent + data** — plain-language description of what to test and the
     values to use.

## Pipeline

```
VOCABULARY.md ─┐
catalog.yaml ──┼─▶  LLM  ─▶  scenario.feature  ─▶  dry-run gate (local)  ─▶  VDI run
login + intent ┘                                    validates step mapping     real result
```

The gate is browser-free, and today it is an HTTP call rather than Maven:

```
POST /api/v1/scenarios/validate      # ~50 ms, no browser, always 200
```

It reports every line that does not map to a deployed step (the LLM invented
one), plus the catalog-aware errors: an unknown label, a verb that does not fit
the field's type, an option the screen never offered, an invented expected
value, a re-worded success message. `POST /api/v1/runs` repeats the same check
server-side, so a typo cannot reach a browser even if the UI is bypassed.

**The Maven dry-run does not work in this repository.** `mvn test
-Dcucumber.execution.dry-run=true` finds no features at all: they live in a
directory literally named `com.ykb.uplift.uptest` (dots) while
`RunCucumberTest` selects the classpath resource `com/ykb/uplift/uptest`
(slashes). Passing a filesystem path explicitly
(`-Dcucumber.features=<path>`) bypasses the selector and does work.

---

## System prompt (fixed — paste verbatim every run)

```
You generate Cucumber .feature files for a banking test suite. You output
ONLY a valid Gherkin .feature file — no prose, no explanation, no step
definitions, no code, no markdown fences.

Absolute rules:
- Use ONLY the steps defined in the VOCABULARY below. Match each pattern
  word-for-word; only the quoted arguments change. Never invent a step.
- Use ONLY labels, dropdown options, and account numbers that appear in the
  SCREEN CATALOG. If the intent needs something not in the catalog, do NOT
  guess — output a single line "# MISSING: <what is missing>" and stop.
- Follow the parameter formats exactly: amounts in Turkish format
  (. = thousands, , = decimal), dates dd/MM/yyyy, accounts as given.
- Pick the verb by field type: amount → `enter amount` / `has amount`;
  text & date → `enter` / `field equals`; dropdown → `select ... from`;
  account_picker → `select account ... from`.
- Start with the login step for the target app (NGC vs NGI), using the
  credentials given in the LOGIN block. One Feature, one Scenario.
- Output nothing but the .feature content.

=== VOCABULARY ===
<paste the full contents of VOCABULARY.md here>
```

## User prompt (skeleton — fill the three blocks per run)

```
=== SCREEN CATALOG ===
<paste the full contents of catalogs/<app>/<flow>.yaml here>

=== LOGIN ===
# Test credentials — supplied here only, never stored in catalogs.
app: NGC
cif: "<cif>"
userid: "<userid>"     # omit for NGI

=== TEST INTENT ===
<plain-language description of the scenario + the exact data values to use>

Generate the .feature file now.
```

---

## Worked example (user prompt for Banko)

```
=== SCREEN CATALOG ===
app: NGC
flow: Banko Payment Request
navigation:
  main_menu: "NAKİT YÖNETİMİ"
  sub_menu: "Ödeme Oluştur / Talep Et"
  entry_button: "Ödeme Talep Et"
fields:
  - label: "TCKN / VKN"        { type: text, verifiable: true }
  - label: "Ad Soyad / Unvan"  { type: text, verifiable: true }
  - label: "Ödeme Tutarı"      { type: amount, verifiable: true }
  - label: "Vade Tarihi"       { type: date, verifiable: true }
  - label: "Açıklama"          { type: text, verifiable: true }
  - label: "Alıcı Hesabı"      { type: account_picker, accounts: ["0006 7010 0000 0003 4322"] }
buttons: ["Devam", "Onay"]
success_message: "Ödeme talebiniz karşı tarafa iletilmiştir. ..."

=== LOGIN ===
app: NGC
cif: "DI00002018"
userid: "3014"

=== TEST INTENT ===
Request a Banko payment of 2.500,75 TL to recipient "ACME LTD" with tax id
"1234567890", due date 30/09/2026, description "Aylik odeme", paid into account
0006 7010 0000 0003 4322. After clicking Devam, verify every entered field on
the confirm screen. Then click Onay and verify the success message.

Generate the .feature file now.
```

(Paste the *real* catalog file contents, not this abbreviated inline form.)

---

## After generation — checklist

1. Save the model output as `src/test/resources/.../<flow>-generated.feature`.
2. **Local dry-run gate** — `mvn test -Dcucumber.execution.dry-run=true`.
   - `# MISSING: ...` in the output → the catalog lacked something; fix the
     catalog or the intent, regenerate.
   - "Undefined step" → the LLM invented a step; tighten the prompt, regenerate.
3. Only on a clean dry-run, hand the file to the VDI for a real run.
4. For the **first** experiments, generate a flow you've already proven by hand
   (Banko/DBS) with *different data*, so you can judge correctness against a
   known-good answer.

## Rules that prevent silent failures

- **Login is passed at generation time, never stored in a catalog.** If a
  catalog ever contains credentials, that's a bug — remove them.
- **The `# MISSING` rule** makes the LLM fail loudly instead of inventing a
  plausible-but-wrong label. Keep it in the system prompt.
- **Never relax "output only the .feature"** — extra prose breaks the
  save→dry-run automation.
