package orchestrator.explore;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A scenario step written the way the writing guide asks, read by CODE into
 * the action do_steps performs — no model call.
 *
 * <p><b>Why.</b> Measured 13.11.2026, tape 4bc5c9f0: a 17-step "fatura
 * oluştur" took 32 turns and 192.735 tokens and did not finish, one tool call
 * per turn, although every step was a plain "select X from Y" / "type V into
 * L". The prompt told the model to batch them with do_steps; it did not. The
 * analyst asked whether our own Java code could do such steps. It can: these
 * sentences are a closed grammar, and {@link StepRunner} already performs each
 * action as {@code CommonActions} does — Select2 opened, searched, read back;
 * the calendar through set_date; an amount as two boxes.
 *
 * <p>The grammar (folded, quoted names as in the guide):
 * <pre>
 * "L" alanına "gg/aa/yyyy" tarihini gir      → date L = value
 * "L" listesinden "V" seç                    → select L = V
 * … "V" olan ilk satırı seç                  → row V
 * … arama kutusuna "V" yaz                   → search V (the focused box)
 * "L" alanına / kutusuna "V" yaz | gir       → type L = V (an amount box splits it)
 * … "N" bağlantısına / düğmesine tıkla | bas → click N
 * … "N" seçeneğini / kutusunu işaretle       → click N
 * </pre>
 * Anything else is not guessed: the steps are read in order and reading stops
 * at the first one that does not fit, so the agent takes over exactly there.
 */
final class CodeSteps {

    /** What was read: the steps code can do, in order, and the first it cannot read. */
    record Parsed(List<StepRunner.Planned> steps, int total, int firstUnread, String unreadText) {

        /** Every step of the scenario is in {@link #steps}. */
        boolean all() {
            return firstUnread == 0 && !steps.isEmpty() && steps.size() == total;
        }
    }

    private static final Pattern DATE = Pattern.compile("^\\d{1,2}[./-]\\d{1,2}[./-]\\d{4}$");

    private static final Pattern SELECT_VERB = Pattern.compile("\\bsec(?:in|iniz)?\\b");
    private static final Pattern TYPE_VERB = Pattern.compile("\\b(?:yaz(?:in|iniz)?|gir(?:in|iniz)?)\\b");
    private static final Pattern CLICK_VERB =
            Pattern.compile("\\b(?:tikla(?:yin|yiniz)?|bas(?:in|iniz)?|isaretle(?:yin|yiniz)?)\\b");

    private CodeSteps() {
    }

    static Parsed parse(String scenario) {
        List<StepExpectations.Step> steps = StepExpectations.parse(scenario);
        List<StepRunner.Planned> out = new ArrayList<>();
        for (StepExpectations.Step step : steps) {
            StepRunner.Step action = read(step.text());
            if (action == null) {
                return new Parsed(out, steps.size(), step.number(), step.text());
            }
            out.add(new StepRunner.Planned(step.number(), action, step.expected()));
        }
        return new Parsed(out, steps.size(), 0, null);
    }

    /** The action one step asks for, or null when it does not fit the grammar. */
    static StepRunner.Step read(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String folded = TapeRenderer.fold(text);
        List<String> quoted = StepExpectations.quotedIn(text);
        if (quoted.isEmpty()) {
            return null;
        }
        String last = quoted.get(quoted.size() - 1);

        if (folded.contains("tarih") && quoted.size() >= 2 && DATE.matcher(last.strip()).matches()) {
            return new StepRunner.Step("date", quoted.get(0), last.strip());
        }
        boolean fromList = folded.contains("listesinden") || folded.contains("listeden")
                || folded.contains("alanindan") || folded.contains("kutusundan");
        if (fromList && quoted.size() >= 2 && SELECT_VERB.matcher(folded).find()) {
            return new StepRunner.Step("select", quoted.get(0), quoted.get(1));
        }
        if (folded.contains("satir") && SELECT_VERB.matcher(folded).find()) {
            return new StepRunner.Step("row", last, null);
        }
        if (folded.contains("arama kutusu") && TYPE_VERB.matcher(folded).find()) {
            return new StepRunner.Step("search", last, last);
        }
        boolean intoBox = folded.contains("alanina") || folded.contains("kutusuna");
        if (intoBox && quoted.size() >= 2 && TYPE_VERB.matcher(folded).find()) {
            return new StepRunner.Step("type", quoted.get(0), quoted.get(1));
        }
        if (CLICK_VERB.matcher(folded).find()) {
            return new StepRunner.Step("click", last, null);
        }
        return null;
    }

    /**
     * What the agent is told when code stopped: which steps are done (and must
     * not be repeated), where it stopped and why, and the steps left.
     */
    static String handover(Parsed parsed, FastReplay.Result run) {
        int from = run != null && run.stoppedAt() > 0 ? run.stoppedAt() : parsed.firstUnread();
        StringBuilder out = new StringBuilder("\n\nKOD İLE YAPILAN ADIMLAR — bunlar YAPILDI, tekrarlama:\n");
        if (run == null || run.done().isEmpty()) {
            out.append("(hiçbiri)\n");
        } else {
            run.done().forEach(line -> out.append("✓ ").append(line).append('\n'));
        }
        if (run != null && run.stoppedAt() > 0) {
            out.append("Kod ").append(run.stoppedAt()).append(". adımı yapamadı: ").append(run.reason()).append('\n');
        } else if (parsed.firstUnread() > 0) {
            out.append("Kod ").append(parsed.firstUnread()).append(". adımı okuyamadı (\"")
                    .append(parsed.unreadText()).append("\")\n");
        }
        out.append("Sen ").append(from).append(". adımdan devam et; aşağıdaki sayfa ŞU ANKİ sayfadır. "
                + "report_result'ta kodla yapılan adımları da succeeded'e yaz.\n");
        return out.toString();
    }
}
