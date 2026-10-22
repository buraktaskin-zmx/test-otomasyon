# Product context — schema

A **product** is a banking product (Banko, DBS, Maaş Ödemeleri) and the
written knowledge about it: the screens it owns, and the rules it must obey.

```
products/<app>/<product>/
├── product.yaml         identity + the flows it claims   (REQUIRED)
├── rules.yaml           the business rules               (optional)
├── test-data.yaml       alias catalogue                  (optional)
├── flow.md              state machine, free text         (optional)
├── roles.md             who may do what, free text       (optional)
├── known-issues.md      bugs not to write tests for      (optional)
├── open-questions.md    what nobody has answered         (optional)
└── scenarios/
    └── <flow>/          one folder per screen
        └── NN-ad.feature
```

Only `product.yaml` is load-bearing. A product being written has most of the
rest missing and must still load — that is the ordinary state of a product
someone started this morning.

---

## Why this is not part of the catalog

A catalog is **discovered**. `CatalogDiscovery` reads the DOM; the MCP agent
reads an accessibility snapshot. Both produce a description of a screen as it
stands, and neither can say whether what it found is *correct*. Ask an agent to
"test this screen" and it will record the İptal button it finds on a paid
invoice as expected behaviour — turning a bug into a passing test.

A rule is **written**. It says what the application ought to do, which no
amount of looking at the application establishes.

Different producer, different source of truth, therefore a different document.

There is a second reason, and it decides the direction of the binding:
**rules exist before the screen does.** A product can be specified while its
catalogs are still being walked. So the product declares its flows, rather than
each catalog naming its product — and a flow listed here with no catalog on
disk is not an error, it is the work queue.

---

## `product.yaml`

```yaml
app: ngc                      # must match the directory above
name: "Maaş Ödemeleri"        # what the analyst sees in the picker
category: "Ödemeler"          # the business area above the product in the tree
description: >
  Bir cümleyle ürün ne işe yarar.
flows:                        # catalog names, without the app prefix
  - maas-odeme-olustur        # → catalogs/ngc/maas-odeme-olustur.yaml
  - maas-odeme-onay
screen_names:                  # optional: the screen's name in the tree
  maas-odeme-olustur: "Maaş Ödeme Oluştur"
```

| Key | Required | Read by |
|---|---|---|
| `app` | yes | nothing — the DIRECTORY decides, as with catalogs |
| `name` | no | the picker and the tree; falls back to the directory name |
| `category` | no | the tree's top level; blank products bucket under "Sınıflandırılmamış" |
| `description` | no | the product overview. Display only, never the prompt |
| `flows` | no | the picker's screen filter, and the missing-catalog report |
| `screen_names` | no | flow → display name in the Kayıtlı Senaryolar tree and table title; a flow left out shows as itself |

`category` is deliberately NOT derived from `app`. NGC and NGI are
**applications**; Ödemeler and Hesap İşlemleri are **business areas** that cut
across them, and a product that gains an NGI screen one day must not change
business area because of it.

---

## `rules.yaml`

```yaml
rules:
  - id: R-MAAS-010
    title: "IBAN format kontrolü"
    status: DOĞRULANDI
    description: >
      Dosyadaki bir satırda IBAN TR ile başlamıyor veya uzunluğu hatalıysa
      satır "Hatalı" işaretlenir ve işlem devam etmez.
    fields: ["IBAN", "Alıcı Adı"]
    messages:
      - "Geçersiz IBAN numarası girdiniz."
    degerler:
      secenekler: ["Bu Hafta", "Son 1 Ay", "Son 12 Ay"]
      varsayilan: "Son 1 Ay"
    source: "Analiz dokümanı v2.3, bölüm 4.1"
```

| Key | Required | What it does |
|---|---|---|
| `id` | **yes** | A rule without one is dropped: it could never be ticked, tagged or reported on |
| `title` | no | The checkbox label |
| `status` | no | `DOĞRULANDI` or `VARSAYIM`. **Anything else, including absent, counts as an assumption** |
| `description` | no | Goes into the prompt, folded onto one line |
| `fields` | no | Catalogued labels this rule touches. **Read by nobody yet** — reserved for the relevance filter |
| `messages` | no | **The enforced part.** See below |
| `degerler` | no | Named lists of literals this rule FIXES. Enforced the same way `messages` is, and diffed when the rule changes — see below |
| `source` | no | Where the rule came from; shown to the analyst, never to the model |

### `status` defaults to "assumption", deliberately

A missing status reads as "nobody checked", never as "verified". Defaulting
the other way would quietly promote a guess to a fact, and carrying a status at
all is only worth doing if the two are different. The UI leaves unconfirmed
rules unticked for the same reason: putting an assumption into a test should
take a deliberate click.

### `messages` is the part that reaches code

Everything else here steers the model. `messages` is **checked**.

