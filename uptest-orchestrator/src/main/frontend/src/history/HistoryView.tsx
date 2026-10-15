import { useCallback, useEffect, useState } from "react";
import { getRuns } from "../api/endpoints";
import { useAsyncAction } from "../hooks/useAsyncAction";
import { EmptyState } from "../ui/EmptyState";
import { useToast } from "../ui/Toast";
import { HistoryFilters } from "./HistoryFilters";
import type { HistoryFilter } from "./HistoryFilters";
import { HistoryTable } from "./HistoryTable";
import type { RunDetail } from "../api/types";

export function HistoryView({ active }: { active: boolean }) {
  const [runs, setRuns] = useState<RunDetail[] | null>(null);
  const [filter, setFilter] = useState<HistoryFilter>("ALL");
  const toast = useToast();

  const load = useCallback(async () => {
    const result = await getRuns();
    if (!result.ok) {
      toast("Geçmiş alınamadı: " + result.error, true);
      return;
    }
    setRuns(result.body);
  }, [toast]);

  const refresh = useAsyncAction(load);

  /* Reloaded on entry rather than kept warm: a run that finished while the
     analyst was in the workspace must be here when they switch over. */
  useEffect(() => {
    if (active) void load();
  }, [active, load]);

  const visible = (runs ?? []).filter(
    (run) => filter === "ALL" || run.status === filter,
  );

  return (
    <section id="view-history" className={"view" + (active ? " is-active" : "")}>
      <div className="pane pane-history">
        <div className="pane-head">
          <h2 className="pane-title">Koşu Geçmişi</h2>
          <div className="pane-actions">
            <HistoryFilters filter={filter} onChange={setFilter} />
            <button
              id="btnRefreshHistory"
              className="btn btn-ghost"
              type="button"
              disabled={refresh.busy}
              onClick={() => refresh.run()}
            >
              Yenile
            </button>
          </div>
        </div>
        <div id="historyBody" className="history-body">
          {visible.length ? (
            <HistoryTable runs={visible} />
          ) : (
            <EmptyState title="Kayıt yok">
              Bir senaryo çalıştırdığınızda burada listelenecek.
            </EmptyState>
          )}
        </div>
      </div>
    </section>
  );
}
