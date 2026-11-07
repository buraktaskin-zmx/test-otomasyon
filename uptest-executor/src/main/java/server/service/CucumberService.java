package server.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import server.exception.ErrorCodes;
import server.exception.StatusCodeException;
import server.timeline.RunTimeline;
import server.timeline.TimelineRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class CucumberService {

    /**
     * Finished runs kept for /status after they complete. The map used to grow
     * forever, which in a long-lived executor is a slow leak; the orchestrator
     * only needs recent results, and the artifacts stay on disk regardless.
     */
    private static final int MAX_RETAINED_RUNS = 200;

    private final int simultaneouslyRun;

    private final Path featuresFolder;

    private final ExecutorService executorService;

    private final ConcurrentHashMap<UUID, Future<CucumberRunnerResult>> pool;

    /**
     * Whether each run's THREAD is still inside it — not whether its Future
     * says it is done.
     *
     * {@code cancel(true)} makes the Future done at once, but a run stuck in a
     * native Playwright call keeps its pool thread long after. Counting the
     * Future freed the slot anyway, so the next /start was accepted with 202
     * and then sat in the fixed pool's queue reported as RUNNING: the "refuse,
     * never queue" rule broken silently. A run is now active while its thread
     * has started and not left, or while it has not started yet and has not
     * been cancelled (a run cancelled before it began never enters, so it must
     * not hold the slot for ever).
     */
    private final ConcurrentHashMap<UUID, Slot> slots = new ConcurrentHashMap<>();

    private static final class Slot {
        final AtomicBoolean started = new AtomicBoolean();
        final AtomicBoolean exited = new AtomicBoolean();
    }

    /** Insertion order, so the oldest FINISHED run is the one evicted. */
    private final Deque<UUID> submissionOrder = new ArrayDeque<>();

    public CucumberService(
            @Value("${simultaneouslyRun}") Integer simultaneouslyRun,
            @Value("${featuresFolder}") String featuresFolder
    ) throws IOException {
        this.simultaneouslyRun = simultaneouslyRun;
        this.featuresFolder = Path.of(featuresFolder);
        this.executorService = Executors.newFixedThreadPool(simultaneouslyRun);
        this.pool = new ConcurrentHashMap<>();

        // createDirectories, not mkdir(): mkdir() silently fails when a parent
        // is missing and its return value was ignored, so the run failed later
        // with a confusing "cannot write feature file" instead of here.
        Files.createDirectories(this.featuresFolder);
    }

    public Map<UUID, ResultStatus> getPoolStatus() {
        Map<UUID, ResultStatus> statusMap = new LinkedHashMap<>();
        for (Map.Entry<UUID, Future<CucumberRunnerResult>> entry : pool.entrySet()) {
            statusMap.put(entry.getKey(), statusOf(entry.getValue()));
        }
        return statusMap;
    }

    /**
     * Accepts a run, or refuses with 409 when this executor is already busy.
     *
     * Refusing beats queueing: /nodeStatus followed by /start is a race, so the
     * orchestrator must be able to learn it lost and pick another executor. A
     * silently queued run would sit invisible behind a 4-minute test.
     *
     * The whole check-and-submit is synchronized because the previous
     * count-then-submit was not atomic — two simultaneous /start calls could
     * both observe a free slot and both be accepted.
     */
    public synchronized void start(UUID id, String feature) {
        if (pool.containsKey(id)) {
            throw new StatusCodeException(ErrorCodes.E2, 409,
                    "Run id already used: " + id);
        }

        long active = activeRuns();
        if (active >= simultaneouslyRun) {
            throw new StatusCodeException(ErrorCodes.E3, 409,
                    "Executor busy: " + active + "/" + simultaneouslyRun + " runs active");
        }

        CucumberInput input = new CucumberInput();
        input.setId(id);
        input.setFolder(featuresFolder.toString());
        input.setFeature(feature);

        CucumberRunner runner = new CucumberRunner(input);
        Slot slot = new Slot();
        slots.put(id, slot);
        pool.put(id, executorService.submit(() -> {
            slot.started.set(true);
            try {
                return runner.call();
            } finally {
                slot.exited.set(true);
            }
        }));
        submissionOrder.addLast(id);
        evictOldestFinished();
    }

    public void stop(UUID uuid) {
        Future<CucumberRunnerResult> obj = pool.get(uuid);

        if (obj == null) {
            throw new StatusCodeException(ErrorCodes.E1, 400, "Runnable not found");
        }

        // Cooperative only. A hung Playwright call blocks in a native call that
        // interrupt() never reaches; restarting the container is the backstop.
        obj.cancel(true);
    }

    public CucumberRunnerResult getStatus(UUID uuid) throws ExecutionException, InterruptedException {
        Future<CucumberRunnerResult> obj = pool.get(uuid);

        if (obj == null) {
            throw new StatusCodeException(ErrorCodes.E1, 400, "Runnable not found");
        }

        if (obj.isCancelled()) {
            return withLiveTimeline(new CucumberRunnerResult(ResultStatus.CANCELED), uuid);
        }

        if (!obj.isDone()) {
            // The Future has no value yet, so the progress view comes from the
            // timeline the Cucumber plugin is writing right now.
            return withLiveTimeline(new CucumberRunnerResult(ResultStatus.RUNNING), uuid);
        }

        // A finished run already carries its own final timeline.
        return obj.get();
    }

    private static CucumberRunnerResult withLiveTimeline(CucumberRunnerResult result, UUID runId) {
        RunTimeline timeline = TimelineRegistry.get(runId);
        if (timeline != null) {
            result.setSteps(timeline.getSteps());
            result.setCurrentStepIndex(timeline.getCurrentStepIndex());
        }
        return result;
    }

    /** Runs still holding a pool thread, or about to — see {@link #slots}. */
    private long activeRuns() {
        return pool.entrySet().stream().filter(entry -> occupies(entry.getKey(), entry.getValue())).count();
    }

    private boolean occupies(UUID id, Future<CucumberRunnerResult> future) {
        Slot slot = slots.get(id);
        if (slot == null) {
            return !future.isDone();
        }
        if (slot.exited.get()) {
            return false;
        }
        return slot.started.get() || !future.isDone();
    }

    private ResultStatus statusOf(Future<CucumberRunnerResult> future) {
        if (future.isCancelled()) {
            return ResultStatus.CANCELED;
        }
        if (!future.isDone()) {
            return ResultStatus.RUNNING;
        }
        try {
            return future.get().getStatus();
        } catch (ExecutionException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return ResultStatus.FAILED;
        }
    }

    /** Called while holding the lock on this service. */
    private void evictOldestFinished() {
        while (pool.size() > MAX_RETAINED_RUNS && !submissionOrder.isEmpty()) {
            UUID oldest = submissionOrder.peekFirst();
            Future<CucumberRunnerResult> future = pool.get(oldest);
            if (future != null && occupies(oldest, future)) {
                return; // oldest is still running: keep it and stop evicting
            }
            submissionOrder.pollFirst();
            pool.remove(oldest);
            slots.remove(oldest);
        }
    }
}