`ScenarioValidator.checkMessageIsCatalogued` used to accept exactly one
asserted sentence — the catalog's `success_message` — and its own javadoc
admitted the cost: *"a negative scenario asserting an error dialog's text is
flagged too"*. For a payments product that is most of the suite, because nearly
every rule worth writing is a refusal, and a screen's catalog has nowhere to
record one.

The accepted set is now `success_message` ∪ every rule's `messages`.

Two consequences worth knowing:

- **Copy the sentence, do not improve it.** The comparison is character for
  character after case-folding and whitespace collapse. A tidier rewording is a
  scenario that cannot pass.
- **Every rule's messages count, not just the ticked ones.** Ticking shapes the
  PROMPT; the validator sees the whole product. Tying the two together would
  mean a draft accepted during generation being refused by the identical check
  at the run gate, because a checkbox was cleared in between.

### `degerler` — the literals a rule fixes

A named list per fact: `secenekler`, `varsayilan`, whatever the rule actually
pins down. A scalar is read as a one-item list, so `varsayilan: "Son 1 Ay"` and
`secenekler: [...]` come out the same shape and no reader has to ask which one
it got. Not a map at all costs the values, never the rule.

It earns its place twice, and neither use needs a reference syntax inside
scenarios:

- **The validator accepts them**, exactly as it accepts `messages`. A
  dropdown's options are something the screen PRINTS, so
  `verify "Son 12 Ay" text is displayed` is quoting a written-down fact rather
  than inventing a sentence. On a screen with no catalog this list is the only
  thing the check can consult.
- **A change is diffed value by value.** When the rule moves, every scenario
  bound to it is marked "bayat" and the detail view names the values that were
  added and dropped, plus the lines of the scenario still carrying a dropped
  one. See below.

### A changed rule marks its scenarios, and never rewrites them

A scenario is bound to a rule by id, so a rule being **renamed or deleted** was
always caught — the chip reads "unknown". A rule whose **text changed** was
not: the id still matched and every scenario on it went on testing the old
wording in silence.

`SourceSnapshot` closes that. When a scenario is created, imported or reviewed,
the full text of every rule and alias it names is stored alongside it
(`SCENARIO.reviewed_sources`) — a copy, not a hash, because "what changed" is
the only half of the question an analyst can act on. Every read compares it to
the current files.

**It reports and stops there, on purpose.** The same difference means two
opposite things: the application changed and the rule caught up, so the
scenario should follow; or the rule was simply written down wrong and has been
corrected, in which case the scenario should change AND the run should stay
RED, over a defect that is still there. Rewriting the scenario from the rule
would turn that second case into a green test over a live bug — the oracle
problem arriving through the back door, this time as maintenance. So the badge
names what moved, points at the lines worth reading, and offers two ways out a
person chooses between: edit the scenario, or "Gözden geçirildi" (re-stamp,
scenario untouched).

An **unstamped** scenario reports nothing. A badge that is on for every row
says nothing about any of them, which is also why the import and the one-time
backfill stamp against the files as they stand: the scenarios and `rules.yaml`
travelled here in the same commit, so as far as anyone knows they agree.

### Duplicate ids

The first rule wins and the rest are reported (`duplicateIds` in the API, shown
in the Ürün panel). A lenient parser that silently dropped them would leave the
analyst reading a file whose contents disagree with the screen.

---

## `scenarios/` — the registry

```
scenarios/alici-listesi-islemleri/01-firma-secilmeden-listele.feature
          └─ the FLOW ─────────┘  └─ the file, and the row's identity ─┘
```

**One folder per screen, and the folder name IS the flow** — the same name the
catalog has. That is how a scenario finds its screen without anything being
written down twice, and a folder nobody declared in `flows:` shows up in the
Ürünler tree as `bildirilmemiş` rather than silently becoming a sixth screen.

**There is no registry file.** Everything the Ürünler table shows is derived
from the `.feature` itself, because Gherkin already has somewhere to put each
of it and a second list of the same scenarios would start by agreeing and end
by disagreeing:

```gherkin
@R-AL-011 @needs:MAKER_01 @needs:FIRMA_LISTELI
Feature: Alıcı Listesi İşlemleri

  Scenario: Firma seçilmeden Listele çalışmaz
    Firma alanı boş bırakılıp Listele tıklanır; sorgu çalışmaz.

    Given login with cif "MAKER_01" and userid "MAKER_01"
    ...
```

**Two namespaces, and they point in opposite directions.** This is the single
easiest thing to confuse, because both can name the same field:

| | `@R-...` | `@needs:...` |
|---|---|---|
| Question | what does this **verify**? | what does it **consume** to run? |
| Resolved against | `rules.yaml` | `test-data.yaml` |

"Geçersiz TCKN reddedilir" **tests** the TCKN check and **needs nothing** — an
invalid number is free to type. "Geçerli TCKN kabul edilir" needs a real one.
Testing broken data does not require sound data, which is why a blocker on
`GECERLI_TCKN` stops far fewer scenarios than it first appears to.

Anything else (`@mutating`, `@regression`) is kept and shown, not discarded: a
registry that silently drops every tag it does not understand teaches people
that tags do nothing.

