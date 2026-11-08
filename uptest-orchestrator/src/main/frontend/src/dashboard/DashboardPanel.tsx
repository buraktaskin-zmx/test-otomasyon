import { useCallback, useEffect, useState } from "react";
import { getDashboard, getScenarioHistory, getScreenDashboard } from "../api/endpoints";
import type {
  DashboardHistory, DashboardOverview, DashboardPeriod, DashboardScreenDetail,
} from "../api/types";
import { PanelHead } from "../workspace/side/PanelHead";
import type { useTestUsers } from "../hooks/useTestUsers";
import { Overview } from "./Overview";
import { ScreenDetail } from "./ScreenDetail";
import { ScenarioHistory } from "./ScenarioHistory";

/** Which screen the detail page shows. */
interface ScreenRef {
  app: string;
  product: string;
  flow: string;
  name: string;
}

/** Which scenario the history page shows. */
interface ScenarioRef {
  app: string;
  product: string;
  path: string;
  title: string;
}

/** The failed scenarios of one screen, handed to Kayıtlı Senaryolar to run. */
export interface RerunRequest {
  app: string;
  product: string;
  flow: string;
  paths: string[];
}

const PERIODS: Array<[DashboardPeriod, string]> = [["24h", "24 saat"], ["7d", "7 gün"], ["30d", "30 gün"]];

/*
  Dashboard: Genel Bakış → a screen's detail → a scenario's history.

  Read when the panel is shown and on Yenile, never on a timer: these are
  counts over days, and a page that reloads itself every few seconds while
  someone reads a table only moves the row they were reading.
*/
export function DashboardPanel({
  active,
  users,
  onRerun,
}: {
  active: boolean;
  /** The saved logins: what the CIF filter offers, before any run has one. */
  users: ReturnType<typeof useTestUsers>;
  onRerun: (request: RerunRequest) => void;
}) {
  const [period, setPeriod] = useState<DashboardPeriod>("7d");
  const [cif, setCif] = useState<string | null>(null);
  /* CIFs seen in runs, from an UNFILTERED overview so picking one does not
     shrink the list to itself. They are MERGED with the saved logins below:
     on their own they would leave the filter empty until runs start recording
     a CIF, and a filter that offers nothing looks broken. */
  const [ranCifs, setRanCifs] = useState<Array<{ cif: string; name: string | null }>>([]);
  const [screen, setScreen] = useState<ScreenRef | null>(null);
  const [scenario, setScenario] = useState<ScenarioRef | null>(null);
  const [overview, setOverview] = useState<DashboardOverview | null>(null);
  const [detail, setDetail] = useState<DashboardScreenDetail | null>(null);
  const [history, setHistory] = useState<DashboardHistory | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    if (scenario) {
      const result = await getScenarioHistory(scenario.app, scenario.product, scenario.path);
      if (result.ok) setHistory(result.body);
      else setError(result.error);
    } else if (screen) {
      const result = await getScreenDashboard(screen.app, screen.product, screen.flow, period, cif);
      if (result.ok) setDetail(result.body);
      else setError(result.error);
    } else {
      const result = await getDashboard(period, cif);
      if (result.ok) {
        setOverview(result.body);
        if (!cif) setRanCifs(result.body.cifs.map((row) => ({ cif: row.cif, name: row.name })));
      } else {
        setError(result.error);
      }
    }
    setLoading(false);
  }, [period, cif, screen, scenario]);

  useEffect(() => {
    if (active) void load();
  }, [active, load]);

  const back = () => {
    if (scenario) {
      setScenario(null);
      setHistory(null);
    } else {
      setScreen(null);
      setDetail(null);
    }
  };

  const openScenario = (ref: ScenarioRef) => {
    setHistory(null);
    setScenario(ref);
  };

  /* Saved logins first, in the order Test Kullanıcıları lists them; then any
     CIF a run used that is no longer saved, so old runs stay filterable. */
  const cifOptions = [
    ...users.users.map((user) => ({ cif: user.cif, name: user.name })),
    ...ranCifs.filter((row) => !users.users.some((user) => user.cif === row.cif)),
  ].filter((row, index, all) => all.findIndex((other) => other.cif === row.cif) === index);

  const title = scenario ? scenario.title : screen ? screen.name : "Dashboard";

  return (
    <div id="side-dashboard" className={"side-panel dashboard-panel" + (active ? " is-active" : "")}>
      <PanelHead title={title}>
        {screen || scenario ? (
          <button type="button" className="btn btn-ghost" onClick={back}>← Geri</button>
        ) : null}
        {scenario ? null : (
          <>
            <span className="db-periods" role="group" aria-label="Dönem">
              {PERIODS.map(([id, word]) => (
                <button
                  key={id}
                  type="button"
                  className={"btn btn-ghost db-chip" + (period === id ? " is-active" : "")}
                  aria-pressed={period === id}
                  onClick={() => setPeriod(id)}
                >
                  {word}
                </button>
              ))}
            </span>
            <label className="db-cif">
              <span>CIF</span>
              <select
                className="search"
                value={cif ?? ""}
                onChange={(event) => setCif(event.target.value || null)}
              >
                <option value="">tümü</option>
                {cifOptions.map((row) => (
                  <option key={row.cif} value={row.cif}>
                    {row.cif}{row.name ? " — " + row.name : ""}
                  </option>
                ))}
                {cif && !cifOptions.some((row) => row.cif === cif) ? <option value={cif}>{cif}</option> : null}
              </select>
            </label>
          </>
        )}
        <button type="button" className="btn btn-ghost" disabled={loading} onClick={() => void load()}>
          {loading ? "Okunuyor…" : "Yenile"}
        </button>
      </PanelHead>

      <div className="db-body">
        {error ? <p className="product-warn">Dashboard okunamadı: {error}</p> : null}
        {scenario ? (
          history ? <ScenarioHistory data={history} /> : !error ? <p className="db-empty">Okunuyor…</p> : null
        ) : screen ? (
          detail ? (
            <ScreenDetail
              data={detail}
              onRerunFailed={(paths) => onRerun({ app: detail.app, product: detail.product, flow: detail.flow, paths })}
              onOpenScenario={(path, scenarioTitle) =>
                openScenario({ app: detail.app, product: detail.product, path, title: scenarioTitle })}
            />
          ) : !error ? <p className="db-empty">Okunuyor…</p> : null
        ) : overview ? (
          <Overview
            data={overview}
            onOpenScreen={(s) => { setDetail(null); setScreen({ app: s.app, product: s.product, flow: s.flow, name: s.name }); }}
            onPickCif={(picked) => setCif(picked)}
            onOpenScenario={(app, product, path, scenarioTitle) =>
              openScenario({ app, product, path, title: scenarioTitle })}
          />
        ) : !error ? <p className="db-empty">Okunuyor…</p> : null}
      </div>
    </div>
  );
}
