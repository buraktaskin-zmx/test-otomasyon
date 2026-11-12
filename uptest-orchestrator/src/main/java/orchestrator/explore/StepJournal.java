package orchestrator.explore;

import java.util.ArrayList;
import java.util.List;

/**
 * Which stretches of a walk's tape were {@code do_steps} batches, and which
 * of their steps were carried out.
 *
 * <p>The tape records a batch's sub-steps under their real tools (a click is
 * a {@code browser_click}), which is right for Gherkin and ClaimCheck and
 * wrong for a replay: the click on a grid row is recorded as a reference and
 * a long accessible name, while the step that made it said
 * {@code row "Ödenecek"} — the thing that still means something on the next
 * run, when the references are new and the row may be a different invoice.
 * So the step itself is kept here, beside the tape range it produced, and
 * {@link CachedSteps#fromWalk} prefers it to the raw calls in that range.
 *
 * <p>One per request; never stored.
 */
final class StepJournal {

    /** Tape entries [from, to) were one batch; {@code done} are its steps that came back "tamam". */
    record Batch(int from, int to, List<StepRunner.Step> done) {
    }

    private final List<Batch> batches = new ArrayList<>();

    synchronized void add(int from, int to, List<StepRunner.Step> done) {
        if (to > from) {
            batches.add(new Batch(from, to, List.copyOf(done)));
        }
    }

    synchronized List<Batch> batches() {
        return List.copyOf(batches);
    }
}
