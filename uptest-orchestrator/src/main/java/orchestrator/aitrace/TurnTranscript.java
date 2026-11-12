package orchestrator.aitrace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One turn as a single readable text: everything that went to the model, in
 * the order the model received it, then everything that came back.
 *
 * <p><b>Why a text and not more JSON.</b> The panel could already show a
 * turn's messages one by one, each behind its own button and its own request,
 * which answers "how big is the system prompt" and not "what did the model
 * actually read". That second question is answered by reading the request top
 * to bottom, and by copying the whole of it somewhere else to compare two
 * turns. So this is one HTTP call, one document, in the model's own order.
 *
 * <p><b>Nothing is cut.</b> A page snapshot is tens of kilobytes and that is
 * the point: a cut transcript cannot answer why the agent did what it did. The
 * browser scrolls it, and the panel offers it as a download.
 *
 * <p><b>Nothing is masked here.</b> Every text passed {@link SecretMask} on
 * its way to disk ({@code TraceSession}), so masking again would be a second
 * rule to keep in step with the first.
 *
 * <p>Reads only what {@link TraceFiles} already stores: the turn document,
 * each text by its sha, and each text's token count. No new storage, so an
 * old session's transcript reads exactly like today's.
 */
final class TurnTranscript {

    /** The Turkish word for each stored role — the same words TurnMessages.tsx prints. */
    private static final Map<String, String> ROLE = Map.of(
            "system", "SYSTEM",
            "user", "KULLANICI",
            "assistant", "MODEL",
            "tool", "TOOL SONUCU");

    private static final Locale TR = Locale.forLanguageTag("tr-TR");

    /** Loud enough not to be mistaken for a line of a page snapshot. */
    private static final String RULE = "################################################################";

    private TurnTranscript() {
    }

    static Optional<String> of(TraceFiles files, UUID id, int seq) {
        return files.turn(id, seq).map(doc -> {
            List<Map<String, Object>> messages = rows(doc.get("messages"));
            StringBuilder out = new StringBuilder();
            header(out, doc, seq, messages.size());

            /* The definitions are printed after the opening system message(s),
               because that is where the chat template puts them and therefore
               where the model reads them. They are not a message of their own:
               they ride in the request's own tools field. */
            int afterSystem = 0;
            while (afterSystem < messages.size()
                    && "system".equals(str(messages.get(afterSystem).get("role")))) {
                afterSystem++;
            }
            for (int i = 0; i < messages.size(); i++) {
                if (i == afterSystem) {
                    defs(out, files, id, doc);
                }
                message(out, files, id, messages.get(i), i + 1);
            }
            if (messages.size() <= afterSystem) {
                defs(out, files, id, doc);
            }

            out.append("\n#################### BU TURDAN ÇIKAN ####################\n");
            answer(out, files, id, doc);
            return out.toString();
        });
    }

    /* ------------------------------ blocks ------------------------------ */

    private static void header(StringBuilder out, Map<String, Object> doc, int seq, int messages) {
        Map<String, Object> usage = map(doc.get("usage"));
        out.append(RULE).append('\n');
        out.append("# TUR ").append(seq);
        appendIf(out, " · ", str(doc.get("startedAt")));
        Long ms = lng(doc.get("durationMs"));
        if (ms != null) {
            out.append(" · ").append(num(ms)).append(" ms");
        }
        appendIf(out, " · model ", str(doc.get("model")));
        out.append('\n');

        out.append("# girdi ").append(num(lng(usage.get("prompt"))))
                .append(" · çıktı ").append(num(lng(usage.get("completion")))).append(" token");
        Long cached = lng(usage.get("cached"));
        if (cached != null) {
            out.append(" · önbellek ").append(num(cached));
        }
        appendIf(out, " · bitiş: ", str(doc.get("finishReason")));
        out.append('\n');

        out.append("# ").append(messages).append(" mesaj");
        Long count = lng(map(doc.get("toolDefs")).get("count"));
        if (count != null) {
            out.append(" · ").append(count).append(" tool tanımı");
        }
        if (Boolean.TRUE.equals(doc.get("vision"))) {
            out.append(" · görsel istek (token sayılamaz)");
        }
        out.append('\n');
        String error = str(doc.get("error"));
        if (error != null) {
            out.append("# HATA: ").append(error).append('\n');
        }
        out.append(RULE).append('\n');
    }

