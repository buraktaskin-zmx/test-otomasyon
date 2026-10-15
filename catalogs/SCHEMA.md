# Screen Catalog Schema

A **catalog** is a curated, structured description of one flow's screens —
exactly the labels, dropdown options, account numbers, buttons, and messages an
LLM needs to author a valid `.feature` for that flow, and nothing more.

- **One file per flow**, YAML, under `catalogs/<app>/<flow>.yaml`
  (`<app>` = `ngc` or `ngi`). Name to match the feature file where practical.
- **Curated, not raw.** Include only what the generator needs. Never paste DOM.
  This keeps catalogs token-cheap.
- **Fixed shape.** Hand-written and auto-generated catalogs MUST produce the
  same keys, so downstream tooling can rely on it. This file is that contract,
  and it has three consumers: the LLM prompt (`PromptBuilder`), the validator
  (`CatalogModel`), and two producers that must both emit this shape — the DOM
  scan (`PW/discovery/CatalogDiscovery`) and the MCP agent
  (`orchestrator/explore/CatalogSink`).
- **The `navigation` block below is enforced as a contract, not just described.**
  `CatalogContractTest` reads its keys out of this file, calls the agent's
  `submit_catalog` with every argument filled, and fails if any key does not
  reach the produced YAML. Adding a navigation key here therefore BREAKS THE
  BUILD until the tool has an argument for it and the renderer emits it. That
  is the point: `entry_tab`, `entry_choice` and `shown_when` were each
  documented here and silently unreportable by the agent — and because this
  file is pasted into the agent's prompt verbatim, the model read about keys it
  had no way to answer with.

## What is enforced, and what only advises

Worth knowing before trusting a green validation. `CatalogModel` parses exactly
two top-level keys — `fields` and `success_message` — and inside a field only
`label`, `type`, `options`/`accounts` and `readonly`. `CatalogService` hands the
YAML to the model **verbatim**, so everything else in this file reaches the
generator as text and is checked by nobody:

| Key | Validator | Model |
|---|---|---|
| `fields.label`, `.type`, `.options`, `.accounts`, `.readonly` | **enforced** | reads |
| `success_message` | **enforced** (paraphrase is an error) | reads |
| `navigation.entry_row` | **enforced AND auto-corrected** — see below | reads |
| `navigation.*` (other keys: `main_menu`, `sub_menu`, `entry_tab`, `entry_choice`) | not parsed | reads |
| `verifiable` | not parsed | reads |
| `shown_when` | parsed, **not** enforced — see below | reads |
| `max_length`, `min`, `max`, `numeric` | parsed, **not** enforced | reads |

The last two rows are parsed by `CatalogModel` but checked by nobody: they exist
for `VariationPlanner`, which uses them to choose a different value for each
scenario of a batch. So a wrong `max_length` does not fail validation — it makes
the planner pick a value the screen truncates, and the form then validates a
number nobody chose.

So a wrong `entry_tab` is not a validation error — it is a thirty-second
timeout in a browser, blamed on the first field of a form that never opened.
Parsing is also LENIENT by design: unknown keys are ignored and a catalog that
will not parse at all switches the catalog checks off rather than failing.

## Top-level keys

| Key | Required | Meaning |
|-----|----------|---------|
| `app` | yes | `NGC` or `NGI`. Determines which login step the generator emits. |
| `flow` | yes | Human name of the flow. |
| `navigation` | yes | How to reach the flow's entry screen. |
| `fields` | yes | The fields the scenario can read/fill, in screen order. |
| `buttons` | no | Clickable buttons/links in the flow (e.g. `Devam`, `Onay`). |
| `success_message` | no | Exact confirmation text, for a final `verify ... text is displayed`. |

Catalogs deliberately contain **no login/credential data**. A catalog describes
a screen, not who logs in. Login args (CIF, user id) are test credentials and
are supplied separately at generation time — never stored here. The `app` key is
all the generator needs to pick the correct login step pattern.

## `navigation`

```yaml
navigation:
  main_menu: "NAKİT YÖNETİMİ"      # exact menu text
  sub_menu: "Banko İşlemleri"
  entry_tab: "Ödenecekler"         # optional: a tab/filter to switch to first
  entry_row: "FORD DBS ODEMELERI"  # optional: a result-grid row to pick first
  entry_button: "Ödeme Talep Et"   # optional: a button that opens the form
  entry_choice: "Bilgi Girişi"     # optional: a radio that decides WHICH form
```

They are performed in the order written: menu, sub-menu, tab, row, button, choice.

