# Gherkin Vocabulary — LLM Generation Contract

This is the **complete and exclusive** vocabulary for generating Cucumber
`.feature` files for the Yapı Kredi internet banking tests. An LLM generating
scenarios MUST treat this as a closed grammar.

> Source of truth: the step patterns below are taken verbatim from the Java
> step definitions (`CommonSteps`, `NGCSteps`, `NGISteps`). If a step is not in
> this file, it does not exist. Do not invent steps. Do not write step
> definitions or page-object code — only `.feature` files.
>
> **This file is the human-facing view; the machine-readable one is generated.**
> The executor produces `vocabulary.json` from the `@Given/@When/@Then`
> annotations at build time and serves it at `GET /cucumber/vocabulary`; the
> orchestrator's validator and the LLM prompt both read that. If the two ever
> disagree, the generated JSON is right and this file is stale — check with
> `GET /api/v1/vocabulary` (currently 16 steps).

---

## Hard rules (read first)

1. **Use only the steps listed in this file.** Any other phrasing will fail to
   map to a step definition and the scenario will not run.
2. **Match the pattern exactly**, including word order and the literal words
   (`the`, `from`, `field`, `has amount`). Only the `{string}`/`{int}`
   placeholders change.
3. **Every `{string}` argument is wrapped in double quotes** in the feature
   file. `{int}` arguments are bare numbers.
4. **Labels must be the exact on-screen text** of the field/button/menu,
   including Turkish characters and spacing (e.g. `"Ad Soyad / Unvan"`,
   `"NAKİT YÖNETİMİ"`). Do not translate or paraphrase labels.
5. **Pick the right verb for the field type** (see Selection and Verification
   sections). A normal dropdown ≠ an account picker; a text verify ≠ an amount
   verify.
6. **NGC and NGI are different applications.** Use the login step for the target
   app only; everything else is shared. Never mix an NGC login with an
   NGI-only flow or vice versa.
7. **Each scenario is self-contained**: start with a login step, end with a
   verification.

---

## Parameter formats (conventions)

| Kind | Format                                                                                                         | Example |
|------|----------------------------------------------------------------------------------------------------------------|---------|
| Amount (enter & verify) | Turkish: `.` = thousands, `,` = decimal (kuruş). Thousands separators optional on input; decimals recommended. | `"1250,55"`, `"54.999.999,50"` |
| Date | `dd/MM/yyyy`                                                                                                   | `"27/08/2026"` |
| Account number | Full number; internal spacing is ignored on match                                                              | `"0006 7010 0000 0003 4322"` |
| CIF | id string for test data                                                                                        | `"DI00002018"` |
| User id | Numeric string                                                                                                 | `"3014"` |
| Plain text | Exact value, exact case                                                                                        | `"Test"`, `"21203030786"` |

Amount **verification** is normalized (thousands separators tolerated); amount
**entry** drives a masked, kuruş-aware field. Text/date **verification is EXACT**
— no normalization — so the expected value must match the screen character for
character.

---

## Steps — Authentication (application-specific)

Choose exactly one based on the target app.

### NGC (corporate)
```
Given login with cif {string} and userid {string}
```
Example: `Given login with cif "DI00002018" and userid "3014"`

### NGI (individual / retail)
```
Given login to ngi with cif {string}
```
Example: `Given login to ngi with cif "SS27856224"`

> NGC and NGI render different HTML; only the login step differs between them.
> All steps below are shared and work on both apps.

---

## Steps — Navigation

```
And go to {string}
And navigate to {string} {string}
And click {string}
```

- `go to {string}` — open an absolute URL. Rarely needed; prefer `navigate to`.
- `navigate to {string} {string}` — main menu, then sub-menu, by their exact
  on-screen text. Example:
  `And navigate to "NAKİT YÖNETİMİ" "Ödeme Oluştur / Talep Et"`
- `click {string}` — click a **button or link** by its visible text. Example:
  `And click "Devam"`

---

## Steps — Input

```
And enter {string} {string}
And enter amount {string} {string}
```

- `enter {string} {string}` — type a value into a **text/date input** identified
  by its field label. Example: `And enter "Vade Tarihi" "27/08/2026"`
- `enter amount {string} {string}` — type into a **masked money field**
  (kuruş-aware). Use this for any monetary amount, never plain `enter`.
  Example: `And enter amount "Ödeme Tutarı" "1250,55"`

---

## Steps — Selection (dropdowns & account pickers)

```
And select {string} from {string}
And select account {string} from {string}
And select account at index {int} from {string}
```

