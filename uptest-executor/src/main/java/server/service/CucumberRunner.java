package server.service;

import com.ykb.uplift.uptest.playwright.common.RunContext;
import io.cucumber.core.cli.Main;
import server.timeline.RunTimeline;
import server.timeline.StepTimelinePlugin;
import server.timeline.TimelineRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

public class CucumberRunner implements Callable<CucumberRunnerResult> {

    /**
     * The glue is a PACKAGE name, not a folder path. It must match the
     * {@code package} declarations in the step classes
     * (com.ykb.uplift.uptest.playwright.*), which is what ends up on the
     * classpath — not the directory the sources happen to sit in.
     */
    private static final String GLUE_PACKAGE = "com.ykb.uplift.uptest.playwright";

    private final CucumberInput input;

    public CucumberRunner(CucumberInput input) {
        this.input = input;
    }

    @Override
    public CucumberRunnerResult call() {
        // Registered before Cucumber starts so the plugin finds it, and dropped
        // in a finally so a finished run leaves nothing behind.
        RunTimeline timeline = TimelineRegistry.create(input.getId());
        try {
            Files.createDirectories(input.getRunDir());
            writeFeature();

            String[] cucumberOptions = new String[]{
                    "--glue", GLUE_PACKAGE,
                    "--plugin", "pretty:" + input.getLogFile(),
                    "--plugin", "html:" + input.getReportFile(),
                    "--plugin", StepTimelinePlugin.class.getName() + ":" + input.getId(),
                    input.getFeatureFile().toString(),
            };

            byte exitCode = runCucumber(cucumberOptions);
            String output = readLog();

            CucumberRunnerResult result = new CucumberRunnerResult(
                    exitCode == 0 ? ResultStatus.DONE : ResultStatus.FAILED);
            result.setOutput(output);
            if (exitCode != 0) {
                result.setError(ErrorDetail.of(exitCode));
            }
            attachTimeline(result, timeline);
            return result;

        } catch (Exception e) {
            CucumberRunnerResult result = new CucumberRunnerResult(ResultStatus.FAILED);
            result.setError(ErrorDetail.of(e));
            attachTimeline(result, timeline);
            return result;
        } finally {
            TimelineRegistry.remove(input.getId());
        }
    }

    /**
     * Copies the timeline onto the finished result before the registry entry
     * goes away, so /status keeps showing which step failed long after the run
     * ended.
     */
    private static void attachTimeline(CucumberRunnerResult result, RunTimeline timeline) {
        result.setSteps(timeline.getSteps());
        result.setCurrentStepIndex(timeline.getCurrentStepIndex());
    }

    /**
     * ALWAYS overwrite, and always as UTF-8.
     *
     * The previous {@code if (!file.exists())} guard meant a repeated run id
     * silently executed the PREVIOUS scenario — expensive to diagnose, because
     * the run looks perfectly healthy while testing the wrong thing.
     *
     * {@code new PrintWriter(file)} used the platform default charset, which
     * corrupts Turkish scenario text ("Senaryo: Müşteri sorgulama") on any host
     * whose default is not UTF-8.
     */
    private void writeFeature() throws IOException {
        Files.writeString(input.getFeatureFile(), input.getFeature(), StandardCharsets.UTF_8);
    }

    /**
     * Publishes the run directory to the Cucumber glue for the duration of the
     * run, so the hooks write traces next to this run's other artifacts.
     */
    private byte runCucumber(String[] cucumberOptions) {
        RunContext.set(input.getRunDir());
        try {
            return Main.run(cucumberOptions, Thread.currentThread().getContextClassLoader());
        } finally {
            RunContext.clear();
        }
    }

    /**
     * The pretty log is the run's output. It can be missing if Cucumber failed
     * before the plugin wrote anything (bad options, unreadable feature file),
     * and that must not mask the real error with an IOException.
     */
    private String readLog() {
        Path log = input.getLogFile();
        try {
            return Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }
}
