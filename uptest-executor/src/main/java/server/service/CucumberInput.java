package server.service;

import java.nio.file.Path;
import java.util.UUID;

/**
 * One run's inputs, plus the per-run directory every artifact of that run
 * lives in:
 *
 *   {baseFolder}/{runId}/scenario.feature
 *   {baseFolder}/{runId}/run.log
 *   {baseFolder}/{runId}/report.html
 *   {baseFolder}/{runId}/traces/*.zip
 *
 * Nothing writes to a fixed path, so concurrent runs cannot overwrite each
 * other's report or traces, and the orchestrator can read one run's output
 * from a single directory.
 *
 * Paths are built with {@link Path#resolve} rather than string concatenation,
 * which silently produced "<folder><id>.feature" when the configured folder
 * had no trailing slash.
 */
public class CucumberInput {

    private UUID id;

    private String feature;

    private String folder;

    public String getFeature() {
        return feature;
    }

    public void setFeature(String feature) {
        this.feature = feature;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getFolder() {
        return folder;
    }

    public void setFolder(String folder) {
        this.folder = folder;
    }

    public Path getRunDir() {
        return Path.of(folder).resolve(id.toString());
    }

    public Path getFeatureFile() {
        return getRunDir().resolve("scenario.feature");
    }

    public Path getLogFile() {
        return getRunDir().resolve("run.log");
    }

    public Path getReportFile() {
        return getRunDir().resolve("report.html");
    }
}
