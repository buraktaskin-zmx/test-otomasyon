# Screen Catalog Schema

A **catalog** is a curated, structured description of one flow's screens —
exactly the labels, dropdown options, account numbers, buttons, and messages an
LLM needs to author a valid `.feature` for that flow, and nothing more.

- **One file per flow**, YAML, under `catalogs/<app>/<flow>.yaml`
  (`<app>` = `ngc` or `ngi`). Name to match the feature file where practical.
- **Curated, not raw.** Include only what the generator needs. Never paste DOM.
  This keeps catalogs token-cheap.
- **Fixed shape.** Hand-written and auto-generated catalogs MUST produce the
  same keys, so downstream tooling (the LLM prompt, the future Playwright
  discovery generator) can rely on it. This file is that contract.

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
  sub_menu: "Firma Fatura Sorgulama"
  entry_button: "Ödeme Talep Et"   # optional: a button that opens the form
```

## `fields`

A list, in the order they appear on screen. Each entry:

| Field key | Required | Meaning |
|-----------|----------|---------|
| `label` | yes | Exact on-screen label text (Turkish chars, spacing, punctuation). |
| `type` | yes | One of: `text`, `amount`, `date`, `dropdown`, `account_picker`. |
| `options` | for `dropdown` | List of exact selectable option texts. |
| `accounts` | for `account_picker` | List of full account numbers (spacing as shown). |
| `verifiable` | no | `true` if this field appears on a confirm/summary screen and can be asserted. Defaults to readable+writable per its type. |

`type` maps directly to vocabulary verbs:

| `type` | enter with | verify with | select with |
|--------|-----------|-------------|-------------|
| `text` | `enter` | `verify the {} field equals {}` | — |
| `date` | `enter` | `verify the {} field equals {}` | — |
| `amount` | `enter amount` | `verify the {} has amount {}` | — |
| `dropdown` | — | — | `select {} from {}` |
| `account_picker` | — | — | `select account {} from {}` / `select account at index {} from {}` |

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
