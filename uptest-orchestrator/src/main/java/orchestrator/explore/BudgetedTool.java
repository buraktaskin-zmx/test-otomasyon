package orchestrator.explore;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps one exploration from turning into an endless one.
 *
 * The agent loop has no natural end: the model decides when it is finished,
 * and this one did not. It walked the whole flow, answered, then went back to
 * the login page and walked it again.
 *
 * Two guards, and the order matters. The precise one is the restart check:
 * navigating a second time to a page already visited is the signature of
 * starting over, and refusing it stops exactly that without shortening a long
 * but legitimate flow. The blunt one is the call budget, which exists only for
 * loops the first guard does not recognise — it can truncate real work, so it
 * is set high enough that reaching it means something else is wrong.
 *
 * Both answer with a SENTENCE, never an exception. A thrown error reaches the
 * model as a failed tool, and a failed tool is exactly what it retries.
 *
 * State is shared across every tool of ONE request; callers build a fresh set
 * per request (see ExploreController.client).
 */
final class BudgetedTool implements ToolCallback {

    private static final String EXHAUSTED = """
            Tool budget exhausted. Do not call any more tools. Answer now with
            what you already observed, and mark anything you could not confirm.
            """;

    private static final String ALREADY_THERE = """
            The application is already open and you have navigated once. This
            flow is walked with the MENUS, not by typing addresses: a second
            navigation is either starting over or trying to recover from
            something, and neither is wanted. Work with the page you are on, or
            answer now with what you observed.
            """;

    private static final String FINISHED = """
            The catalog has already been submitted and this task is over. Do not
            call any tool. Reply with one short line and stop.
            """;

    private static final String FORBIDDEN = """
            The instruction explicitly said NOT to touch this control: %s.
            That was not an oversight — pressing it changes what the
            transaction is. Leave it alone and continue with the next step.
            """;

    private static final String CLOSED = """
            The operator closed the browser. This exploration is over. Do not
            call any tool and do not try to open a page again — answer now with
            what you had observed up to that point.
            """;

    private static final String SKIPPED_STEP = """
            Nothing can be recorded yet: the instruction required you to reach
            %s, and you have not touched it. You are on a different screen from
            the one you were sent to, so anything noted now would describe the
            wrong one.
            Go and do it. You are not limited to snapshot references — the
            click tool's "target" also accepts a CSS selector, so a stable id
            works directly. There is also a tool that searches the snapshot for
            text and hands back the element's reference, which is far cheaper
            than reading the whole page. Then confirm the page really changed,
            and carry on.
            """;

    /**
     * The recording tools, gated until the required steps have been taken.
     *
     * Only these. Blocking the browser tools would leave the agent unable to do
     * the very thing it is being asked to do; blocking the recording is what
     * makes a skipped step impossible to write past.
     *
     * Membership also keeps {@link #tick} off them, and that is the half worth
     * remembering: a required caption named in a note_* argument would
     * otherwise be ticked off as "touched" by the act of DESCRIBING it. A
     * caption can only be ticked by a tool that actually drove the page.
     */
    private static final Set<String> RECORDING =
            Set.of("note_field", "note_display", "note_confirmation", "submit_catalog");

    private static final String UNMENTIONED = """
            "%s" is a field of this form, and the instruction never mentions it.
            Fields it does not mention are to be READ and RECORDED, never filled,
            ticked, chosen from or pressed. Record it if you have not already —
            note_field if it is something you could fill in, note_display if the
            screen only shows it to you — and move on to the next field the
            instruction DOES name.
            """;

    /**
     * Below this, a label is too short to match on.
     *
     * A two-letter label appears inside half the arguments on the page by
     * accident, and a guard that fires by accident is worse than none.
     */
    private static final int SHORTEST_LABEL = 4;

    /**
     * How a vanished browser announces itself, whichever tool notices first.
     *
     * Three signatures, each found after the previous one proved incomplete:
     *
     *   "has been closed"        the window shut mid-call. Came back as a
     *                            THROWN error, not a returned one, which is
     *                            why the first version of this check never saw
     *                            it.
     *   "no open pages"          a call made when nothing is left.
     *   "about:blank"            the quiet one, and the reason this list has a
     *                            third entry. The MCP server opens a browser
     *                            whenever a tool needs one, so after the
     *                            operator closes the window the NEXT call can
     *                            succeed: a fresh browser, a blank page, an
     *                            empty snapshot and no error anywhere. The run
     *                            then carries on in a window nobody asked for.
     *                            This flow is never legitimately on a blank
     *                            page — it navigates to the bank on its first
     *                            call and reaches everything else by clicking
     *                            — so seeing one means the browser was
     *                            replaced underneath us.
     */
    private static final List<String> GONE_MARKERS =
            List.of("has been closed", "no open pages");

