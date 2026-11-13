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
        if (verdict == null || !verdict.isClosed() || verdict.expected().isEmpty() || !verdict.answeredByModel()) {
            return;
        }
        List<String> pages = pages(calls);
        if (pages.isEmpty()) {
            return;
        }
        String last = pages.get(pages.size() - 1);
        List<String> earlier = pages.subList(0, pages.size() - 1);
        for (StepExpectations.Expectation e : verdict.expected()) {
            List<String> absent = nowhere(e.text(), pages);
            if (!absent.isEmpty()) {
                System.out.println("[uptest] explore: B" + e.number() + " gorulmus olamaz — " + absent
                        + " bu kosuda hicbir ekranda yoktu");
                verdict.unseen(e.number(), String.join(", ", absent.stream().map(n -> "\"" + n + "\"").toList())
                        + (absent.size() == 1 ? " adı" : " adları") + " bu koşuda hiçbir ekranda geçmedi."
                        + " Adı ekranın yazdığı gibi yazın.");
                continue;
            }
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
            String[] words = part.strip().split("\\s+");
            if (words.length == 0 || words[0].isEmpty() || !capital(words[0])) {
                continue;
            }
            // Up to the last capitalised word before the closing run of lower-case
            // ones: "Fatura Dönemi alanları salt okunur görünür" → "Fatura Dönemi",
            // but "Yeni Son banko Tarihi" stays whole — a lower-case word INSIDE
            // a name is part of what the analyst wrote, and may be the mistake.
            int last = 0;
            for (int i = 0; i < words.length; i++) {
                if (capital(words[i])) {
                    last = i;
                }
            }
            if (last < MAX_WORDS) {
                List<String> name = new ArrayList<>();
                for (int i = 0; i <= last; i++) {
                    name.add(words[i].replaceAll("[.:;]+$", ""));
                }
                out.add(String.join(" ", name));
            }
        }
        return new ArrayList<>(out);
    }

    /** Longer than this and a "name" is a sentence. */
    private static final int MAX_WORDS = 6;

    private static boolean capital(String word) {
        return !word.isEmpty() && Character.isUpperCase(word.codePointAt(0));
    }

    /**
     * The names of {@code expectation} that NO page of the walk showed. Such
     * an expectation cannot have been seen, whatever the model answered.
     *
     * <p><b>Measured 13.11.2026</b>: "Güncelleme formunda yeni değer alanları
     * bulunur". The analyst listed "Yeni Son banko Tarihi" on purpose — the
     * screen says "Yeni Son Ödeme Tarihi" — and the model answered "görüldü",
     * its evidence a word-for-word copy of the expectation.
     */
    static List<String> nowhere(String expectation, List<String> pages) {
        List<String> out = new ArrayList<>();
        String folded = TapeRenderer.fold(expectation);
        if (pages.isEmpty() || ABSENCE.stream().anyMatch(folded::contains)) {
            return out;
        }
        // Only names the analyst marked as names: quoted, or a list of FIELDS.
        // A free sentence ("Hata mesajı görünür: İşlem yapılamadı") cut into
        // capitalised runs yields "names" no page carries, and failing a run
        // over them would be the code inventing a mistake.
        if (StepExpectations.quotedIn(expectation).isEmpty() && !folded.contains("alan")) {
            return out;
        }
        List<String> compacted = pages.stream().map(ClaimCheck::compact).toList();
        for (String name : names(expectation)) {
            String needle = ClaimCheck.compact(name).replaceAll("[.!?:;]+$", "");
            if (needle.length() >= 3 && compacted.stream().noneMatch(page -> page.contains(needle))) {
                out.add(name);
            }
        }
        return out;
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
