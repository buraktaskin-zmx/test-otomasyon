import { useState } from "react";
import { explore, exploreForCatalog, saveCatalog } from "../../api/endpoints";
import { useAsyncAction } from "../../hooks/useAsyncAction";
import { catalogNameFromYaml, slug } from "../../lib/slug";
import { useToast } from "../../ui/Toast";
import { EmptyState } from "../../ui/EmptyState";
import { ExploreToolsDiagnostic } from "./ExploreToolsDiagnostic";
import type { useExploreTools } from "../../hooks/useExploreTools";
import type { useTestUsers } from "../../hooks/useTestUsers";
import { UserPicker } from "../../users/UserPicker";
import { LOGIN_URL, TEST_SMS_CODE } from "./ProductPanel";

type AgentEndpoint = "explore" | "catalog";

/*
  Ekran Keşfi -- form left, result right, in the wide panel.

  Both buttons share ONE busy flag: useAsyncAction's "one action at a time"
  guard takes the endpoint as its argument, rather than two hook instances
  that could drive the browser twice.

  Credentials live ONLY in this component's local state -- never lifted to a
  context, never logged by anything this app controls. They do still leave
  the browser (ExploreController.compose writes them into the model's
  instruction text, and BudgetedTool.trace prints tool-call arguments to the
  orchestrator's console -- a pre-existing server-side issue).
*/
export function AgentPanel({
  active,
  tools,
  users,
  onCatalogSaved,
}: {
  active: boolean;
  tools: ReturnType<typeof useExploreTools>;
  users: ReturnType<typeof useTestUsers>;
  onCatalogSaved: (name: string) => void;
}) {
  /* The address comes from the application, the login from a saved user and
     the SMS code is the test environment's fixed one -- so the only thing
     left to type is what to do after logging in. */
  const [app, setApp] = useState("ngc");
  const [loginId, setLoginId] = useState("");
  const chosen = loginId ? users.byId(loginId) : null;
  const [instruction, setInstruction] = useState("");

  const [pending, setPending] = useState<AgentEndpoint | null>(null);
  const [output, setOutput] = useState("");
  const [problems, setProblems] = useState<string[] | null>(null);
  const [isCatalog, setIsCatalog] = useState(false);
  const [saveApp, setSaveApp] = useState("");
  const [saveFlow, setSaveFlow] = useState("");

  const toast = useToast();
  const save = useAsyncAction(async () => {
    if (!saveApp.trim() || !saveFlow.trim()) {
      toast("Uygulama ve akış adı gerekli", true);
      return;
    }
    if (!output.trim()) {
      toast("Kaydedilecek bir katalog yok", true);
      return;
    }
    const result = await saveCatalog(saveApp.trim(), saveFlow.trim(), output);
    if (!result.ok) {
      toast("Kaydedilemedi: " + result.error, true);
      return;
    }
    toast(result.body.catalog + " kaydedildi");
    onCatalogSaved(result.body.catalog);
  });

  const run = useAsyncAction(async (endpoint: AgentEndpoint) => {
    if (!instruction.trim()) {
      toast("Ne yapılacağını yazın", true);
      return;
    }
    setOutput("");
    setProblems(null);
    setPending(endpoint);

    const request = {
      text: instruction,
      url: LOGIN_URL[app],
      cif: chosen?.cif ?? "",
      userId: chosen?.userid ?? "",
      password: chosen?.password ?? "",
      smsCode: TEST_SMS_CODE,
    };

    try {
      // Two branches ON PURPOSE: explore() and exploreForCatalog() return
      // different success bodies, and a ternary-built union cannot be
      // narrowed against a condition checked afterwards.
      if (endpoint === "catalog") {
        const result = await exploreForCatalog(request);
        if (!result.ok) {
          toast("Ekran keşfi başarısız: " + result.error, true);
          return;
        }
        setOutput(result.body.catalog);
        setProblems(result.body.problems);
        setIsCatalog(true);
        const guess = catalogNameFromYaml(result.body.catalog);
        // Only fills EMPTY boxes -- only the analyst knows whether this is a
        // new screen or a better walk of one already stored.
        if (guess.app && !saveApp.trim()) setSaveApp(guess.app);
        if (guess.flow && !saveFlow.trim()) setSaveFlow(guess.flow);
      } else {
        const result = await explore(request);
        if (!result.ok) {
          toast("Ekran keşfi başarısız: " + result.error, true);
          return;
        }
        setOutput(result.body.answer);
        setProblems(null);
        setIsCatalog(false);
      }
    } finally {
      setPending(null);
    }
  });

  const busy = run.busy;

  return (
    <div id="side-agent" className={"side-panel agent-panel" + (active ? " is-active" : "")}>
      <div className="agent-body">
        <section className="agent-form">
          <label className="suggest-label" htmlFor="agentApp">Uygulama</label>
          <select
            id="agentApp"
            className="search"
            value={app}
            disabled={busy}
            onChange={(e) => setApp(e.target.value)}
          >
            {Object.keys(LOGIN_URL).map((key) => (
              <option key={key} value={key}>{key.toUpperCase()} ({key})</option>
            ))}
          </select>

          <UserPicker
            idPrefix="agent"
            users={users}
            value={loginId}
            onChange={setLoginId}
            needsUserid={app === "ngc"}
            disabled={busy}
          />

          <label className="suggest-label" htmlFor="agentInstruction">Ne yapsın?</label>
          <textarea
            id="agentInstruction"
            className="search suggest-intent"
            rows={8}
            placeholder={
              "Giriş otomatik yapılır — buraya sadece giriş SONRASINI yazın.\n" +
              "Örn: Online DBS İşlemleri'ne git, FORD DBS ODEMELERI satırını seç, Fatura Oluştur'a " +
              "tıkla ve formdaki alanları incele. Devam/Onay'a basma."
            }
            value={instruction}
            onChange={(e) => setInstruction(e.target.value)}
          />

          <div className="agent-actions">
            <button
              id="btnAgentExplore"
              className="btn btn-ghost"
              type="button"
              disabled={busy}
              onClick={() => run.run("explore")}
            >
              {pending === "explore" ? "Çalışıyor…" : "Serbest Gözlem"}
            </button>
            <button
              id="btnAgentCatalog"
              className="btn btn-primary"
              type="button"
              disabled={busy}
              onClick={() => run.run("catalog")}
            >
              {pending === "catalog" ? "Çalışıyor…" : "YAML Katalog Çıkar"}
            </button>
          </div>

          {tools.loaded && tools.count === 0 ? (
            <EmptyState title="MCP tarayıcı sağlamıyor">
              Ajan araçları yayınlanmıyor — <code>uptest.explore.enabled</code> açık ama MCP
              sunucusu hiç araç döndürmedi.
            </EmptyState>
          ) : null}
          <ExploreToolsDiagnostic loaded={tools.loaded} count={tools.count} tools={tools.tools} />
        </section>

        <section className="agent-result">
          <div className="agent-result-head">
            <h3 className="users-card-title">EKRAN KEŞFİ</h3>
            <p className="agent-result-sub">
              Tarayıcıyı gezip ekranı okur ve katalog taslağı üretir. Kaydetmeden önce
              sorunları gözden geçirin.
            </p>
          </div>

          {output ? (
            <>
              {problems && problems.length > 0 ? (
                <div className="suggest-status">
                  <div className="s-line">Taslakta {problems.length} sorun var:</div>
                  {problems.map((p, i) => (
                    <div className="s-line" key={i}>• {p}</div>
                  ))}
                </div>
              ) : null}

              <textarea
                id="agentOutput"
                className="search discover-output agent-output"
                spellCheck={false}
                value={output}
                onChange={(e) => setOutput(e.target.value)}
              />

              {isCatalog ? (
                <div id="agentSaveRow" className="agent-save">
                  <div className="agent-save-names">
                    <input
                      id="agentSaveApp"
                      className="search"
                      type="text"
                      autoComplete="off"
                      placeholder="ngc"
                      aria-label="Uygulama"
                      value={saveApp}
                      onChange={(e) => setSaveApp(e.target.value)}
                    />
                    <span className="agent-save-sep">/</span>
                    <input
                      id="agentSaveFlow"
                      className="search"
                      type="text"
                      autoComplete="off"
                      placeholder="banko-odeme-talebi"
                      aria-label="Akış"
                      value={saveFlow}
                      onChange={(e) => setSaveFlow(slug(e.target.value))}
                    />
                    <span className="agent-save-sep">.yaml</span>
                  </div>
                  <button
                    id="btnAgentSave"
                    className="btn btn-primary"
                    type="button"
                    disabled={save.busy}
                    onClick={() => save.run()}
                  >
                    Kataloğu Kaydet
                  </button>
                </div>
              ) : null}
            </>
          ) : (
            <p className="agent-empty">
              Keşif bitince YAML katalog ya da gözlem raporu burada görünür.
            </p>
          )}
        </section>
      </div>
    </div>
  );
}
