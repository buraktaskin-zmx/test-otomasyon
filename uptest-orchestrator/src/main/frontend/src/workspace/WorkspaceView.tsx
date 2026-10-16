import { useCallback, useEffect, useRef, useState } from "react";
import { EditorPane } from "./editor/EditorPane";
import { EditorPaneHead } from "./editor/EditorPaneHead";
import { MultiBar } from "./multi/MultiBar";
import { MultiList } from "./multi/MultiList";
import { MultiPanel } from "./multi/MultiPanel";
import type { Draft } from "./multi/draft";
import { AgentPanel } from "./side/AgentPanel";
import { SideTabs } from "./side/SidePane";
import type { SideTab } from "./side/SidePane";
import { SuggestPanel } from "./side/SuggestPanel";
import { TimelinePanel } from "./side/TimelinePanel";
import { VocabPanel } from "./side/VocabPanel";
import { Badge } from "../ui/Badge";
import { useCatalogs } from "../hooks/useCatalogs";
import { useEditorOperations } from "../hooks/useEditorOperations";
import { useExploreTools } from "../hooks/useExploreTools";
import { useOrigin } from "../hooks/useOrigin";
import { useRunArtifacts } from "../hooks/useRunArtifacts";
import { useRunPoll } from "../hooks/useRunPoll";
import { useValidation } from "../hooks/useValidation";
import { useToast } from "../ui/Toast";
import type { Origin, ValidationResult } from "../api/types";
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
  const [sideTab, setSideTab] = useState<SideTab>("vocab");
  const toast = useToast();
  const origin = useOrigin();
  const catalogs = useCatalogs();
  const tools = useExploreTools();
  const runPoll = useRunPoll();
  const artifacts = useRunArtifacts(runPoll.run?.runId ?? null, runPoll.run?.status ?? null);

  /* The live fallback screen/intent AI Üret or Çoklu Üret currently shows --
     read only when nothing has been pinned yet. See lib/originScreen.ts. */
  const [fallbackOrigin, setFallbackOrigin] = useState<Origin>({ app: "", flow: "", intent: "" });
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

  const { textareaRef, gutterRef, syncGutterScroll, goToLine, replaceLine, insertAtCursor } =
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

  const handleRunClick = useCallback(async () => {
    const result = await runPoll.start(feature, resolvedOrigin);
    if (!result.ok) {
      if (result.status === 422) {
        validation.applyResult(result.body as ValidationResult);
        setSideTab("vocab");
        toast("Senaryo çalıştırılmadı: tanımsız adım var", true);
        return;
      }
      toast(result.error, true);
      return;
    }
    setSideTab("timeline");
    toast("Koşu başladı · " + result.body.executor);
  }, [runPoll, feature, resolvedOrigin, validation, toast]);

  const liveRun = runPoll.run?.status === "RUNNING";
  const agentVisible = !tools.loaded || tools.available;
  const runDisabled = runPoll.starting || listMode;

  return (
    <section id="view-workspace" className={"view" + (active ? " is-active" : "")}>
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
          onScroll={syncGutterScroll}
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
      <aside className="pane pane-side">
        <SideTabs
          active={sideTab}
          onChange={setSideTab}
          liveRun={liveRun}
          agentVisible={agentVisible}
        />
        <VocabPanel
          active={sideTab === "vocab"}
          steps={vocabulary.steps}
          loaded={vocabulary.loaded}
          failed={vocabulary.failed}
          onInsert={insertAtCursor}
        />
        <TimelinePanel
          active={sideTab === "timeline"}
          run={runPoll.run}
          artifacts={artifacts}
          onStop={() => void runPoll.stop()}
        />
        <SuggestPanel
          active={sideTab === "suggest"}
          catalogs={catalogs.catalogs}
          preselect={lastSavedCatalog}
          onGenerated={handleGenerated}
          onFallbackChange={setFallbackOrigin}
        />
        <MultiPanel
          active={sideTab === "multi"}
          catalogs={catalogs.catalogs}
          preselect={lastSavedCatalog}
          onBatchGenerated={handleBatchGenerated}
          onFallbackChange={setFallbackOrigin}
        />
        {agentVisible ? (
          <AgentPanel active={sideTab === "agent"} tools={tools} onCatalogSaved={handleCatalogSaved} />
        ) : null}
      </aside>
    </section>
  );
}
