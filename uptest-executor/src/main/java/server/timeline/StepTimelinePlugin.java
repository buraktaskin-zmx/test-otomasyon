package server.timeline;

import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.TestCaseStarted;
import io.cucumber.plugin.event.TestStep;
import io.cucumber.plugin.event.TestStepFinished;
import io.cucumber.plugin.event.TestStepStarted;

import java.util.UUID;

/**
 * Feeds the live step timeline from Cucumber's own execution events.
 *
 * Registered as {@code --plugin server.timeline.StepTimelinePlugin:<runId>};
 * Cucumber instantiates plugins itself and passes the text after the colon to a
 * single-String constructor, which is how the run id gets in here.
 *
 * Events, not the pretty log: parsing console output would re-derive state that
 * Cucumber already publishes, and would break the first time the formatter
 * changed.
 */
public class StepTimelinePlugin implements ConcurrentEventListener {

    private final UUID runId;

    public StepTimelinePlugin(String runId) {
        this.runId = UUID.fromString(runId);
    }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestCaseStarted.class, this::onTestCaseStarted);
        publisher.registerHandlerFor(TestStepStarted.class, this::onTestStepStarted);
        publisher.registerHandlerFor(TestStepFinished.class, this::onTestStepFinished);
    }

    /**
     * Publishes the whole scenario up front, every step PENDING, so the UI can
     * draw the full timeline immediately instead of growing it line by line.
     */
    private void onTestCaseStarted(TestCaseStarted event) {
        RunTimeline timeline = TimelineRegistry.get(runId);
        if (timeline == null) {
            return;
        }
        for (TestStep testStep : event.getTestCase().getTestSteps()) {
            // Hooks are TestSteps too, but they are not Gherkin lines and mean
            // nothing to an analyst.
            if (testStep instanceof PickleStepTestStep pickleStep) {
                timeline.addStep(
                        testStep.getId().toString(),
                        new StepState(pickleStep.getStep().getKeyword().trim(),
                                pickleStep.getStep().getText()));
            }
        }
    }

    private void onTestStepStarted(TestStepStarted event) {
        RunTimeline timeline = TimelineRegistry.get(runId);
        if (timeline != null && event.getTestStep() instanceof PickleStepTestStep) {
            timeline.started(event.getTestStep().getId().toString());
        }
    }

    private void onTestStepFinished(TestStepFinished event) {
        RunTimeline timeline = TimelineRegistry.get(runId);
        if (timeline == null || !(event.getTestStep() instanceof PickleStepTestStep)) {
            return;
        }
        Result result = event.getResult();
        timeline.finished(
                event.getTestStep().getId().toString(),
                map(result.getStatus()),
                result.getDuration() != null ? result.getDuration().toMillis() : null,
                messageOf(result));
    }

    private static StepState.Status map(io.cucumber.plugin.event.Status status) {
        return switch (status) {
            case PASSED -> StepState.Status.PASSED;
            case SKIPPED -> StepState.Status.SKIPPED;
            case UNDEFINED -> StepState.Status.UNDEFINED;
            // AMBIGUOUS and PENDING are authoring faults, not passes: showing
            // them as failures is what an analyst needs to see.
            default -> StepState.Status.FAILED;
        };
    }

    /** First line only: the UI shows the summary, the run log holds the trace. */
    private static String messageOf(Result result) {
        Throwable error = result.getError();
        if (error == null) {
            return null;
        }
        String message = error.getMessage() != null ? error.getMessage() : error.toString();
        int newline = message.indexOf('\n');
        return newline > 0 ? message.substring(0, newline) : message;
    }
}
