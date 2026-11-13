package orchestrator.explore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Catches a final Beklenen that is about a screen the walk has already left.
 *
 * <p><b>Measured 12.11.2026, tape bd40c3ee.</b> The scenario opened "Fatura
 * Güncelle", pressed "Geri", and its final Beklenen said "Kurum Adı, Bayi
 * Kodu, Bayi Ünvanı, … alanları salt okunur görünür". The final Beklenen is
 * judged on the LAST page, and the last page was the invoice list: Kurum Adı,
 * Bayi Ünvanı, Para Birimi were not on it — they were on the update screen
 * two pages earlier. The agent reported the run green; an earlier attempt of
 * the same scenario even wrote "salt okunur göründü", from memory of a page
 * it no longer had.
 *
 * <p>Whether a name is on a page is a fact the tape holds, so code decides it:
 * a final expectation that names at least {@value #AT_LEAST} fields which are
 * missing from the last page but were on an earlier page of the same walk is
 * failed, with how to write it instead (under the step that opens that screen).
 *
 * <p>Deliberately narrow, because it decides a verdict:
 * <ul>
 *   <li>only names that WERE seen earlier count — a name absent from every
 *       page says nothing about where the expectation belongs;
 *   <li>an expectation that says something is GONE ("kapanır", "görünmez",
 *       "yok") is skipped: its names are absent from the last page on purpose.
 * </ul>
 */
final class ExpectationCheck {

    /** Fewer than this and one stray word could fail a run. */
    static final int AT_LEAST = 2;

    /** Folded stems of an expectation that something is absent. */
    private static final List<String> ABSENCE = List.of(
            "gorunmez", "gorunmemeli", "kapan", "kaybol", "yok", "gizli", "gizlen", "olmaz", "silin",
            "degil", "cikmaz", "listelenmez");

    private ExpectationCheck() {
    }

    /** Marks every misplaced final expectation on the verdict. Does nothing without one. */
    static void apply(RunVerdict verdict, List<TapeRenderer.Call> calls) {
        if (verdict == null || !verdict.isClosed() || verdict.expected().isEmpty()) {
            return;
        }
        List<String> pages = pages(calls);
        if (pages.size() < 2) {
            return;
        }
        String last = pages.get(pages.size() - 1);
        List<String> earlier = pages.subList(0, pages.size() - 1);
        for (StepExpectations.Expectation e : verdict.expected()) {
            if (e.afterStep() != 0) {
                continue;
            }
            List<String> left = leftBehind(e.text(), last, earlier);
            if (left.size() >= AT_LEAST) {
                System.out.println("[uptest] explore: B" + e.number() + " son ekranda kontrol edilemez — "
                        + left + " son ekranda yok, onceki bir ekrandaydi");
                verdict.misplaced(e.number(), "Adı geçen " + quotedList(left) + " son ekranda yok; daha önceki"
                        + " bir ekrandaydı. Son Beklenen son ekranda kontrol edilir: bu satırı o ekranı açan"
                        + " adımın hemen altına \"Beklenen:\" ile yazın.");
            }
        }
    }

    /** The names of {@code expectation} missing from {@code last} that an earlier page did show. */
    static List<String> leftBehind(String expectation, String last, List<String> earlier) {
        List<String> out = new ArrayList<>();
        String folded = TapeRenderer.fold(expectation);
        if (ABSENCE.stream().anyMatch(folded::contains)) {
            return out;
        }
        String lastPage = ClaimCheck.compact(last);
        for (String name : names(expectation)) {
            String needle = ClaimCheck.compact(name);
            if (needle.length() < 3 || lastPage.contains(needle)) {
                continue;
            }
            if (earlier.stream().map(ClaimCheck::compact).anyMatch(page -> page.contains(needle))) {
                out.add(name);
            }
        }
        return out;
    }

    /**
     * The field names an expectation mentions: its quoted names when it has
     * any; otherwise each item of a list written "A, B ve C alanları …" — the
     * run of capitalised words at the start of each comma or "ve" part,
     * stopping at the first lower-case word ("alanları").
     */
    static List<String> names(String expectation) {
        Set<String> out = new LinkedHashSet<>(StepExpectations.quotedIn(expectation));
        if (!out.isEmpty() || expectation == null) {
            return new ArrayList<>(out);
        }
        for (String part : expectation.split(",|\\s+ve\\s+")) {
            List<String> words = new ArrayList<>();
            for (String word : part.strip().split("\\s+")) {
                if (word.isEmpty() || !Character.isUpperCase(word.codePointAt(0))) {
                    break;
                }
                words.add(word.replaceAll("[.:;]+$", ""));
            }
            if (!words.isEmpty() && words.size() <= 4) {
                out.add(String.join(" ", words));
            }
        }
        return new ArrayList<>(out);
    }

    /** Every page the walk was shown, in order: the snapshots on the tape. */
    private static List<String> pages(List<TapeRenderer.Call> calls) {
        List<String> out = new ArrayList<>();
        if (calls == null) {
            return out;
        }
        for (TapeRenderer.Call call : calls) {
            if ("browser_snapshot".equals(call.tool()) && "OK".equals(call.outcome())
                    && call.result() != null && !call.result().isBlank()) {
                out.add(LeanResults.unwrap(call.result()));
            }
        }
        return out;
    }

    private static String quotedList(List<String> names) {
        List<String> quoted = names.stream().map(name -> "\"" + name + "\"").toList();
        return quoted.size() == 1 ? quoted.get(0) + " alanı" : String.join(", ", quoted) + " alanları";
    }
}