| Column | Comes from |
|---|---|
| Senaryo | the **Scenario** line (the Feature line names the screen, the Scenario line names the test), the path, and the parsed step count — Background included, because Background steps run |
| Kurallar | `@R-...` tags. A tag naming a rule that no longer exists is shown struck through, never dropped: a scenario pointing at a deleted rule LOOKS bound and is not |
| Ön koşul | `@needs:...` tags. The row's word is the WORST of its needs |
| Açıklama | the Scenario's description block |

Clicking a row opens the file. The columns are CLAIMS the tags make; the
Gherkin underneath is whether the steps keep them — and no parser can check
that, which is why a row opens rather than merely expanding.

Tags cost nothing elsewhere: Cucumber ignores the ones it has no hook for, and
`ScenarioValidator` only ever looks at steps.

### `@needs:none` is not the same as no tag

```gherkin
@R-AL-010 @needs:none      → "ön koşulsuz"    — somebody checked
@R-AL-010                  → "belirtilmemiş"  — nobody wrote it down
```

An empty list means two things, and only the first is a finished scenario.
Showing the second as green would queue a run that cannot survive.

## `test-data.yaml`

```yaml
kullanicilar:                 # the group names are YOURS, not a fixed set
  MAKER_01:
    kisa: "Tam yetkili kullanıcı"     # what the Ön koşul column prints
    aciklama: "Tam yetkili test kullanıcısı"
    cozum: gizli
alici_verisi:
  GECERLI_TCKN:
    kisa: "Geçerli TCKN"
    aciklama: "Algoritma kontrolünü geçen sentetik TCKN"
    cozum: uretim
    engel: S-18
```

A child counts as an alias when it carries a **`cozum`** key; everything else
in the file is ignored. The grouping keys are free — a product invents the
categories its data actually has.

`kisa` is the NEED in a few words. The id is for machines; somebody scanning
fifty rows wants to read "Geçerli TCKN", not `GECERLI_TCKN`. It falls back to
`aciklama`, then to the id.

| `cozum` | Meaning | Ürünler shows |
|---|---|---|
| `sabit` | written in this file | hazır |
| `uretim` | computed at run time | hazır |
| `gizli` | read from `DO_NOT_COMMIT/` | hazır |
| `seed` | the test creates it first | **seed** |
| `eksik` | nobody has it yet | **eksik** |
| *(any, plus `engel:`)* | an open question forbids it | **engelli** |

**`engel` outranks `cozum`.** `GECERLI_TCKN` is `uretim` — the TCKN checksum
algorithm is public, so the value genuinely looks producible — and is still
unusable while S-18 ("does the application accept a synthetic TCKN?") is
unanswered. Reading `cozum` alone would call it ready and send a scenario to
its death.

**`engelli` and `eksik` are separate states on purpose.** `eksik` is a request
to whoever provisions test users; `engelli` is a request to an analyst, and
until the question is answered the scenario cannot even be WRITTEN correctly,
because what it should assert is unknown. Procurement and analysis are
different queues.

**Real customer numbers, IBANs and employee names never go in this file.**
`DO_NOT_COMMIT/` is that rule made concrete.

The resolver — turning `MAKER_01` into a real credential at run time — does not
exist yet. Today the file is read only to answer "can this scenario run?".

## `known-issues.md`

Bugs the team already knows about, as free text. Unlike `open-questions.md`
this one IS meant to be readable by the model: its own heading addresses it
("bu davranışlara senaryo ÜRETME").

A known issue often carries a message the screen really prints — and that is a
trap worth knowing about. B-02's record-delete text differs from the correct
list-delete text by five characters. Because the buggy text is in no rule, a
scenario asserting what the screen ACTUALLY says is refused, and the validator
helpfully suggests the nearest known message — the wrong one. Until known-issue
messages are structured (see below), write the scenario without asserting that
message.

> **Not wired yet:** this file is loaded and shown, but its messages do not
> join the validator's accepted set. Giving issues an `issues:` block in
> `rules.yaml`, with ids and messages, is the open proposal.

## `open-questions.md`

Free text, and the one file that is **never sent to the model**. Its whole
purpose is to mark where an assumption would otherwise be invented; handing the
list to something that invents plausible text defeats it. The UI shows it to
the analyst as a warning instead.

---

## What is NOT enforced

`flow.md`, `roles.md`, `fields`, `source` and `test-data.yaml` steer or inform;
nothing checks them. This mirrors `catalogs/SCHEMA.md`, where `navigation` and
`verifiable` are likewise parsed-or-ignored rather than enforced — and it
carries the same hazard: a wrong one surfaces as a strange scenario, not as a
validation error.

`test-data.yaml` is read by nothing at all today. It is the place aliases will
live (`ACC_RICH` → a real account) once a resolver exists. **Real customer
numbers, IBANs and names never go in it** — the repository already has
`DO_NOT_COMMIT/` as that rule made concrete.