    /**
     * The quiet signature, checked everywhere EXCEPT a navigation's own result.
     *
     * A navigate legitimately reports the blank page it is leaving, so looking
     * for this there would abort every run on its very first call.
     */
    private static final String BLANK_MARKER = "about:blank";

    /** The tool that moves the browser to a new page. */
    private static final String NAVIGATE = "browser_navigate";

    /** The url argument out of the tool's JSON input. */
    private static final Pattern URL = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");

    private final ToolCallback delegate;
    private final AtomicInteger remaining;
    private final Set<String> visited;

    /** The catalog being built, or null on the prose endpoint, which never closes. */
    private final CatalogSink sink;

    /**
     * Controls the instruction named as off limits, lower-cased for matching.
     *
     * DATA, not a rule in this class: which button must not be pressed is a
     * property of the flow being explored, so hard-coding "Garantili Ödeme"
     * here would make the guard useless on the next screen. It arrives with
     * the request and dies with it.
     *
     * It exists because telling the model not to press something does not
     * work. It was told twice, plainly, and pressed it anyway — the same
     * shape as every other guard here: a prompt rule that only held once it
     * became code.
     */
    private final List<String> forbidden;

    /** Set once the operator closes the browser; never cleared within a request. */
    private final AtomicBoolean aborted;

    /**
     * The analyst's instruction, folded, searched for field names.
     *
     * The rule it enforces was a prompt rule first and was broken anyway: told
     * plainly not to, the agent typed into "Garanti Tutarı" — a field the
     * instruction never names, and one that changes what the transaction is.
     * A field the analyst did not mention is not an oversight to be helpfully
     * filled in; it is a field this test is not about.
     */
    private final String instruction;

    /**
     * Controls the instruction REQUIRED the agent to touch, still untouched.
     *
     * Emptied as they are seen, so whatever remains at the end was skipped.
     * This exists because the failure it catches is invisible: told in a
     * numbered step to switch to a named list, the agent did not click it,
     * did not fail, and carried on — selecting the right row of the WRONG
     * list. Every later step succeeded and the catalog looked finished. The
     * only way anyone noticed was comparing a reference number against a
     * screenshot.
     *
     * Reported, never forced. A tool call cannot be conjured from here, and
     * refusing everything until one appears would trade a quiet wrong answer
     * for a loud stuck one.
     */
    private final Set<String> pending;

    private BudgetedTool(ToolCallback delegate, AtomicInteger remaining, Set<String> visited,
                         CatalogSink sink, List<String> forbidden, AtomicBoolean aborted,
                         Set<String> pending, String instruction) {
        this.delegate = delegate;
        this.remaining = remaining;
        this.visited = visited;
        this.sink = sink;
        this.forbidden = forbidden;
        this.aborted = aborted;
        this.pending = pending;
        this.instruction = instruction;
    }

    /**
     * Thrown on the PROSE endpoint once the operator has closed the browser.
     *
     * Not a ToolExecutionException on purpose: that one is turned back into a
     * tool result for the model, which would then write an answer about a walk
     * that was cut short. This one leaves the model loop altogether, so the
     * request ends at the very next tool call and the UI can say what happened.
     * The catalog endpoint does not throw it -- a half-walked catalog is still
     * worth keeping, so there the agent is told to stop and submit instead.
     */
    static final class BrowserClosed extends RuntimeException {
        BrowserClosed() {
            super("Tarayıcı beklenmedik şekilde kapandı");
        }
    }

