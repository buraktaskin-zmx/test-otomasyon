package orchestrator.explore;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static orchestrator.explore.TapeRenderer.fold;

/**
 * The THEN of a rendered walk, built from the analyst's BEKLENEN lines and
 * checked against what the walk's last screen showed; and the write verb a
 * value's shape asks for.
 *
 * Split out of {@link TapeRenderer} unchanged.
 */
final class ExpectedVerifies {

    private ExpectedVerifies() {
    }

    /**
     * The THEN, built from what the ANALYST expects — never from what the
     * screen showed.
     *
     * The distinction is the whole of it. The agent does not assert: it reads
     * a snapshot and describes it, so a verify derived from the walk would say
     * the application is right because the application said so, and a defect
     * would be written into the suite as a passing test. The expectation is
     * the one input that can contradict the screen, and it is the analyst's.
     *
     * What the tape contributes is the other half: whether the label exists on
     * the screen the walk ENDED on. A verify naming a field that was never
     * shown spends its full timeout and blames the wrong thing, and that is
     * knowable here, before anyone runs it.
     *
     * The two are then compared. When they disagree the analyst's value is
     * used and the difference is reported — because a disagreement means
     * either the expectation is stale or the application is wrong, and those
     * need opposite fixes.
     */
    static void appendVerifies(List<String> expectations, TapeScope scope,
                                       List<String> steps, List<String> notes) {
        if (expectations == null || expectations.isEmpty()) {
            notes.add("Senaryonun BEKLENEN alanı boş, doğrulama satırı üretilmedi —"
                    + " bir THEN'i olmayan senaryo test değildir.");
            return;
        }
        Map<String, String> onScreen = scope.labelledValues();
        for (String expectation : expectations) {
            if (expectation == null || expectation.isBlank()) {
                continue;
            }
            String label = longestLabelIn(expectation, onScreen.keySet());
            if (label == null) {
                notes.add("\"" + expectation.trim() + "\" bir doğrulama satırına çevrilemedi:"
                        + " ajanın son gördüğü ekranda bu adda bir alan yok. Satırı elle yazın.");
                continue;
            }
            String value = valueIn(expectation, label);
            if (value.isBlank()) {
                notes.add("\"" + expectation.trim() + "\" içinde '" + label
                        + "' alanı bulundu ama beklenen değer okunamadı. Satırı elle yazın.");
                continue;
            }
            steps.add(verifyFor(label, value));

            String shown = onScreen.getOrDefault(label, "");
            if (!shown.isBlank() && !fold(shown).equals(fold(value))) {
                notes.add("'" + label + "' için beklediğiniz değer \"" + value
                        + "\", ajanın gördüğü \"" + shown + "\". Sizinki yazıldı —"
                        + " ya beklenti bayat ya uygulama hatalı, ve ikisi zıt düzeltme ister.");
            }
        }
    }

    /** gg/aa/yyyy, which {@code enter date} and its verify take as meaning. */
    private static final Pattern DATE = Pattern.compile("^\\d{1,2}[./-]\\d{1,2}[./-]\\d{4}$");

    /** 1.925,96 with an optional currency, which the amount verify normalises. */
    private static final Pattern AMOUNT =
            Pattern.compile("^[0-9][0-9.]*,[0-9]{2}(\\s*[A-Z]{2,3})?$");

    /**
     * The write verb the value's shape asks for.
     *
     * A date written with plain {@code enter} is the mistake DateVerb exists
     * to rewrite at generation time: {@code field equals} compares exactly, so
     * a literal date passes into a field that resolves it and the assertion
     * hunts for text the screen never shows. Here the shape is known, so the
     * right verb is written in the first place.
     *
     * Amounts are deliberately NOT guessed: this application's amount field is
     * two inputs, lira and kuruş, so a single recorded fill is only half of
     * one and a step built from it would set the wrong number.
     */
    static String enterFor(String label, String value) {
        if (DATE.matcher(value).matches()) {
            return "enter date \"" + label + "\" \"" + value + "\"";
        }
        return "enter \"" + label + "\" \"" + value + "\"";
    }

    /** The verb the value's own shape asks for, as the type table does. */
    private static String verifyFor(String label, String value) {
        if (DATE.matcher(value).matches()) {
            return "verify the \"" + label + "\" has date \"" + value + "\"";
        }
        if (AMOUNT.matcher(value).matches()) {
            return "verify the \"" + label + "\" has amount \"" + value + "\"";
        }
        return "verify the \"" + label + "\" field equals \"" + value + "\"";
    }

    /**
     * The longest on-screen label the expectation names.
     *
     * Longest, because the short one is inside the long one on these screens:
     * an expectation about "Orijinal Fatura Tutarı" also contains "Fatura
     * Tutarı", and the shorter match would verify a different row of the same
     * dialog.
     */
    private static String longestLabelIn(String expectation, java.util.Set<String> labels) {
        String folded = fold(expectation);
        String best = null;
        for (String label : labels) {
            if (folded.contains(fold(label)) && (best == null || label.length() > best.length())) {
                best = label;
            }
        }
        return best;
    }

    /**
     * What the analyst wrote after the label, with the sentence's tail off.
     *
     * "Bayi Kodu 151732 oldugunu doğrula" is a sentence, not a field and a
     * value, so the value is whatever sits between the label and the first
     * word that is plainly part of the asking. The list is short and only
     * grows when a real expectation is not read — a longer list would start
     * eating values.
     */
    private static final List<String> TAIL = List.of(
            "oldugunu", "olduğunu", "olmali", "olmalı", "dogrula", "doğrula",
            "dogrulanmali", "doğrulanmalı", "gorunmeli", "görünmeli", "gosterilmeli",
            "gösterilmeli", "yazmali", "yazmalı", "olarak", "seklinde", "şeklinde");

    private static String valueIn(String expectation, String label) {
        String folded = fold(expectation);
        int at = folded.indexOf(fold(label));
        String rest = expectation.substring(Math.min(expectation.length(), at + label.length())).trim();

        int cut = rest.length();
        for (String tail : TAIL) {
            Matcher word = Pattern.compile("(?i)(^|\\s)" + Pattern.quote(tail) + "(\\s|$|\\.)")
                    .matcher(fold(rest));
            if (word.find()) {
                cut = Math.min(cut, word.start(1) == word.start() ? word.start() : word.start() + 1);
            }
        }
        return rest.substring(0, Math.max(0, Math.min(cut, rest.length()))).trim()
                .replaceAll("^[:\\-–—\\s]+", "").replaceAll("[.,;\\s]+$", "").trim();
    }
}
