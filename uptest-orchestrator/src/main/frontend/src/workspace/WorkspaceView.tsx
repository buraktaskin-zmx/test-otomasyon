import { useCallback, useEffect, useRef, useState } from "react";
import { EditorPane } from "./editor/EditorPane";
import { EditorPaneHead } from "./editor/EditorPaneHead";
import { MultiBar } from "./multi/MultiBar";
import { MultiList } from "./multi/MultiList";
import type { Draft } from "./multi/draft";
import { AgentPanel } from "./side/AgentPanel";
import { ProductPanel } from "./side/ProductPanel";
import { UsersPanel } from "./side/UsersPanel";
import { ChatView } from "../chat/ChatView";
import { SideRail } from "./side/SidePane";
import type { SideTab } from "./side/SidePane";
import { SuggestPanel } from "./side/SuggestPanel";
import { TimelinePanel } from "./side/TimelinePanel";
import type { RunTab } from "./side/TimelinePanel";
import { Badge } from "../ui/Badge";
import { useCatalogs } from "../hooks/useCatalogs";
import { useEditorOperations } from "../hooks/useEditorOperations";
import { useExploreTools } from "../hooks/useExploreTools";
import { useOrigin } from "../hooks/useOrigin";
import { useProducts } from "../hooks/useProducts";
import { useTestUsers } from "../hooks/useTestUsers";
import { useRunArtifacts } from "../hooks/useRunArtifacts";
import { useRunPoll } from "../hooks/useRunPoll";
import { useValidation } from "../hooks/useValidation";
import { useToast } from "../ui/Toast";
import type { Origin, RunLogin, ValidationResult } from "../api/types";
import { splitTags } from "../lib/scenarioProse";
import { loginStepFor, withLogin } from "../lib/loginLine";
import type { useVocabulary } from "../hooks/useVocabulary";