    /**
     * One budget and one visit history, shared by the tools of a single request.
     *
     * @param pending captions that must be touched; entries are REMOVED as
     *                they are seen, so the caller reads the leftovers
     * @param aborted set when the browser is found closed; the caller reads it
     */
    static Object[] wrap(List<ToolCallback> callbacks, int budget, CatalogSink sink,
                         List<String> forbidden, Set<String> pending, String instruction,
                         AtomicBoolean aborted) {
        AtomicInteger remaining = new AtomicInteger(budget);
        Set<String> visited = new HashSet<>();
        List<String> lowered = forbidden.stream().map(BudgetedTool::fold).toList();
        String folded = fold(instruction == null ? "" : instruction);
        return callbacks.stream()
                .map(callback -> (Object) new BudgetedTool(
                        callback, remaining, visited, sink, lowered, aborted, pending, folded))
                .toArray();
    }

    /** Turkish locale, so "GARANTİLİ" folds onto "garantili" rather than onto nothing. */
    private static String fold(String value) {
        return value.replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.forLanguageTag("tr-TR"));
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String input) {
        return guarded(input, () -> delegate.call(input));
    }

    @Override
    public String call(String input, ToolContext context) {
        return guarded(input, () -> delegate.call(input, context));
    }

    /**
     * One path in and out, so every guard sees every call.
     *
     * The failure this exists for: an MCP error does not always come BACK, it
     * is often THROWN. A check that only read the return value therefore never
     * saw the one message that matters — "No open pages available" — and a run
     * kept navigating at a browser the operator had already closed, opening a
     * fresh window each time. Both shapes are inspected now.
     */
    private String guarded(String input, Supplier<String> invoke) {
        trace(input);
        String refusal = refuse(input);
        if (refusal != null) {
            return refusal;
        }
        String result;
        try {
            result = watch(invoke.get());
        } catch (BrowserClosed closed) {
            throw closed;
        } catch (RuntimeException failed) {
            watch(String.valueOf(failed.getMessage()));
            if (aborted.get()) {
                // Answer rather than rethrow: a thrown tool is one the model
                // retries, and retrying is exactly how it kept reopening the
                // browser after it had been shut.
                return closed();
            }
            throw failed;
        }
        return aborted.get() ? closed() : trim(result);
    }

    /** The prose endpoint ends the request; the catalog endpoint asks for an answer. */
    private String closed() {
        if (sink == null) {
            throw new BrowserClosed();
        }
        return CLOSED;
    }

    /**
     * Notices the operator closing the browser, and makes it final.
     *
     * The MCP server opens a browser whenever a tool needs one, so closing the
     * window stops nothing by itself: the next call quietly gets a fresh
     * browser and the run carries on in a window nobody is watching. Shutting
     * the window has to MEAN something, and the only signal it leaves is the
     * error the first call afterwards comes back with.
     *
     * Sticky on purpose. Refusing one call would let the next one reopen a
     * browser; once this is set, every remaining call is refused and the run
     * ends. The next exploration starts from nothing — new sink, new budget,
     * new page.
     */
    private String watch(String result) {
        if (result == null) {
            return null;
        }
        String lowered = result.toLowerCase(java.util.Locale.ROOT);
        for (String marker : GONE_MARKERS) {
            if (lowered.contains(marker)) {
                return stop(marker, result);
            }
        }
        if (!NAVIGATE.equals(getToolDefinition().name()) && lowered.contains(BLANK_MARKER)) {
            return stop(BLANK_MARKER, result);
        }
        return result;
    }

    private String stop(String marker, String result) {
        if (aborted.compareAndSet(false, true)) {
            System.out.println("[uptest] explore: tarayici yok (\"" + marker
                    + "\") — kosu sonlandiriliyor");
        }
        return result;
    }

    /**
     * Bounds one tool result, so the conversation cannot grow past the model.
     *
     * Every tool result stays in the conversation for the rest of the task.
     * A page snapshot here runs to tens of thousands of characters, and around
     * the twentieth one a run simply stopped: "maximum context length is
     * 131072 tokens ... your prompt contains at least 131073". Nothing was
     * wrong with the flow; it ran out of room half-finished, after minutes of
     * real browser time.
     *
     * The cap is generous on purpose — a form's whole field list has to
     * survive, or the cure removes what the walk was for. What it stops is the
     * unbounded case. The note is addressed to the model: it says what was
     * lost and what to use instead, because a silently shortened page would
     * send it hunting for a field that was only cut off.
     */
    private String trim(String result) {
        if (result == null || result.length() <= LARGEST_RESULT) {
            return result;
        }
        System.out.println("[uptest] explore: " + getToolDefinition().name() + " sonucu " + result.length()
                + " karakter — " + LARGEST_RESULT + "'e kisaltildi");
        return result.substring(0, LARGEST_RESULT)
                + "\n\n[... kesildi. Bu sayfa tek bir cevaba sigmayacak kadar buyuk. "
                + "Aradigin sey burada yoksa TUM sayfayi yeniden okuma: metinle arayan araci "
                + "kullan, ya da snapshot'i target/depth ile daralt.]";
    }

    /**
     * Characters, not tokens, because that is what we can count here.
     *
     * Sized from the failure: the window is 131072 tokens, roughly half a
     * million characters. Twenty capped snapshots leave the system prompt, the
     * schema and the model's own reasoning comfortable room, where twenty
     * uncapped ones did not fit at all.
     */
    private static final int LARGEST_RESULT = 12_000;

    /**
     * What the model actually asked for, before anything happens to it.
     *
     * Spring AI logs MCP failures, but only the error — never the call that
     * caused it. So a run's log shows "does not match any elements" without
     * saying which element was asked for, and every diagnosis after that is
     * guesswork. Twice already a fix was designed for a failure whose
     * arguments nobody had seen.
     *
     * Arguments only, never results: a browser_snapshot result is the whole
     * page and would bury the line worth reading.
     */
    private void trace(String input) {
        tick(input);
        String arguments = input == null ? "" : input.replaceAll("\\s+", " ").trim();
        if (arguments.length() > 300) {
            arguments = arguments.substring(0, 300) + "…";
        }
        System.out.println("[uptest] explore -> " + getToolDefinition().name() + " " + arguments);
    }

    /**
     * Crosses off a required caption the moment a call mentions it.
     *
     * Counted on the way IN, before any refusal: the question being answered
     * is "did the agent go for this at all", and a call refused for some other
     * reason still shows that it tried.
     */
    private void tick(String input) {
        // Only what happens in the BROWSER counts. Writing the caption into a
        // note_field call is describing the step, not taking it, and would
        // otherwise let the agent tick off a screen it never visited.
        if (pending.isEmpty() || input == null || RECORDING.contains(getToolDefinition().name())) {
            return;
        }
        // The captions keep their original spelling so the report reads the way
        // the analyst wrote it; only the comparison is folded.
        String folded = fold(input);
        pending.removeIf(caption -> folded.contains(fold(caption)));
    }

    /** The reason this call is not being made, or null to let it through. */
    private String refuse(String input) {
        // Before everything else. A closed browser is the operator saying stop,
        // and the one refusal that must not be spent out of a budget or argued
        // with — the next call would simply open a new window.
        if (aborted.get()) {
            return closed();
        }
        // Checked first, and without spending budget: once the catalog is in,
        // nothing the agent does next can improve it, and everything it does
        // keeps the analyst's request — and their disabled button — waiting.
        if (sink != null && sink.isClosed()) {
            System.out.println("[uptest] explore: katalog gonderildi, sonrasinda arac cagrisi "
                    + "yapildi — reddedildi");
            return FINISHED;
        }
        String banned = banned(input);
        if (banned != null) {
            System.out.println("[uptest] explore: yasakli kontrol '" + banned + "' — reddedildi");
            return FORBIDDEN.formatted(banned);
        }
        String stranger = unmentioned(input);
        if (stranger != null) {
            System.out.println("[uptest] explore: talimatta gecmeyen alan '" + stranger
                    + "' — reddedildi");
            return UNMENTIONED.formatted(stranger);
        }
        // A required step still undone makes every recording premature: told to
        // switch lists and silently not doing it, the agent went on to record
        // the right row of the wrong list, and nothing anywhere said so.
        if (!pending.isEmpty() && RECORDING.contains(getToolDefinition().name())) {
            System.out.println("[uptest] explore: zorunlu adim atlanmis (" + String.join(", ", pending)
                    + ") — kayit reddedildi");
            return SKIPPED_STEP.formatted(String.join(", ", pending));
        }
        if (isRepeatNavigation(input)) {
            System.out.println("[uptest] explore: ayni sayfaya ikinci kez gidiliyor, akis bastan "
                    + "baslatilmak uzere — reddedildi");
            return ALREADY_THERE;
        }
        if (remaining.decrementAndGet() < 0) {
            return EXHAUSTED;
        }
        return null;
    }

    /**
     * The off-limits control this call is aiming at, or null.
     *
     * The whole argument text is searched rather than one named field,
     * because the model describes what it is acting on in different keys from
     * one tool to the next ("element", "selector", "text") and a guard that
     * only reads one of them is a guard with a door left open. Nothing but
     * arguments is ever inspected, so a page that merely MENTIONS the caption
     * cannot trip it.
     */
    private String banned(String input) {
        if (forbidden.isEmpty() || input == null) {
            return null;
        }
        String folded = fold(input);
        for (String caption : forbidden) {
            if (folded.contains(caption)) {
                return caption;
            }
        }
        return null;
    }

    /**
     * A field of this form that the instruction never mentions, or null.
     *
     * The two halves each come from somewhere that cannot be faked. What
     * counts as a FIELD comes from the agent's own note_field calls, so a
     * calendar day cell and a dropdown option — which are not fields — are
     * never caught by this. What counts as MENTIONED comes from the analyst's
     * text, which is the whole definition of what this test is about.
     *
     * note_field itself is exempt: recording a field is reading, and reading
     * every field is the point of the walk. Only acting on one is refused.
     */
    private String unmentioned(String input) {
        // Every recording tool is exempt, not just note_field: reading and
        // writing down a field is the point of the walk, and note_confirmation
        // in particular carries a list of labels that would otherwise refuse
        // itself. Only ACTING on an unmentioned field is refused.
        if (sink == null || input == null || instruction.isEmpty()
                || RECORDING.contains(getToolDefinition().name())) {
            return null;
        }
        String folded = fold(input);
        for (String label : sink.labels()) {
            // Trailing punctuation is part of the label as the screen prints
            // it ("Fatura numarası eklemek istiyorum.") but rarely part of how
            // the agent names what it is acting on.
            String needle = fold(label).replaceAll("[.:,;]+$", "");
            if (needle.length() >= SHORTEST_LABEL
                    && folded.contains(needle) && !instruction.contains(needle)) {
                if (opensToBeRead(label)) {
                    continue;
                }
                return label;
            }
        }
        return null;
    }

    /**
     * A click that OPENS a list rather than changing anything.
     *
     * On this application a closed dropdown holds no options: the list is
     * built when it is clicked. So cataloguing a dropdown the analyst never
     * mentioned requires opening it, and refusing that leaves the catalog with
     * a field a scenario cannot use. Opening changes nothing; CHOOSING does,
     * and choosing clicks the option, which is not a noted field and so never
     * reaches this check.
     *
     * Narrow deliberately. A checkbox the instruction does not name is also
     * "just a click", and ticking it changes what the transaction is — which
     * is the failure this whole guard exists for.
     */
    private boolean opensToBeRead(String label) {
        if (!CLICK.equals(getToolDefinition().name())) {
            return false;
        }
        String type = sink.typeOf(label);
        return "dropdown".equals(type) || "account_picker".equals(type);
    }

    /** The tool that opens a dropdown — and also the one that ticks a checkbox. */
    private static final String CLICK = "browser_click";

    /**
     * Any navigation after the first one.
     *
     * It used to refuse only a REPEAT of a URL already visited, and that let
     * the worst case straight through: the operator closes the browser window,
     * the agent's next call is a navigation to a DIFFERENT address, and the
     * MCP server quietly opens a brand new browser and reports success. There
     * is no error to notice — the run carries on in a window nobody asked for,
     * at about:blank, logged out.
     *
     * One navigation is all a healthy run makes. Every log of a flow that
     * worked opens the application once and reaches everything else through
     * the menus; a second navigation has always been either starting over or
     * flailing after something went wrong. So the rule is the count, not the
     * address, and closing the window now really does end the run: the tools
     * that need a page fail and abort it, and the one tool that could conjure
     * a new page is refused.
     */
    private boolean isRepeatNavigation(String input) {
        if (!NAVIGATE.equals(getToolDefinition().name()) || input == null) {
            return false;
        }
        // The decision is made BEFORE recording, because a set would swallow a
        // second navigation to the SAME address and let exactly the repeat
        // this started out refusing slip through.
        boolean isFirst = visited.isEmpty();
        Matcher url = URL.matcher(input);
        visited.add(url.find() ? url.group(1) : NAVIGATE);
        return !isFirst;
    }
}
