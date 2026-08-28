package server.timeline;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The live step timeline of one run, written by {@link StepTimelinePlugin} while
 * Cucumber executes and read by /status while the run is still going.
 *
 * This exists because a {@code Future<CucumberRunnerResult>} only has a value
 * once the run is over — it cannot back a progress view. Reads happen on Tomcat
 * threads while writes happen on the runner thread, hence the concurrent
 * collections and volatile fields.
 */
public class RunTimeline {

    private final List<StepState> steps = new CopyOnWriteArrayList<>();

    /** Cucumber's test-step id -> position in {@link #steps}. */
    private final Map<String, Integer> indexById = new ConcurrentHashMap<>();

    private volatile int currentStepIndex = -1;

    /** First step that failed, or -1. See {@link #getCurrentStepIndex()}. */
    private volatile int failedStepIndex = -1;

    /** Appends a scenario's steps; a feature may hold more than one. */
    public void addStep(String id, StepState step) {
        steps.add(step);
        indexById.put(id, steps.size() - 1);
    }

    public void started(String id) {
        Integer index = indexById.get(id);
        if (index == null) {
            return;
        }
        currentStepIndex = index;
        steps.get(index).setStatus(StepState.Status.RUNNING);
    }

    public void finished(String id, StepState.Status status, Long durationMs, String error) {
        Integer index = indexById.get(id);
        if (index == null) {
            return;
        }
        StepState step = steps.get(index);
        step.setStatus(status);
        step.setDurationMs(durationMs);
        step.setError(error);

        if (status == StepState.Status.FAILED && failedStepIndex < 0) {
            failedStepIndex = index;
        }
    }

    public List<StepState> getSteps() {
        return List.copyOf(steps);
    }

    /**
     * The step the UI should highlight: the one running now, or — once a step
     * has failed — the one that failed.
     *
     * Cucumber emits TestStepStarted for skipped steps too, so a plain "last
     * step touched" would point past the failure at a step that never ran.
     */
    public int getCurrentStepIndex() {
        return failedStepIndex >= 0 ? failedStepIndex : currentStepIndex;
    }
}
