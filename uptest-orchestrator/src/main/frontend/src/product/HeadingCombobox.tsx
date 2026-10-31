import { useId, useState } from "react";
import { foldTr, matchesTr } from "../lib/headings";
import type { ScenarioHeading } from "../api/types";

type Option = { kind: "heading"; name: string } | { kind: "add"; name: string };

/*
  The new-scenario form's "Başlık": type to filter the screen's headings,
  ↑/↓ to walk them, Enter to take one, Esc to close the list. A name that
  matches none is offered as "“…” olarak ekle" -- the heading itself is made
  on save, by the server, which also folds a name it already has into that
  heading. Left empty, the scenario is saved başlıksız.
*/
export function HeadingCombobox({
  value,
  headings,
  onChange,
}: {
  value: string;
  /** The screen's own headings. */
  headings: ScenarioHeading[];
  onChange: (value: string) => void;
}) {
  const id = useId();
  const listId = id + "-list";
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);

  const typed = value.trim();
  const exact = headings.some((heading) => foldTr(heading.name) === foldTr(typed));
  const options: Option[] = [
    ...headings
      .filter((heading) => matchesTr(heading.name, value))
      .map((heading): Option => ({ kind: "heading", name: heading.name })),
    ...(typed && !exact ? [{ kind: "add", name: typed } as Option] : []),
  ];
  const shown = open && options.length > 0;
  const current = shown && active >= 0 && active < options.length ? active : -1;

  const choose = (option: Option) => {
    onChange(option.name);
    setOpen(false);
    setActive(-1);
  };

  return (
    <div className="sd-doc-block">
      <label className="sd-doc-label" htmlFor={id}>Başlık</label>
      <div className="sc-combo">
        <input
          id={id}
          className="search"
          role="combobox"
          autoComplete="off"
          aria-autocomplete="list"
          aria-expanded={shown}
          aria-controls={listId}
          aria-activedescendant={current >= 0 ? listId + "-" + current : undefined}
          value={value}
          placeholder="Başlık seçin ya da yeni yazın"
          onFocus={() => setOpen(true)}
          onBlur={() => setOpen(false)}
          onChange={(event) => {
            onChange(event.target.value);
            setOpen(true);
            setActive(-1);
          }}
          onKeyDown={(event) => {
            if (event.key === "ArrowDown" || event.key === "ArrowUp") {
              event.preventDefault();
              if (!open) {
                setOpen(true);
                return;
              }
              if (options.length === 0) return;
              const step = event.key === "ArrowDown" ? 1 : -1;
              setActive((current + step + options.length) % options.length);
            } else if (event.key === "Enter") {
              const picked = current >= 0 ? options[current] : undefined;
              if (picked) {
                event.preventDefault();
                choose(picked);
              } else if (shown) {
                event.preventDefault();
                setOpen(false);
              }
            } else if (event.key === "Escape" && shown) {
              event.preventDefault();
              setOpen(false);
              setActive(-1);
            }
          }}
        />
        {shown ? (
          <ul id={listId} className="sc-combo-list" role="listbox" aria-label="Başlıklar">
            {options.map((option, index) => (
              <li
                key={option.kind + ":" + option.name}
                id={listId + "-" + index}
                role="option"
                aria-selected={index === current}
                className={"sc-combo-option" + (index === current ? " is-active" : "")
                  + (option.kind === "add" ? " is-add" : "")}
                // mousedown, not click: the input's blur would close the list first.
                onMouseDown={(event) => {
                  event.preventDefault();
                  choose(option);
                }}
                onMouseEnter={() => setActive(index)}
              >
                {option.kind === "add" ? "“" + option.name + "” olarak ekle" : option.name}
              </li>
            ))}
          </ul>
        ) : null}
      </div>
    </div>
  );
}