`entry_tab` is for screens whose sub-menu lands on a list that is already
filtered. Banko İşlemleri opens on "Yanıt Bekleyenler"; the payments a test is
about are under "Ödenecekler", and a scenario that omits the switch picks the
right row of the wrong list — every later step passes and the run documents a
different record. It is driven with the ordinary `click` step, because these
tabs are links.

`entry_row` is for flows whose sub-menu lands on a result grid rather than a
form: the row is chosen with `select row` BEFORE `entry_button` is clicked.
Online DBS İşlemleri works this way — the institution is picked from the grid,
then "Fatura Oluştur" opens the form for it.

It is the one navigation key that is both checked AND repaired, because the
mistake it prevents is mechanical. A draft that writes `click "<entry_row>"` is
asking Playwright for a button or a link, and a grid cell is neither: the run
spends its full timeout on it. `orchestrator/llm/EntryRow` therefore deletes
that line when the draft also selects the row properly, and rewrites it to
`select row` when it does not — before the validator ever sees it. The
validator's `rowNotClickable` check remains as the backstop for when the
rewriter declines (an unparseable catalog, or an executor that does not publish
`select row`).

Note the contrast with `entry_tab` and `entry_button` immediately above and
below: those really are driven with `click` — the tabs are links — so a draft
that clicks them is correct and is left alone. Only `entry_row` names something
`click` cannot reach.


`entry_choice` is for a form that is gated behind a radio choice. On DBS Fatura
Oluştur, pressing "Fatura Oluştur" does not open the form — it opens a fork,
"Oluşturma Tipi": **Bilgi Girişi** or **Dosya Yükleme**. It is driven with
`choose`, which exists because `select row` matches a radio by its table cell
and row and therefore cannot see one in a plain form section.

It is NAVIGATION, not a field, and that distinction is the point. The two
branches are different screens: the catalogued fields belong to the Bilgi Girişi
form and not one of them exists on the Dosya Yükleme screen. Modelled as a field
with options, `VariationPlanner` would vary it, half the scenarios would land on
the upload screen, and every field after it would time out. A flow with its own
screen gets its own catalog — see "One file per flow" above.

## `fields`

A list, in the order they appear on screen. Each entry:

| Field key | Required | Meaning |
|-----------|----------|---------|
| `label` | yes | Exact on-screen label text (Turkish chars, spacing, punctuation). |
| `type` | yes | One of: `text`, `amount`, `date`, `dropdown`, `account_picker`, `checkbox`. |
| `options` | for `dropdown` | List of exact selectable option texts. An ABSENT or empty list is a gap, not "anything goes" — see below. |
| `accounts` | for `account_picker` | List of full account numbers (spacing as shown). |
| `verifiable` | no | `true` if this field appears on a confirm/summary screen and can be asserted. Defaults to readable+writable per its type. |
| `readonly` | no | `true` if the APPLICATION fills this field, not the scenario. Never written to, only verified. |
| `shown_when` | no | `{field, value}` — this field only exists on screen once that field holds that value. |
| `max_length` | no | The box's own `maxlength`. For `amount`, the LIRA box's. |
| `min` / `max` | no | A number input's range. |
| `numeric` | no | `true` if the box accepts digits only. |

### An empty `options` list is a hole, and it has two causes

A `dropdown` or `account_picker` with no options is a field a scenario cannot
use: nothing tells the generator what it is allowed to pick, and nothing tells
`ScenarioValidator` what to accept. It is never "unconstrained".

The two causes need different repairs, so the producers are made to distinguish
them:

- **Nobody opened it.** On this application a closed dropdown contains nothing —
  Select2 builds the list on click — so an accessibility snapshot of a closed
  control shows the field and no choices. The DOM scan opens it
  (`CatalogDiscovery.openAndReadOptions`); the MCP agent is told to, and
  `note_field` now sends it back when it did not.
- **It really was empty.** A dependent dropdown has no options until its parent
  is chosen: "Döviz Cinsi" stays empty until "Bayi Kodu" is set. The agent may
  record that, but only by saying so, which lands in the draft as
  `# emin degil: <reason>` above the field. An analyst fills the list in by hand.

A draft carrying neither options nor a reason is reported by
`ExploreController.problems()` as the first case, which is the one worth
re-walking.

### The constraint keys, and who writes them

`max_length`, `min`, `max` and `numeric` exist for one consumer: the batch
generator (`VariationPlanner`), which has to produce N scenarios that differ.
Rotating a dropdown's `options` is free, but a text box offers no list — so the
only honest variation is its LENGTH, and that needs a measured bound. Without
one the planner falls back to a conservative ladder and SAYS SO in the batch's
notes; it never invents a bound.

