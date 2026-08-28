package server.timeline;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Connects a run to its timeline across a boundary we do not control: Cucumber
 * constructs its plugins itself, so the runner cannot hand the plugin an object.
 * It can, however, pass a string — the run id — via
 * {@code --plugin server.timeline.StepTimelinePlugin:<runId>}, and the plugin
 * looks itself up here.
 *
 * Static state, deliberately scoped: entries exist only while a run is in
 * flight ({@link #remove} runs in a finally), so this cannot grow unbounded and
 * two concurrent runs cannot see each other's steps.
 */
public final class TimelineRegistry {

    private static final ConcurrentHashMap<UUID, RunTimeline> TIMELINES = new ConcurrentHashMap<>();

    private TimelineRegistry() {
    }

    public static RunTimeline create(UUID runId) {
        RunTimeline timeline = new RunTimeline();
        TIMELINES.put(runId, timeline);
        return timeline;
    }

    /** The in-flight timeline for a run, or null once the run has finished. */
    public static RunTimeline get(UUID runId) {
        return TIMELINES.get(runId);
    }

    public static void remove(UUID runId) {
        TIMELINES.remove(runId);
    }
}
