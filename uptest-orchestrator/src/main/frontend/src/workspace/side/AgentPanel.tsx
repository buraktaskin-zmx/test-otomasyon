import { useState } from "react";
import { PanelHead } from "./PanelHead";
import { explore, exploreForCatalog, saveCatalog } from "../../api/endpoints";
import { useAsyncAction } from "../../hooks/useAsyncAction";
import { catalogNameFromYaml, slug } from "../../lib/slug";
import { useToast } from "../../ui/Toast";
import { EmptyState } from "../../ui/EmptyState";
import { ExploreToolsDiagnostic } from "./ExploreToolsDiagnostic";
import type { useExploreTools } from "../../hooks/useExploreTools";
import type { useTestUsers } from "../../hooks/useTestUsers";
import { UserPicker } from "../../users/UserPicker";

type AgentEndpoint = "explore" | "catalog";

/*
  index.html:201-251 + app.js:950-1082. Both buttons share ONE busy flag
  (app.js disables btnAgentExplore and btnAgentCatalog together, because
  runAgent() is the single function both call) -- useAsyncAction's generic
  "one action at a time" guard is reused here with the endpoint choice as
  its argument, rather than two separate hook instances that could race.

  Credentials (#agentPassword, #agentSms) live ONLY in this component's
  local state -- never lifted to a context, never logged by anything this
  app controls. They do still leave the browser (ExploreController.compose
  writes them into the model's instruction text, and the orchestrator's own
  BudgetedTool.trace prints tool-call arguments to its console -- a
  pre-existing server-side issue, not something this component can fix).
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
  const [url, setUrl] = useState("https://ifctest.yapikredi.com.tr/ngc/indexNgc.do");
  /* Credentials come from a saved login. This panel typed a PASSWORD on
     every walk, which is the field a person gets wrong most and the one
     whose mistake costs a full browser launch to discover. The SMS code
     stays typed: it is single-use by definition and there is nothing to
     save. */
  const [loginId, setLoginId] = useState("");
  const chosen = loginId ? users.byId(loginId) : null;
  const cif = chosen?.cif ?? "";
  const userId = chosen?.userid ?? "";
  const password = chosen?.password ?? "";
  const [smsCode, setSmsCode] = useState("");
  const [mainMenu, setMainMenu] = useState("");
  const [instruction, setInstruction] = useState("");

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

    const request = { text: instruction, url, cif, userId, password, smsCode, mainMenu };

    // Kept as two separate branches (not merged into one `result` variable
    // via a ternary) ON PURPOSE: explore() and exploreForCatalog() return
    // different success body shapes, and TypeScript cannot narrow a union
    // produced by a ternary against a condition checked afterwards.
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
      // Only fills EMPTY boxes -- the analyst can always overwrite either,
      // and only they know whether this is a new screen or a better walk
      // of one already stored (app.js:703-716).
      if (guess.app && !saveApp.trim()) setSaveApp(guess.app);
      if (guess.flow && !saveFlow.trim()) setSaveFlow(guess.flow);
    } else {
      const result = await explore(request);
      if (!result.ok) {
        toast("Ekran keşfi başarısız: " + result.error, true);
        return;
      }
      setOutput(result.body.answer);
      setProblems([]);
      setIsCatalog(false);
    }
  });

  const busy = run.busy;

  return (
    <div id="side-agent" className={"side-panel" + (active ? " is-active" : "")}>
      <PanelHead title="Ekran Keşfi" />
      <div className="suggest-form">
        <label className="suggest-label" htmlFor="agentUrl">
          Adres
        </label>
        <input
          id="agentUrl"
          className="search"
          type="text"
          autoComplete="off"
          value={url}
          onChange={(e) => setUrl(e.target.value)}
        />
        <UserPicker
          idPrefix="agent"
          users={users}
          value={loginId}
          onChange={setLoginId}
          needsUserid={false}
          disabled={busy}
        />
        <label className="suggest-label" htmlFor="agentSms">
          Akıllı SMS kodu <span className="suggest-optional">(opsiyonel)</span>
        </label>
        <input
          id="agentSms"
          className="search"
          type="text"
          autoComplete="off"
          placeholder="SMS ekranı gelmiyorsa boş bırakın"
          value={smsCode}
          onChange={(e) => setSmsCode(e.target.value)}
        />
        <label className="suggest-label" htmlFor="agentMainMenu">
          Ana menü <span className="suggest-optional">(opsiyonel)</span>
        </label>
        <input
          id="agentMainMenu"
          className="search"
          type="text"
          autoComplete="off"
          placeholder="Örn: NAKİT YÖNETİMİ"
          value={mainMenu}
          onChange={(e) => setMainMenu(e.target.value)}
        />
        <label className="suggest-label" htmlFor="agentInstruction">
          Ne yapsın?
        </label>
        <textarea
          id="agentInstruction"
          className="search suggest-intent"
          rows={10}
          placeholder={
            "Girişi yukarıdaki alanlar hallediyor — buraya sadece giriş SONRASINI yazın.\n" +
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
            Serbest Gözlem
          </button>
          <button
            id="btnAgentCatalog"
            className="btn btn-primary"
            type="button"
            disabled={busy}
            onClick={() => run.run("catalog")}
          >
            YAML Katalog Çıkar
          </button>
        </div>
        <p className="suggest-hint">
          Gerçek bir tarayıcı sürülür ve her adımdan önce sayfa okunur. <b>Serbest Gözlem</b> düzyazı
          bir cevap verir; <b>YAML Katalog Çıkar</b> aynı gezintinin sonunda <code>SCHEMA.md</code>
          biçiminde ekranın YAML kataloğunu döndürür. Taslak, emin olmadığı satırları <code># emin degil</code> ile işaretler —
          özellikle <code>type</code> alanını ölçülmüş bir taramayla karşılaştırın. Onay/Devam gibi
          kayıt oluşturan düğmelere basmamasını açıkça yazın.
        </p>
      </div>

      <div id="agentStatus" className="suggest-status">
        {busy ? (
          <div className="s-line">tarayıcı sürülüyor, bu birkaç dakika sürebilir…</div>
        ) : problems === null ? null : problems.length > 0 ? (
          <>
            <div className="s-line">Taslakta {problems.length} sorun var:</div>
            {problems.map((p, i) => (
              <div className="s-line" key={i}>
                • {p}
              </div>
            ))}
          </>
        ) : (
          <div className="s-line">Tamamlandı — mekanik kontrollerden temiz geçti.</div>
        )}
      </div>

      <textarea
        id="agentOutput"
        className="search discover-output"
        spellCheck={false}
        rows={14}
        placeholder="Gözlem ya da YAML burada görünecek."
        value={output}
        onChange={(e) => setOutput(e.target.value)}
      />

      {isCatalog && output ? (
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

      {tools.loaded && tools.count === 0 ? (
        <EmptyState title="MCP tarayıcı sağlamıyor">
          Ajan araçları yayınlanmıyor — <code>uptest.explore.enabled</code> açık ama MCP sunucusu
          hiç araç döndürmedi.
        </EmptyState>
      ) : null}
      <ExploreToolsDiagnostic loaded={tools.loaded} count={tools.count} tools={tools.tools} />
    </div>
  );
}
