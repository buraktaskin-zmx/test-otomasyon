import { artifactHref } from "../../api/endpoints";
import { artifactLabel, isDownloadOnly } from "../../lib/artifacts";
import { formatBytes } from "../../lib/format";
import type { Artifact, RunStep } from "../../api/types";

/*
  app.js:415-417,444-461 (RunFooter + renderArtifacts), merged into one
  component since both only ever appear together. Rendering `artifacts` as
  an array rather than concatenating HTML strings is what makes the old
  append-twice bug structurally impossible here -- see useRunArtifacts.ts.
*/
export function RunFooter({
  runId,
  steps,
  artifacts,
}: {
  runId: string;
  steps: RunStep[];
  artifacts: Artifact[] | null;
}) {
  if (steps.length === 0) return <div className="run-footer" />;

  const passed = steps.filter((s) => s.status === "PASSED").length;

  return (
    <div className="run-footer">
      <span className="h-mono">
        {passed} / {steps.length} adım geçti
      </span>
      {artifacts && artifacts.length > 0 ? (
        <>
          <span className="artifact-sep" />
          {artifacts.map((artifact) => {
            const download = isDownloadOnly(artifact.name);
            return (
              <a
                key={artifact.name}
                className="artifact"
                href={artifactHref(runId, artifact.name)}
                target={download ? undefined : "_blank"}
                rel={download ? undefined : "noopener"}
              >
                {artifactLabel(artifact.name)}
                <span className="artifact-size">{formatBytes(artifact.sizeBytes)}</span>
              </a>
            );
          })}
        </>
      ) : null}
    </div>
  );
}
