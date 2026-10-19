import { useCallback, useEffect, useRef, useState } from "react";
import { EditorPane } from "./editor/EditorPane";
import { EditorPaneHead } from "./editor/EditorPaneHead";
import { MultiBar } from "./multi/MultiBar";
import { MultiList } from "./multi/MultiList";
import { MultiPanel } from "./multi/MultiPanel";
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
import type { useVocabulary } from "../hooks/useVocabulary";

/* app.js:18-31, verbatim. */
const SAMPLE = [
  "Feature: Banko Ödeme Talebi",
  "",
  "  Scenario: Kullanıcı Banko ekranından ödeme talep eder",
  '    Given login with cif "DI00002018" and userid "3014"',
  '    And navigate to "NAKİT YÖNETİMİ" "Ödeme Oluştur / Talep Et"',
  '    And click "Ödeme Talep Et"',
  '    And enter "TCKN / VKN" "21203030786"',
  '    And enter amount "Ödeme Tutarı" "1250,55"',
  '    And select account "0006 7010 0000 0003 4322" from "Alıcı Hesabı"',
  '    And click "Devam"',
  '    Then verify the "Ödeme Tutarı" has amount "1250,55"',
  "",
].join("\n");

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
  const [feature, setFeature] = useState(SAMPLE);
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
  const [multiNotes, setMultiNotes] = useState<string[]>([]);
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
    setMultiNotes([]);
    activeDraftRef.current = null;
    setActiveDraft(null);
  }, []);

  const handleBatchGenerated = useCallback((newDrafts: Draft[], notes: string[]) => {
    setDrafts(newDrafts);
    setMultiNotes(notes);
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

  const handleGenerated = useCallback(
    (generatedFeature: string, generatedOrigin: Origin) => {
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

  const handleRunClick = useCallback(
    () => runWith(feature, resolvedOrigin),
    [runWith, feature, resolvedOrigin],
  );

  /**
   * Run a scenario straight from the registry.
   *
   * Goes through handleGenerated's own path — clear the batch, pin the origin,
   * put the text in the editor — because a run whose scenario is not the one
   * on screen is the stale-panel bug again, and because the registry must not
   * grow a SECOND way to start a run: there is one gate and it re-validates.
   *
   * The panel switches before the run starts, with a short pause, so the
   * analyst is already watching the timeline when the first step lands rather
   * than discovering it afterwards. That is all the pause buys and all it is
   * for.
   *
   * `login` is the one thing a registered scenario does NOT carry. A file in
   * products/ is written once and run by whoever needs it, so baking one
   * analyst's cif into it is how a suite stops being shareable -- the picker
   * sits above the scenario instead, and the orchestrator writes the step.
   */
  const runRegistered = useCallback(
    (text: string, where: Origin, login?: RunLogin) => {
      handleGenerated(text, where);
      showPanel("timeline");
      window.setTimeout(() => void runWith(text, where, login), 800);
    },
    [handleGenerated, showPanel, runWith],
  );

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
    && (sideTab === "chat" || sideTab === "product" || sideTab === "users");

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
          <MultiList drafts={drafts} notes={multiNotes} onOpen={openDraft} />
        </div>
      ) : (
        <EditorPane
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
          onRun={runRegistered}
        />
        <TimelinePanel
          active={sideTab === "timeline"}
          tab={runTab}
          onTabChange={setRunTab}
          run={runPoll.run}
          artifacts={artifacts}
          onStop={() => void runPoll.stop()}
          onRun={() => void handleRunClick()}
          runDisabled={runDisabled}
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
          onFallbackChange={setFallbackOrigin}
        />
        <MultiPanel
          active={sideTab === "multi"}
          catalogs={catalogs.catalogs}
          products={products}
          users={users}
          preselect={lastSavedCatalog}
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