- `select {string} from {string}` — pick `optionText` from a **standard
  dropdown** (Select2) identified by its label.
  Example: `And select "Bu Hafta" from "Hızlı Tarih Seçimi"`
- `select account {string} from {string}` — pick an account from an **account
  combobox** by matching the account number (spacing ignored).
  Example: `And select account "0006 7010 0000 0003 4322" from "Alıcı Hesabı"`
- `select account at index {int} from {string}` — pick an account by **0-based
  position** in the list (use when the exact number isn't known).
  Example: `And select account at index 0 from "Alıcı Hesabı"`

> Decision rule: a money/currency account field → use a `select account …` step.
> Any other dropdown (dates, firm choice, type, etc.) → use plain `select … from`.

---

## Steps — Result grids (row selection)

```
And select row {string}
And select row at index {int}
```

A result grid picks its row with a **radio button**, not a dropdown: there is no
field label bound to it, so no `select … from` step can reach it.

- `select row {string}` — pick the row containing this text. The radio's own
  cell is matched first, then the whole row, so a value in any column works.
  Example: `And select row "21203030786"`
- `select row at index {int}` — pick by **0-based** position among the radios on
  the page. Example: `And select row at index 0`

---

## Steps — Verification

```
Then verify the {string} field equals {string}
Then verify the {string} has amount {string}
Then verify {string} text is displayed
```

- `verify the {string} field equals {string}` — assert a **text or date** field
  equals the expected value, **EXACT match**. Example:
  `Then verify the "Vade Tarihi" field equals "27/08/2026"`
- `verify the {string} has amount {string}` — assert a **money** field equals
  the expected amount (normalized). Example:
  `Then verify the "Toplam Fatura Tutarı" has amount "54.999.999,50"`
- `verify {string} text is displayed` — assert a **free-text message** (e.g. a
  success banner) is present on the page. Example:
  `Then verify "Ödeme talebiniz karşı tarafa iletilmiştir." text is displayed`

> Use `field equals` for text/dates and `has amount` for money. They are not
> interchangeable — `field equals` does an exact string compare and will fail on
> a formatted amount.

---

## Steps — Timing

```
And wait {string} seconds
```

- `wait {string} seconds` — fixed pause. **Avoid.** The framework auto-waits on
  every action and assertion; only use this as a last resort for a known async
  gap, and prefer a verification step instead.

---

## Gherkin structure

- One `Feature:` per file, with a one-line title and optional description lines.
- One or more `Scenario:` blocks under it.
- First step uses `Given`; subsequent action steps use `And`; assertions use
  `Then`. (Cucumber treats `Given/When/Then/And` interchangeably for matching,
  but follow this convention for readability — analysts review these.)
- Keep scenarios clean and readable: no implementation detail, no comments
  explaining locators.

---

## Worked example (NGC — Banko payment)

```gherkin
Feature: Banko Request Payment Happy Path
  User has limit, auth and Banko settings and sms login.

  Scenario: User can enter information and request payment from Banko interface.
    Given login with cif "DI00002018" and userid "3014"
    And navigate to "NAKİT YÖNETİMİ" "Ödeme Oluştur / Talep Et"
    And click "Ödeme Talep Et"

    And enter "TCKN / VKN" "21203030786"
    And enter "Ad Soyad / Unvan" "Test"
    And enter amount "Ödeme Tutarı" "1250,55"
    And enter "Vade Tarihi" "27/08/2026"
    And enter "Açıklama" "Test"
    And select account "0006 7010 0000 0003 4322" from "Alıcı Hesabı"

    And click "Devam"

    Then verify the "TCKN / VKN" field equals "21203030786"
    Then verify the "Ad Soyad / Unvan" field equals "Test"
    Then verify the "Ödeme Tutarı" has amount "1250,55"
    Then verify the "Vade Tarihi" field equals "27/08/2026"
    Then verify the "Açıklama" field equals "Test"

    And click "Onay"
    Then verify "Ödeme talebiniz karşı tarafa iletilmiştir. Tarafınıza yapılacak ödemenin detaylarını Banko İşlemleri'nden görüntüleyebilirsiniz." text is displayed
```

---

## Generation checklist (self-check before output)

- [ ] Every line matches a pattern in this file, word for word.
- [ ] All `{string}` args are double-quoted; `{int}` args are bare numbers.
- [ ] Labels are exact on-screen text (Turkish chars, spacing, punctuation).
- [ ] Money fields use `enter amount` / `has amount`; text/dates use
      `enter` / `field equals`.
- [ ] Account fields use a `select account …` step, not plain `select`.
- [ ] Exactly one login step, matching the target app (NGC vs NGI).
- [ ] No invented steps, no step-definition code, no locators.
```
