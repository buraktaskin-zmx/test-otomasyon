import { useCallback, useState } from "react";
import { HistoryView } from "./history/HistoryView";
import { useTheme } from "./hooks/useTheme";
import { useVocabulary } from "./hooks/useVocabulary";
import { TopBar } from "./shell/TopBar";
import type { View } from "./shell/ViewTabs";
import { useToast } from "./ui/Toast";
import { WorkspaceView } from "./workspace/WorkspaceView";

export function App() {
  const [view, setView] = useState<View>("workspace");
  const { toggle } = useTheme();
  const toast = useToast();

  /* Lives here, not inside WorkspaceView: the executor pill (TopBar, a
     SIBLING of WorkspaceView) needs to trigger the same refresh the
     vocabulary panel renders, and both need the one fetch -- a second one
     could disagree with itself. */
  const vocabulary = useVocabulary();

  const handleRefresh = useCallback(() => {
    void vocabulary.refresh().then((ok) => {
      toast(ok ? "Executor durumu ve sözlük yenilendi" : "Sözlük yenilenemedi", !ok);
    });
  }, [vocabulary, toast]);

  return (
    <>
      <TopBar view={view} onChangeView={setView} onRefresh={handleRefresh} onToggleTheme={toggle} />
      <main>
        {/* Both views stay mounted and are shown with .is-active, exactly as
            app.css expects. Unmounting the workspace would throw away the
            scenario text every time someone glanced at the history. */}
        <WorkspaceView active={view === "workspace"} vocabulary={vocabulary} />
        <HistoryView active={view === "history"} />
      </main>
    </>
  );
}
