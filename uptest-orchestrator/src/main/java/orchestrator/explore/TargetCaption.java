package orchestrator.explore;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a snapshot reference a tool call aims at SAYS. Moved out of
 * BudgetedTool (which was already past the ~800-line rule) when it was
 * written; BudgetedTool's commit guard is its only caller.
 */
final class TargetCaption {

    /** {@code "target": "f3e703"} or {@code "ref": "e42"} — the snapshot reference a call aims at. */
    private static final Pattern TARGET_REF =
            Pattern.compile("\"(?:target|ref)\"\\s*:\\s*\"([A-Za-z0-9]+)\"");

    private TargetCaption() {
    }

    /**
     * What the element a reference points at SAYS, read off the latest page
     * on the tape that carries that reference.
     *
     * <p><b>Measured 12.11.2026</b> (tape 2628c4fd): on "AI ile çalıştır"
     * every click arrives as {@code {"target": "f3e703"}} and nothing else —
     * no description, no word. The guard searched the ARGUMENTS for a
     * committing word, so on this walk it could not see one at all: a click
     * on the cancel screen's "Evet" would have gone straight through. The
     * meaning of a reference lives in the page the model was looking at, and
     * that page is on the tape (AiRunPolicy records it after every action).
     *
     * <p>Blank when the reference is not on any recorded page — the guard then
     * falls back to the arguments, which is what it always did.
     */
    static String of(ActionTape tape, String input) {
        if (tape == null || input == null) {
            return "";
        }
        Matcher ref = TARGET_REF.matcher(input);
        if (!ref.find()) {
            return "";
        }
        List<TapeRenderer.Call> calls = tape.calls();
        for (int i = calls.size() - 1; i >= 0; i--) {
            String result = calls.get(i).result();
            if (result == null || !result.contains("[ref=" + ref.group(1) + "]")) {
                continue;
            }
            AriaSnapshot page = AriaSnapshot.of(result);
            var node = page.node(ref.group(1));
            if (node.isEmpty()) {
                continue;
            }
            String caption = node.get().caption();
            if (caption.isBlank()) {
                caption = page.childrenOf(ref.group(1)).stream()
                        .map(AriaSnapshot.Node::caption)
                        .filter(text -> !text.isBlank())
                        .collect(java.util.stream.Collectors.joining(" "));
            }
            return caption;
        }
        return "";
    }

    /**
     * A committing word as a WORD of the element's caption, not a fragment of
     * one: the caption is the page's own text, where "sil" sits inside
     * "Silinmiş Faturalar" and "gönder" inside "Gönderilenler". The arguments
     * are matched loosely as before, because there the word is the model's own
     * description of what it is about to press.
     */
    static boolean wholeWord(String text, String word) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return Pattern.compile("(^|[^\\p{L}\\p{N}])" + Pattern.quote(word) + "($|[^\\p{L}\\p{N}])")
                .matcher(text).find();
    }
}
