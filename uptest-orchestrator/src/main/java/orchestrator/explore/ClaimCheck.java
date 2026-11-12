package orchestrator.explore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Holds the agent's "it worked" against what the tape says it did.
 *
 * <p><b>Measured 12.11.2026</b>, "İptal ekranından Geri ile listeye dönülür":
 * four steps, the last two being "Fatura İptal Et düğmesine tıkla" and "Geri
 * düğmesine tıkla". The agent made six moves — two to pick the firm, a look
 * at the grid, three more clicks — and then reported every step done,
 * "Geri düğmesine tıklandı ve liste ekranına dönüldü" among them, with
 * {@code failed: []}. The analyst watched the browser: a second invoice was
 * picked and the window closed. {@link RunVerdict} only overrides a claim
 * when the agent itself lists a failure, so an agent that simply SAYS it did
 * something got a green result.
 *
 * <p>The check is deliberately narrow, because it decides a verdict: a step
 * that tells the agent to press a NAMED control ({@code "Geri" düğmesine
 * tıkla}) must have a click on that name on the tape. Whether the click is on
 * the tape is a fact, not a judgement — the tape holds the Playwright line the
 * server actually ran, and the renderer resolves each reference against the
 * page the agent was looking at. A step whose name it cannot read is left
 * alone rather than guessed at.
 *
 * <p>What it does NOT check: that the screen then SHOWED what BEKLENEN says.
 * That needs reading values off a page, which is the assertion this walk
 * does not make; this only catches the claim that has no action behind it.
 */
final class ClaimCheck {

    /** A numbered step line, as {@code docAsText} writes it ("3. …"). */
    private static final Pattern STEP = Pattern.compile("^\\s*\\d+[.)]\\s+(.*)$");

    /**
     * The verb of a press, folded: tıkla / tıklayın / bas / basın. Not the
     * negatives — "tıklama", "basma" are a step saying what NOT to press, and
     * reading them as a press would demand the very click it forbids.
     */
    private static final Pattern PRESS = Pattern.compile("\\b(tikla(?:yin|yiniz)?|bas(?:in|iniz)?)\\b");

    /** A name in straight or typographic double quotes. */
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"|“([^”]+)”");

    /** The accessible name in an echoed Playwright line: {@code name: 'Geri'} or {@code getByText('Geri')}. */
    private static final Pattern CODE_NAME =
            Pattern.compile("(?:name:\\s*|getByText\\(\\s*)'((?:[^'\\\\]|\\\\.)*)'");

    /** A rendered click: {@code click "Geri"}. */
    private static final Pattern RENDERED_CLICK = Pattern.compile("^click \"(.*)\"$");

    private ClaimCheck() {
    }

    /**
     * Marks the verdict failed for every named press the tape does not show.
     * Does nothing when there is no verdict, so Serbest Gözlem is untouched.
     */
    static void apply(RunVerdict verdict, String instruction, List<TapeRenderer.Call> calls,
                      List<String> renderedSteps) {
        if (verdict == null || !verdict.isClosed()) {
            return;
        }
        for (String caption : unclicked(instruction, calls, renderedSteps)) {
            System.out.println("[uptest] explore: ajan '" + caption + "' adimini yapildi dedi ama bantta"
                    + " bu tiklama yok — sonuc GECMEDI'ye cevrildi");
            verdict.refute(caption, "\"" + caption + "\" düğmesine tıklanmadı: senaryo bunu istiyor,"
                    + " ama ajanın yaptığı işlemler arasında bu tıklama yok. Ajan bu adımı"
                    + " yapıldı olarak bildirdi; bildirimi kod tarafından düzeltildi.");
        }
    }

    /** The named presses the scenario asks for that no successful click on the tape carries. */
    static List<String> unclicked(String instruction, List<TapeRenderer.Call> calls,
                                  List<String> renderedSteps) {
        Set<String> clicked = clickedNames(calls, renderedSteps);
        List<String> missing = new ArrayList<>();
        for (String wanted : pressesAskedFor(instruction)) {
            String needle = compact(wanted);
            if (needle.isEmpty()) {
                continue;
            }
            boolean seen = clicked.stream().anyMatch(name -> name.contains(needle));
            if (!seen) {
                missing.add(wanted);
            }
        }
        return missing;
    }

    /**
     * For each numbered step that presses something, the LAST quoted name
     * before the verb. The last, because a step can quote a value as well —
     * {@code "Ödenecek" olan satırdaki "Detay"a tıkla} presses Detay, not
     * Ödenecek — and a name the step only mentions must not be demanded.
     */
    static List<String> pressesAskedFor(String instruction) {
        Set<String> out = new LinkedHashSet<>();
        if (instruction == null) {
            return List.of();
        }
        for (String line : instruction.split("\\R")) {
            Matcher step = STEP.matcher(line);
            if (!step.matches()) {
                continue;
            }
            String text = step.group(1);
            String folded = TapeRenderer.fold(text);
            Matcher verb = PRESS.matcher(folded);
            if (!verb.find()) {
                continue;
            }
            // fold() maps letter for letter and collapses spaces, so the verb's
            // position in the folded text is its position in the original once
            // the original's spaces are collapsed the same way.
            String collapsed = text.replaceAll("\\s+", " ").trim();
            int verbAt = Math.min(verb.start(), collapsed.length());
            String last = null;
            Matcher quoted = QUOTED.matcher(collapsed);
            while (quoted.find() && quoted.start() < verbAt) {
                last = quoted.group(1) != null ? quoted.group(1) : quoted.group(2);
            }
            if (last != null && !last.isBlank()) {
                out.add(last.strip());
            }
        }
        return new ArrayList<>(out);
    }

    /** Every name a successful click carried, compacted for comparison. */
    private static Set<String> clickedNames(List<TapeRenderer.Call> calls, List<String> renderedSteps) {
        Set<String> names = new LinkedHashSet<>();
        if (renderedSteps != null) {
            for (String step : renderedSteps) {
                Matcher click = RENDERED_CLICK.matcher(step == null ? "" : step.strip());
                if (click.matches()) {
                    names.add(compact(click.group(1)));
                }
            }
        }
        if (calls != null) {
            for (TapeRenderer.Call call : calls) {
                if (!"browser_click".equals(call.tool()) || !"OK".equals(call.outcome())) {
                    continue;
                }
                Matcher name = CODE_NAME.matcher(TapeEvidence.code(call.result()));
                while (name.find()) {
                    names.add(compact(name.group(1).replace("\\'", "'")));
                }
                String element = TapeEvidence.arg(call, "element");
                if (!element.isBlank()) {
                    names.add(compact(element));
                }
            }
        }
        names.remove("");
        return names;
    }

    /**
     * Folded with every space removed: a {@code <br>} label reads run-together
     * on the page and spaced in the analyst's text (see the CLAUDE.md lesson).
     */
    static String compact(String value) {
        return TapeRenderer.fold(value).replace(" ", "");
    }
}