    private static void message(StringBuilder out, TraceFiles files, UUID id,
                                Map<String, Object> message, int index) {
        String role = str(message.get("role"));
        StringBuilder title = new StringBuilder();
        title.append('[').append(index).append("] ")
                .append(ROLE.getOrDefault(role, role == null ? "?" : role.toUpperCase(TR)));
        appendIf(title, ": ", str(message.get("name")));
        section(out, title.toString(), null, files, id, str(message.get("sha")),
                lng(message.get("chars")), Boolean.TRUE.equals(message.get("isNew")));
    }

    private static void defs(StringBuilder out, TraceFiles files, UUID id, Map<String, Object> doc) {
        Map<String, Object> defs = map(doc.get("toolDefs"));
        String sha = str(defs.get("sha"));
        if (!TraceFiles.isSha(sha)) {
            return;
        }
        Long count = lng(defs.get("count"));
        section(out, "TOOL TANIMLARI" + (count == null ? "" : " (" + count + " araç)"),
                "(mesaj değil: isteğin kendi tools alanı — şablon bunu system'den sonra ekler)",
                files, id, sha, lng(defs.get("chars")), false);
    }

    private static void answer(StringBuilder out, TraceFiles files, UUID id, Map<String, Object> doc) {
        Map<String, Object> response = map(doc.get("response"));
        if (TraceFiles.isSha(str(response.get("sha")))) {
            section(out, "MODELİN CEVABI", null, files, id, str(response.get("sha")),
                    lng(response.get("chars")), false);
        } else {
            out.append("\n===== MODELİN CEVABI =====\n")
                    .append("(metin yok — model yalnız tool çağrısı istedi, ya da çağrı hata verdi)\n");
        }

        Map<String, Object> reasoning = map(doc.get("reasoning"));
        if (TraceFiles.isSha(str(reasoning.get("sha")))) {
            section(out, "MODELİN DÜŞÜNCESİ (reasoning)", null, files, id,
                    str(reasoning.get("sha")), lng(reasoning.get("chars")), false);
        }

        List<Map<String, Object>> calls = rows(doc.get("toolCalls"));
        out.append("\n===== İSTEDİĞİ TOOL ÇAĞRILARI (").append(calls.size()).append(") =====\n");
        if (calls.isEmpty()) {
            out.append("(yok)\n");
        }
        for (Map<String, Object> call : calls) {
            String arguments = str(call.get("arguments"));
            out.append(str(call.get("name")));
            if (arguments != null) {
                out.append(' ').append(arguments);
            }
            out.append('\n');
        }
    }

    /** One labelled block: a heading carrying the sizes, an optional note, then the text whole. */
    private static void section(StringBuilder out, String title, String note, TraceFiles files,
                                UUID id, String sha, Long chars, boolean isNew) {
        out.append("\n===== ").append(title);
        if (chars != null) {
            out.append(" · ").append(num(chars)).append(" karakter");
        }
        files.count(id, sha).ifPresent(count -> out.append(" · ").append(num((long) count.tokens()))
                .append(" token").append(count.exact() ? "" : " (tahmin)"));
        if (isNew) {
            out.append(" · YENİ");
        }
        out.append(" =====\n");
        if (note != null) {
            out.append(note).append('\n');
        }
        out.append(text(files, id, sha)).append('\n');
    }

    /** The stored text, whole; a missing one says so rather than leaving a gap. */
    private static String text(TraceFiles files, UUID id, String sha) {
        if (!TraceFiles.isSha(sha)) {
            return "(bu parçanın metni kaydedilmemiş)";
        }
        return files.read(id, sha).orElse("(metin diskte yok: " + sha.substring(0, 8) + ")");
    }

    /* ------------------------------ reading ----------------------------- */

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> entry) {
                    out.add((Map<String, Object>) entry);
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> entry ? (Map<String, Object>) entry : new LinkedHashMap<>();
    }

    /** A JSON null arrives as the string "null" from one writer and as null from another. */
    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isEmpty() || "null".equals(text) ? null : text;
    }

    private static Long lng(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    /** 24.312 — tr-TR thousands, the way the panel prints every other number. */
    private static String num(Long value) {
        return value == null ? "—" : String.format(TR, "%,d", value);
    }

    private static void appendIf(StringBuilder out, String prefix, String value) {
        if (value != null) {
            out.append(prefix).append(value);
        }
    }
}