/*
  Owns the editor text, origin pinning, validation, the current run, and the
  Çoklu Üret draft batch -- every piece of state more than one side panel
  needs to reach into. See the plan's "Durum mimarisi" section.

  Two parallel modes, never a third (app.js:44-64's state.drafts/
  activeDraft comment, ported literally): `listMode` (a batch exists, no
  draft open -- editor/validation hidden, MultiList shown) and everything
  else (an ordinary single scenario, OR a draft opened from the list, which
  looks the same as a single scenario plus the multiBar strip).
*/
export function WorkspaceView({
  active,
  vocabulary,
}: {
  active: boolean;
  vocabulary: ReturnType<typeof useVocabulary>;
}) {
  // Starts empty: the editor shows where results will appear until there is one.
  const [feature, setFeature] = useState("");
  const [sideTab, setSideTab] = useState<SideTab>("timeline");
  /* Which half of the run panel shows. Owned here rather than inside the
     panel because the two forced switches below have to set it: a panel
     cannot switch itself on a decision taken somewhere else.

     It opens on the TIMELINE. The rail item that reveals this panel reads
     "Kayıtlı adımlarla çalıştır", so landing on the dictionary answers a
     question nobody asked -- and the dictionary is still one click away,
     which is the whole point of the two halves. */
  const [runTab, setRunTab] = useState<RunTab>("timeline");
  /* The panel can be closed outright to give the editor the full width --
     a long scenario is 20+ lines of quoted Turkish and the 40% column was
     costing the half of it that matters. Closing hides the panel, not the
     rail: the rail is how you get it back. */
  const [panelOpen, setPanelOpen] = useState(true);

  /* Selecting a panel always shows it. Both forced switches below (the run
     starting, and a 422 refusal) exist to put something in front of the
     analyst; with the panel closed they would switch a tab nobody can see
     and the feedback would be lost. */
  const showPanel = useCallback((tab: SideTab) => {
    setSideTab(tab);
    setPanelOpen(true);
  }, []);
  const toast = useToast();
  const origin = useOrigin();
  const catalogs = useCatalogs();
  const products = useProducts();
  const users = useTestUsers();
  const tools = useExploreTools();
  const runPoll = useRunPoll();
  const artifacts = useRunArtifacts(runPoll.run?.runId ?? null, runPoll.run?.status ?? null);

  /* The live fallback screen/intent AI Üret or Çoklu Üret currently shows --
     read only when nothing has been pinned yet. See lib/originScreen.ts. */
  const [fallbackOrigin, setFallbackOrigin] = useState<Origin>({
    app: "",
    flow: "",
    intent: "",
    product: "",
  });
  const resolvedOrigin = origin.resolve(fallbackOrigin);

  /* ------------------------------ Çoklu Üret ------------------------------ */

  const [drafts, setDrafts] = useState<Draft[]>([]);
  const [activeDraft, setActiveDraft] = useState<number | null>(null);
  const activeDraftRef = useRef<number | null>(null);
  const draftsRef = useRef<Draft[]>(drafts);
  activeDraftRef.current = activeDraft;
  draftsRef.current = drafts;

  const listMode = activeDraft === null && drafts.length > 0;

  /* Written on every keystroke, not only on navigating back to the list --
     app.js:1121-1125: a side-panel switch, a regeneration, or a closed tab
     can all leave the editor without pressing "← Tümü", and an edit lost
     that way is lost invisibly. */
  const saveActiveDraftFeature = useCallback((text: string) => {
    const index = activeDraftRef.current;
    if (index === null) return;
    setDrafts((prev) => {
      const current = prev[index];
      if (!current) return prev;
      const next = prev.slice();
      next[index] = { ...current, feature: text };
      return next;
    });
  }, []);

  /* Keeps the active draft's own badge in sync as the analyst fixes and
     re-validates it -- app.js:276-282. */
  const handleValidationResult = useCallback((result: ValidationResult) => {
    const index = activeDraftRef.current;
    if (index === null) return;
    setDrafts((prev) => {
      const current = prev[index];
      if (!current) return prev;
      const next = prev.slice();
      next[index] = { ...current, validation: result };
      return next;
    });
  }, []);

  const validation = useValidation(feature, resolvedOrigin, handleValidationResult);

  const handleEdited = useCallback(
    (next: string) => {
      saveActiveDraftFeature(next);
      validation.scheduleValidate();
    },
    [saveActiveDraftFeature, validation],
  );

  const { textareaRef, gutterRef, highlightRef, syncScroll, goToLine, replaceLine, insertAtCursor } =
    useEditorOperations(feature, setFeature, handleEdited);

  const handleChange = useCallback(
    (value: string) => {
      setFeature(value);
      saveActiveDraftFeature(value);
      validation.scheduleValidate();
    },
    [saveActiveDraftFeature, validation],
  );

  const openDraft = useCallback(
    (index: number) => {
      const draft = draftsRef.current[index];
      if (!draft) return;
      activeDraftRef.current = index;
      setActiveDraft(index);
      setLinked(null);
      setFileEditing(false);
      origin.pinOrigin(draft.origin);
      setFeature(draft.feature);
      // The verdict already came back with the draft -- rendered from it
      // rather than re-validated, which would cost a round trip to show
      // the same thing (app.js:892-896).
      validation.applyResult(draft.validation);
    },
    [origin, validation],
  );

  const backToList = useCallback(() => {
    saveActiveDraftFeature(feature);
    activeDraftRef.current = null;
    setActiveDraft(null);
  }, [feature, saveActiveDraftFeature]);

  /* A single generation replaces the batch outright -- one editor, one
     scenario, no list to go back to (app.js:914-922). */
  const clearBatch = useCallback(() => {
    setDrafts([]);
    activeDraftRef.current = null;
    setActiveDraft(null);
  }, []);

  /* The planner's notes (e.g. "no max_length in the catalog for …") are not
     shown: the analyst asked not to have them above the list. */
  const handleBatchGenerated = useCallback((newDrafts: Draft[]) => {
    setDrafts(newDrafts);
    activeDraftRef.current = null;
    setActiveDraft(null);
  }, []);

  /* --------------------------- catalog save sync --------------------------- */

  const [lastSavedCatalog, setLastSavedCatalog] = useState<string | null>(null);
  const handleCatalogSaved = useCallback(
    (name: string) => {
      setLastSavedCatalog(name);
      void catalogs.reload();
    },
    [catalogs],
  );

  /* app.js:1210 validates once right after the vocabulary loads at startup
     -- not via the debounce path, which only fires on user input. */
  useEffect(() => {
    void validation.validateNow();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /* ----------------------- a registered file ("Git") ---------------------- */

  /* Set while the editor holds a scenario opened from Kayıtlı Senaryolar.
     `saved` is the text as on disk (tag lines removed, see splitTags); the
     editor returns to it when the Çalıştır panel is left. Nothing on this
     screen writes the file. Anything else that replaces the editor's text
     clears this. */
  const [linked, setLinked] = useState<{
    app: string;
    product: string;
    path: string;
    tags: string;
    saved: string;
    login?: RunLogin;
  } | null>(null);
  const [fileEditing, setFileEditing] = useState(false);

  const handleGenerated = useCallback(
    (generatedFeature: string, generatedOrigin: Origin) => {
      setLinked(null);
      setFileEditing(false);
      clearBatch();
      origin.pinOrigin(generatedOrigin);
      setFeature(generatedFeature);
      // Both values passed explicitly: neither setFeature nor pinOrigin has
      // been committed yet, so validateNow's refs still hold the previous
      // scenario and the previous screen. See useValidation.validateNow.
      void validation.validateNow({ feature: generatedFeature, origin: generatedOrigin });
    },
    [clearBatch, origin, validation],
  );

  /* The one run path, taking what to run explicitly so the chat can use it
     without building a second one. Everything it does on the way -- the 422
     branch, the forced panel switches, the toasts -- is as true of a
     chat-written scenario as of a typed one, and a copy of it would be a
     copy that drifts. */
  const runWith = useCallback(
    async (text: string, where: Origin, login?: RunLogin) => {
      const result = await runPoll.start(text, where, login);
      if (!result.ok) {
        if (result.status === 422) {
          validation.applyResult(result.body as ValidationResult);
          showPanel("timeline");
          setRunTab("vocab");
          toast("Senaryo çalıştırılmadı: tanımsız adım var", true);
          return;
        }
        toast(result.error, true);
        return;
      }
      /* Showing the panel is not enough: the analyst may have switched to
         the dictionary half while writing, and a run that streams behind
         the other tab is a run nobody sees. The 422 branch above forces
         the opposite half for the same reason. */
      showPanel("timeline");
      setRunTab("timeline");
      toast("Koşu başladı · " + result.body.executor);
    },
    [runPoll, validation, toast, showPanel],
  );

  /* The CIF the editor's scenario runs under. Choosing one rewrites the
     scenario's login line in the editor (see lib/loginLine); like every
     change on this screen it is not written to the file. */
  const [loginId, setLoginId] = useState("");
  const chosenUser = loginId ? users.byId(loginId) : null;
  useEffect(() => {
    const first = users.users[0];
    if (!chosenUser && first) setLoginId(first.id);
  }, [chosenUser, users.users]);

  const runLogin: RunLogin | undefined = chosenUser
    ? { cif: chosenUser.cif, userid: chosenUser.userid }
    : undefined;

  const handleRunClick = useCallback(
    () => runWith(feature, resolvedOrigin, runLogin),
    [runWith, feature, resolvedOrigin, runLogin],
  );

  /**
   * "Git": open a registered scenario in the editor, on the Çalıştır screen.
   *
   * Goes through handleGenerated's own path -- clear the batch, pin the
   * origin, put the text in the editor, validate -- so the editor, its
   * validation and its Çalıştır are the ordinary ones: the registry does not
   * grow a second way to start a run.
   *
   * `login` is the one thing a registered scenario does NOT carry. A file in
   * products/ is written once and run by whoever needs it, so the cif picked
   * in the registry travels with the opened file instead of being written
   * into it.
   */
  const openRegistered = useCallback(
    (text: string, where: Origin, login: RunLogin | undefined,
     file: { app: string; product: string; path: string }) => {
      const { tags, body } = splitTags(text);
      handleGenerated(body, where);
      setLinked({ ...file, tags, saved: body, login });
      setSessionText(body);
      // The registry's CIF comes along, so the dropdown starts on it.
      const match = login
        ? users.users.find((u) => u.cif === login.cif && u.userid === login.userid)
        : undefined;
      if (match) setLoginId(match.id);
      showPanel("timeline");
      setRunTab("timeline");
    },
    [handleGenerated, showPanel, users.users],
  );

  /* Çalıştır's Kaydet is for THIS SCREEN ONLY: nothing is written to disk.
     The file is changed from Kayıtlı Senaryolar; here an analyst tries a
     variation and runs it. `sessionText` is what Kaydet last kept, so Vazgeç
     goes back to it; `linked.saved` stays the file as read, and leaving the
     Çalıştır panel brings that back. */
  const [sessionText, setSessionText] = useState("");

  const saveLinked = useCallback(() => {
    setSessionText(feature);
    setFileEditing(false);
    toast("Bu ekranda kaydedildi — dosya değişmedi");
  }, [feature, toast]);

  const changeLogin = useCallback((id: string) => {
    setLoginId(id);
    const user = users.byId(id);
    if (!user) return;
    const app = resolvedOrigin.app || linked?.app || "";
    const next = withLogin(feature, loginStepFor(app, user.cif, user.userid));
    if (next === feature) return;
    setFeature(next);
    if (!fileEditing) setSessionText(next);
    validation.scheduleValidate();
  }, [users, resolvedOrigin.app, linked, feature, validation, fileEditing]);

  const cancelLinked = useCallback(() => {
    if (!linked) return;
    setFeature(sessionText);
    setFileEditing(false);
    validation.scheduleValidate();
  }, [linked, sessionText, validation]);

  // Leaving the Çalıştır panel drops every change made on it -- the CIF too,
  // which goes back to the one the scenario was opened with.
  useEffect(() => {
    if (sideTab === "timeline" || !linked) return;
    setFeature(linked.saved);
    setSessionText(linked.saved);
    setFileEditing(false);
    const opened = linked.login
      ? users.users.find((u) => u.cif === linked.login!.cif && u.userid === linked.login!.userid)
      : undefined;
    if (opened) setLoginId(opened.id);
    validation.scheduleValidate();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sideTab]);

  /* Chat's Çalıştır: the draft goes into the editor FIRST, then runs. The
     editor is where a scenario lives once it exists -- validated, with the
     gutter and the error panel around it -- and a run whose scenario is not
     the one on screen is the stale-panel bug all over again. */
  const runDraft = useCallback(
    (text: string, where: Origin) => {
      handleGenerated(text, where);
      void runWith(text, where);
    },
    [handleGenerated, runWith],
  );

  /* Three panels need the whole workspace rather than the 40% column: a
     conversation beside a growing draft, a three-level tree beside a
     five-column table, and a login pool beside the form that edits it.
     Each of the three is READ far more than the editor is while it is
     open, which is the test -- not how much markup it happens to have. */
  const wideMode = panelOpen
    && (sideTab === "chat" || sideTab === "product" || sideTab === "users"
      || sideTab === "agent");

  const liveRun = runPoll.run?.status === "RUNNING";
  const agentVisible = !tools.loaded || tools.available;
  const runDisabled = runPoll.starting || listMode;

  return (
    <section id="view-workspace" className={"view" + (active ? " is-active" : "")}>
      <SideRail
        active={sideTab}
        onChange={showPanel}
        liveRun={liveRun}
        agentVisible={agentVisible}
        collapsed={!panelOpen}
        onToggleCollapse={() => setPanelOpen((open) => !open)}
      />
      {listMode ? (
        <div className="pane pane-editor">
          <EditorPaneHead
            badge={<Badge variant="idle">{drafts.length} senaryo</Badge>}
            onValidateClick={() => {}}
            onRunClick={() => {}}
            validateDisabled
            runDisabled
          />
          <MultiList drafts={drafts} onOpen={openDraft} />
        </div>
      ) : (
        <EditorPane
          runFocused={panelOpen && sideTab === "timeline"}
          file={linked ? {
            path: linked.path,
            editing: fileEditing,
            saving: false,
            onEdit: () => setFileEditing(true),
            onCancel: cancelLinked,
            onSave: saveLinked,
          } : null}
          login={{
            users: users.users.map((u) => ({ id: u.id, label: u.cif + (u.name ? " — " + u.name : "") })),
            value: loginId,
            onChange: changeLogin,
          }}
          feature={feature}
          onChange={handleChange}
          textareaRef={textareaRef}
          gutterRef={gutterRef}
          highlightRef={highlightRef}
          onScroll={syncScroll}
          validationState={validation.state}
          errors={validation.errors}
          onValidateClick={() => void validation.validateNow()}
          onRunClick={() => void handleRunClick()}
          runDisabled={runDisabled}
          onGoToLine={goToLine}
          onApplyFix={replaceLine}
          multiBar={
            activeDraft !== null && draftsRef.current[activeDraft] ? (
              <MultiBar
                position={activeDraft + 1}
                total={drafts.length}
                draft={draftsRef.current[activeDraft]}
                onBack={backToList}
              />
            ) : null
          }
        />
      )}
      {/* Hidden, never unmounted. The panels hold state the analyst typed
          -- the agent's credentials and its editable YAML output, both
          generator forms -- and unmounting would throw it away the moment
          someone closed the panel to read a long scenario. Same reason
          App.tsx keeps both views mounted. */}
      <aside
        className={
          "pane pane-side" + (panelOpen ? "" : " is-closed") + (wideMode ? " is-wide" : "")
        }
      >
        <ChatView
          active={sideTab === "chat"}
          catalogs={catalogs.catalogs}
          products={products}
          users={users}
          preselect={lastSavedCatalog}
          onHandOff={handleGenerated}
          onRunDraft={runDraft}
        />
        <UsersPanel active={sideTab === "users"} users={users} />
        <ProductPanel
          active={sideTab === "product"}
          users={users}
          products={products}
          onOpen={openRegistered}
        />
        <TimelinePanel
          active={sideTab === "timeline"}
          tab={runTab}
          onTabChange={setRunTab}
          run={runPoll.run}
          artifacts={artifacts}
          onStop={() => void runPoll.stop()}
          steps={vocabulary.steps}
          vocabularyLoaded={vocabulary.loaded}
          vocabularyFailed={vocabulary.failed}
          onInsert={insertAtCursor}
        />
        <SuggestPanel
          active={sideTab === "suggest"}
          catalogs={catalogs.catalogs}
          products={products}
          users={users}
          preselect={lastSavedCatalog}
          onGenerated={handleGenerated}
          onBatchGenerated={handleBatchGenerated}
          onFallbackChange={setFallbackOrigin}
        />
        {agentVisible ? (
          <AgentPanel
            active={sideTab === "agent"}
            tools={tools}
            users={users}
            onCatalogSaved={handleCatalogSaved}
          />
        ) : null}
      </aside>
    </section>
  );
}
