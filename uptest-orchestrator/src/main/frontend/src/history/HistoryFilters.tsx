export type HistoryFilter = "ALL" | "RUNNING" | "DONE" | "FAILED";

const CHIPS: ReadonlyArray<{ id: HistoryFilter; label: string }> = [
  { id: "ALL", label: "Tümü" },
  { id: "RUNNING", label: "Çalışıyor" },
  { id: "DONE", label: "Başarılı" },
  { id: "FAILED", label: "Başarısız" },
];

/*
  Filtering stays on the client, as it has been: RunController exposes no
  ?status= parameter and has no index to support one, so asking the server
  would be slower and would need a backend change the UI does not need.
*/
export function HistoryFilters({
  filter,
  onChange,
}: {
  filter: HistoryFilter;
  onChange: (filter: HistoryFilter) => void;
}) {
  return (
    <div className="filters" id="historyFilters">
      {CHIPS.map((chip) => (
        <button
          key={chip.id}
          className={"chip" + (filter === chip.id ? " is-active" : "")}
          data-filter={chip.id}
          type="button"
          aria-pressed={filter === chip.id}
          onClick={() => onChange(chip.id)}
        >
          {chip.label}
        </button>
      ))}
    </div>
  );
}