**Only the DOM scan can produce them.** `CatalogDiscovery` reads the attributes
off the input (`maxlength`, `min`, `max`, `type=number`, `inputmode`). The MCP
agent works from an accessibility snapshot, which carries no attributes at all,
so an agent-written catalog is silent here. That is the same shape as the
Select2 v3-versus-v4 split: two producers, opposite blind spots, both kept.

An ABSENT key means "nobody measured it", never "unbounded" — the two lead to
different behaviour and a `max_length: 0` written to mean "none" would be read
as a zero-character box.

Known gap: a jQuery-masked field enforces its shape in JavaScript rather than in
attributes, so a mask that permits only digits is invisible to the scan unless
the markup also says so. `numeric` is deliberately NOT guessed from a label's
wording — "Fatura No" accepts `FTR-2026-0001` on this application.

`shown_when` is for fields a choice reveals. On DBS Fatura Oluştur, picking
"E-Fatura" for "Fatura Tipi" makes "E-Fatura Hash Kodu" appear and become
required; with "Normal" it is not on the page at all, so a scenario that fills
it hunts for a field that does not exist until it times out. A scenario either
sets the named value AND fills the field, or does neither.

**It is NOT for a dropdown whose options arrive late.** "Döviz Cinsi" is empty
until "Bayi Kodu" is chosen, but the field is on the screen the whole time — it
is an ordinary dependent dropdown and needs no `shown_when`. The two are easy to
confuse because both follow a choice, so the test is: *was the LABEL missing
from the page, or was the label there with nothing inside it?* Only the first is
`shown_when`.

Getting it wrong is expensive in one direction. `VariationPlanner` excludes a
conditional field from every variation that does NOT pick the trigger value, so
a `shown_when` on an always-present required field leaves it empty in all those
scenarios and the form refuses them — four runs out of five on a five-option
parent. A missing `shown_when` on a field that is genuinely always there costs
nothing. `ExploreController.problems()` therefore asks about any choice field
carrying both a condition and a filled option list.

In a batch that decision is no longer left to the model: `VariationPlanner`
assigns the field only in the variations that chose the triggering value, and
lists it as explicitly off-limits in the others. For a single scenario the prompt
still asks the model to get it right, which is why `shown_when`'s parent must be
listed BEFORE it — the planner walks the fields once, in catalog order, and
cannot evaluate a condition whose parent it has not reached. A late parent is
reported rather than guessed at.

`readonly` exists because a disabled input is not optional — it is not writable
at all. On DBS Fatura Oluştur the app looks "Bayi Ünvanı" up from the chosen
"Bayi Kodu"; a scenario that tries to type into it waits 30 s for an element
that will never accept input, and the run is wasted.

`type` maps directly to vocabulary verbs:

| `type` | enter with | verify with | select with |
|--------|-----------|-------------|-------------|
| `text` | `enter` | `verify the {} field equals {}` | — |
| `date` | `enter date` | `verify the {} has date {}` | — |
| `amount` | `enter amount` | `verify the {} has amount {}` | — |
| `dropdown` | — | — | `select {} from {}` |
| `account_picker` | — | — | `select account {} from {}` / `select account at index {} from {}` |
| `checkbox` | `check {}` | — | — |

`date` takes `enter date`, not `enter`, and the value is an OFFSET — `"bugün"`,
`"+3 gün"`, `"+1 hafta"` — resolved when the browser runs. `enter` would set a
date too and the browser would not complain; what it cannot do is keep working.
A literal date freezes the day the scenario was generated, so one drafted this
morning is in the past a fortnight later and a maturity field refuses it. An
absolute `gg/aa/yyyy` still passes through `enter date` untouched, for a test
that is genuinely about one specific day.

`checkbox` is a box the user ticks, captioned with a whole sentence on these
screens ("Garanti tutarı girmek istiyorum."). It is NOT `text`: a scenario that
tries to type into one waits for an input that will never accept text.

Ticking one usually changes what the transaction IS, or reveals a further
field, so a scenario ticks a box only when the analyst asked for it. A catalog
lists every box it found; whether to press one is the scenario's decision, not
the catalog's.

## Minimal example

```yaml
app: NGC
flow: Example
navigation:
  main_menu: "MENÜ"
  sub_menu: "Alt Menü"
fields:
  - label: "Tutar"
    type: amount
buttons: ["Devam"]
success_message: "İşlem tamamlandı."
```
