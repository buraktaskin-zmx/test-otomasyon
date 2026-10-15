package orchestrator.llm;

import orchestrator.catalog.CatalogModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides, in Java, what makes each of N scenarios DIFFERENT.
 *
 * Asking the model for "five different scenarios" does not work here and the
 * reason is written down in application.properties: temperature is 0.3 because
 * "the same request must produce the same scenario, not a new invention". That
 * setting is right for drafting Gherkin and fatal for diversity — five calls
 * with one prompt return five near-identical files. Raising the temperature
 * would buy variety by also buying invented labels and reworded steps, which is
 * exactly what the validator spends its time rejecting.
 *
 * So the variety is computed here and handed to the model as literal values it
 * is told to use. Same shape as every other fix in this project: a prompt rule
 * only holds once it becomes code.
 *
 * Three things follow from that which a prompt could not give:
 *
 * - {@code shown_when} is resolved CORRECTLY instead of hopefully. A field a
 *   choice reveals is assigned only in the variations that made the choice, so
 *   no scenario fills "E-Fatura Hash Kodu" on a screen showing "Normal".
 * - {@code readonly} fields are never assigned, because the application owns
 *   them.
 * - When a screen genuinely cannot produce N distinct scenarios, that is
 *   REPORTED rather than faked. The DBS form has three single-option dropdowns;
 *   pretending five meaningfully different tests came out of it would be the
 *   silent pass this project keeps paying for.
 */
@Component
public class VariationPlanner {

    /**
     * One scenario's worth of decisions.
     *
     * Three lists rather than one map, because the vocabulary has three shapes
     * and a map cannot express them: a value is typed or selected, a checkbox is
     * only ticked (`check {string}` takes no value at all), and an excluded
     * field is one no step may name.
     *
     * @param values   label -> the value this scenario must use, in screen order
     * @param ticked   checkbox captions this scenario must `check`
     * @param excluded labels this scenario must NOT touch — a shown_when field
     *                 whose condition this variation does not meet, or a
     *                 checkbox this variation deliberately leaves alone
     * @param label    a short Turkish name for what makes this one different,
     *                 written HERE rather than read back from the model's
     *                 Scenario title — the UI lists these and must not depend on
     *                 what the model chose to call things
     */
    public record Variation(int index, String label, Map<String, String> values,
                            List<String> ticked, List<String> excluded) {
    }

    /** The plan, plus anything the analyst should know about its limits. */
    public record Plan(List<Variation> variations, List<String> notes) {
    }

    /**
     * Amount ladder, used when the screen told us nothing about the box.
     *
     * Deliberately boundary-flavoured: the smallest payable amount, a round
     * one, a kuruş-bearing one (the case that cost two days), and a large one.
     * Written in the application's own format — comma for kuruş, dot for
     * thousands — because `enter amount` splits exactly that.
     */
    private static final List<String> AMOUNTS =
            List.of("1,00", "0,01", "250,00", "1.250,55", "9.999,99");

    /**
     * Text ladder for a box whose length nobody could measure.
     *
     * Letters and digits only — no space, no hyphen, no punctuation. That is not
     * tidiness: a value this ladder produces goes into whatever text box the
     * catalog failed to describe, and on these screens that includes masked
     * boxes. "TEST VERISI 4" and "TEST-2" are rejected by a mask that accepts
     * neither; "TESTVERISI4" at least has a chance. Length still varies, because
     * length is the only axis an undescribed box offers.
     *
     * It remains a guess, and the batch says so in its notes. The real fix is a
     * catalog that carries max_length and numeric, which only the DOM scan can
     * produce.
     */
    private static final List<String> TEXTS =
            List.of("TEST1", "TEST22", "TST3", "TESTVERISI4", "T5");

    /**
     * Date ladder, written as OFFSETS rather than days.
     *
     * These strings go into the scenario verbatim and `enter date` resolves them
     * when the browser runs. That is the whole point: a literal date freezes the
     * day the scenario was generated, so one drafted this morning reads
     * "08/10/2026" forever and is in the past a fortnight later — refused by a
     * form whose maturity date may not be behind today. The scenario rotted
     * while nothing about it changed. An offset cannot rot.
     *
     * Near, and all future. Near because a year out is not a realistic invoice
     * date and tests nothing anyone asked about; future because a past maturity
     * date is refused outright on this application, and which of a screen's date
     * fields tolerates the past is not something the catalog records. Guessing
     * it from the label's wording is exactly what this class does not do.
     * Testing a past date needs a catalog key that says the field accepts one.
     */
    private static final List<String> DATE_OFFSETS =
            List.of("bugün", "+1 gün", "+3 gün", "+1 hafta", "+1 ay");

    /**
     * Which rung of which ladder each field reads, worked out once for the whole
     * catalog before any scenario is built.
     *
     * Two different problems, two different schemes, and both were wrong when
     * every field simply used the scenario number:
     *
     * - CHOICE fields (dropdown, account picker, checkbox) used {@code i % size}
     *   each, independently. With two two-option dropdowns that walks the
     *   DIAGONAL: (A,X), (B,Y), (A,X)… and the combinations (A,Y) and (B,X) are
     *   never tested at all. {@code weight} turns the fields into the digits of
     *   one mixed-radix counter, so the scenarios walk COMBINATIONS instead.
     * - DATA fields (text, amount, date) also used {@code i}, which made every
     *   field of the same type take the SAME value: on the DBS form all six text
     *   boxes got "TEST1" in scenario 1, and the two invoice numbers were
     *   identical — which a uniqueness check would refuse. {@code ordinal}
     *   offsets each field along its ladder so they differ within one scenario.
     *
     * @param combinations product of every choice field's size — the real
     *                     ceiling on genuinely different scenarios
     */
    private record Slots(Map<String, Integer> weight, Map<String, Integer> ordinal, int combinations) {

        /** Which rung this field reads for scenario {@code i}. */
        int indexFor(CatalogModel.CatalogField field, int i) {
            Integer place = weight.get(field.label());
            if (place != null) {
                int size = "checkbox".equals(field.type()) ? 2 : Math.max(1, field.options().size());
                return (i / place) % size;
            }
            // A data field: its own offset along a ladder every helper wraps.
            return i + ordinal.getOrDefault(field.label(), 0);
        }
    }

    /** How many combinations are worth counting before the number stops meaning anything. */
    private static final int CEILING_CAP = 10_000;

    /**
     * Walks the catalog once and assigns every field its place.
     *
     * Order is the catalog's order, which SCHEMA.md requires to be screen order,
     * so the first field on screen is the counter's least significant digit and
     * therefore the one that changes between scenario 1 and 2. That reads the way
     * a person would expect a list of test cases to read.
     */
    private static Slots slotsOf(CatalogModel catalog) {
        Map<String, Integer> weight = new LinkedHashMap<>();
        Map<String, Integer> ordinal = new LinkedHashMap<>();
        int running = 1;
        int dataFields = 0;

        for (CatalogModel.CatalogField field : catalog.fields()) {
            if (field.readonly()) {
                continue;
            }
            int size = switch (field.type()) {
                case "dropdown", "account_picker" -> field.options().size();
                case "checkbox" -> 2;
                default -> 0;
            };
            if (size > 0) {
                weight.put(field.label(), running);
                running = (running > CEILING_CAP / Math.max(1, size)) ? CEILING_CAP : running * size;
            } else if (!"date".equals(field.type())) {
                ordinal.put(field.label(), dataFields++);
            }
            // DATES GET NO OFFSET, on purpose, and they are the one exception.
            // Offsetting text and amount boxes makes them differ inside one
            // scenario, which is what we want: two invoice numbers that are
            // identical can trip a uniqueness check. Offsetting DATES makes them
            // differ too — into an arbitrary ORDER. On the DBS form that put
            // "Fatura Tarihi" six months after "Başlangıç Vadesi", an ordering
            // no analyst asked for and the application may well refuse. Field
            // relationships ("this date must precede that one") are not in the
            // catalog and cannot be guessed, so every date in a scenario takes
            // the same day: internally consistent, and still varying from one
            // scenario to the next. An absent key reads as offset 0 below.
        }
        return new Slots(weight, ordinal, running);
    }

    /**
     * @param count how many scenarios are wanted; the caller has already
     *              clamped it to something sane
     */
    public Plan plan(CatalogModel catalog, int count) {
        Slots slots = slotsOf(catalog);
        List<Variation> variations = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Set<String> unconstrained = new LinkedHashSet<>();
        Set<String> lateParents = new LinkedHashSet<>();

        for (int i = 0; i < count; i++) {
            Map<String, String> values = new LinkedHashMap<>();
            List<String> ticked = new ArrayList<>();
            List<String> excluded = new ArrayList<>();
            List<String> distinguishing = new ArrayList<>();
            String dataHint = null;

            // Screen order, one pass. A shown_when parent is earlier on these
            // screens, so by the time its dependent field is reached the parent
            // is already in `values` and the condition can simply be read.
            for (CatalogModel.CatalogField field : catalog.fields()) {
                if (field.readonly()) {
                    // The application fills this one in. Assigning a value would
                    // put an `enter` on a disabled input: a 30 s wait for an
                    // element that never accepts typing.
                    continue;
                }
                if (field.conditional()) {
                    String parent = values.get(field.shownWhenField());
                    if (parent == null) {
                        // The parent is readonly, absent, or catalogued AFTER
                        // this field. Either way the condition cannot be
                        // evaluated, and guessing is how a field that is not on
                        // the page gets filled.
                        if (catalog.field(field.shownWhenField()).isPresent()) {
                            lateParents.add(field.label());
                        }
                        excluded.add(field.label());
                        continue;
                    }
                    if (!parent.equals(field.shownWhenValue())) {
                        excluded.add(field.label());
                        continue;
                    }
                }

                // A checkbox is the one field with no value to carry: the step
                // is `check "<caption>"` and its only two states are "called"
                // and "not called". Alternating them is this field's whole
                // variation axis, and the unticked half belongs in `excluded`
                // because ticking a box twice UNticks it.
                if ("checkbox".equals(field.type())) {
                    if (slots.indexFor(field, i) == 0) {
                        ticked.add(field.label());
                        distinguishing.add(field.label() + " işaretli");
                    } else {
                        excluded.add(field.label());
                    }
                    continue;
                }

                Choice choice = valueFor(field, slots.indexFor(field, i));
                if (choice == null) {
                    continue;
                }
                values.put(field.label(), choice.value());
                if (choice.unconstrained()) {
                    unconstrained.add(field.label());
                }
                if (choice.distinguishing()) {
                    distinguishing.add(field.label() + " = " + choice.value());
                }
                // Fallback material for the title, for the very common case
                // where NOTHING on the screen offers a choice: all three
                // catalogs in this repo are like that today. An amount is what a
                // reader recognises next, a date after it.
                if (dataHint == null && "amount".equals(field.type())) {
                    dataHint = field.label() + " " + choice.value();
                } else if (dataHint == null && "date".equals(field.type())) {
                    dataHint = field.label() + " " + choice.value();
                }
            }

            variations.add(new Variation(i, name(i, distinguishing, dataHint), values, ticked, excluded));
        }

        describeCeiling(catalog, slots, count, notes);
        if (!unconstrained.isEmpty()) {
            notes.add("Şu alanların kısıtı (max_length / min / max) katalogda yok, değerler "
                    + "varsayılan merdivenden geldi — ekran daha kısa bir değer bekliyorsa reddedebilir: "
                    + String.join(", ", unconstrained)
                    + ". Bu alanlar için \"Ekranı Keşfet\" taraması kısıtı okuyup kataloğa yazar.");
        }
        if (!lateParents.isEmpty()) {
            notes.add("Şu alanlar hiçbir senaryoda doldurulmadı: " + String.join(", ", lateParents)
                    + ". shown_when ile bağlı oldukları alan katalogda ONLARDAN SONRA geliyor, "
                    + "o yüzden koşul değerlendirilemedi. Katalogda alanları ekran sırasına alın.");
        }
        return new Plan(variations, notes);
    }

    /**
     * @param distinguishing whether this value is one a reader would call the
     *                       point of the scenario — a chosen option, not an
     *                       invented amount
     * @param unconstrained  whether it had to be invented without knowing what
     *                       the box accepts
     */
    private record Choice(String value, boolean distinguishing, boolean unconstrained) {
    }

    /**
     * @param i the RUNG this field reads, from {@link Slots#indexFor} — NOT the
     *          scenario number. The two were the same thing once and that was
     *          the bug: every field of a type then took the same value in a
     *          scenario, and choice fields walked a diagonal instead of
     *          combinations. Every ladder below wraps with {@code %}, so a rung
     *          larger than the ladder is fine; passing the scenario number
     *          straight in is what must not come back.
     */
    private static Choice valueFor(CatalogModel.CatalogField field, int i) {
        List<String> options = field.options();

        return switch (field.type()) {
            // The catalog already carries what these accept, so rotating
            // through the list is both free and guaranteed valid — the
            // validator checks a chosen option against this very list.
            //
            // "distinguishing" is size > 1, not simply true. A single-option
            // dropdown holds the same value in every scenario, so naming it in
            // the card title says nothing — and it USED to, which is how all
            // five DBS cards ended up titled "Bayi Kodu = 93002 · Döviz Cinsi =
            // TL" while the one field that actually differed was trimmed off the
            // end. A title that cannot tell two cards apart is worse than none.
            case "dropdown", "account_picker" -> options.isEmpty()
                    ? null
                    : new Choice(options.get(i % options.size()), options.size() > 1, false);

            case "date" -> new Choice(DATE_OFFSETS.get(i % DATE_OFFSETS.size()), false, false);

            case "amount" -> amount(field, i);

            case "text" -> text(field, i);

            // An uncatalogued or unsupported type (UNSUPPORTED_V3). No step
            // drives it, so no scenario may be told to use it.
            default -> null;
        };
    }

    /**
     * An amount, bounded by the integer box's own maxlength when the scan read
     * one.
     *
     * The ladder's largest entry is four digits, so a box that holds fewer would
     * silently truncate — the form then validates a number nobody chose. With a
     * measured length the ladder is rebuilt to fit; without one the ladder is
     * used as written and the field is reported as unconstrained.
     */
    private static Choice amount(CatalogModel.CatalogField field, int i) {
        Integer digits = field.maxLength();
        if (digits == null || digits <= 0) {
            return new Choice(AMOUNTS.get(i % AMOUNTS.size()), false, true);
        }
        // Boundaries of what this box can hold: the smallest, a middle value,
        // and exactly as many digits as it accepts.
        List<String> ladder = List.of(
                "1,00",
                "0,01",
                group("9".repeat(Math.max(1, digits / 2))) + ",50",
                group("9".repeat(digits)) + ",99",
                "250,00");
        return new Choice(ladder.get(i % ladder.size()), false, false);
    }

    /** Thousands separated the way this application prints and parses them. */
    private static String group(String digits) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                out.append('.');
            }
            out.append(digits.charAt(i));
        }
        return out.toString();
    }

    /**
     * Text, as long as the box will take and no longer.
     *
     * Length is the only axis a plain text box offers, so the ladder walks it:
     * one character, half, exactly full. A numeric box gets digits — letters in
     * a field the screen only accepts digits for is a rejection, not a test.
     */
    private static Choice text(CatalogModel.CatalogField field, int i) {
        Integer limit = field.maxLength();
        if (limit == null || limit <= 0) {
            return field.numeric()
                    ? new Choice(numberInRange(field, i), false, field.min() == null && field.max() == null)
                    : new Choice(TEXTS.get(i % TEXTS.size()), false, true);
        }
        if (field.numeric()) {
            return new Choice(clampDigits(numberInRange(field, i), limit), false, false);
        }

        int half = Math.max(1, limit / 2);
        List<Integer> lengths = List.of(Math.min(5, limit), limit, half, 1, Math.min(2, limit));
        return new Choice(filler(lengths.get(i % lengths.size())), false, false);
    }

    /**
     * A number the field's own min/max allows, walking that range rather than
     * the middle of it: the boundaries are where a form's validation lives.
     */
    private static String numberInRange(CatalogModel.CatalogField field, int i) {
        int low = field.min() == null ? 1 : field.min();
        int high = field.max() == null ? Math.max(low, 12) : field.max();
        if (high < low) {
            high = low;
        }
        List<Integer> ladder = List.of(low, high, (low + high) / 2, Math.min(high, low + 1), high);
        return String.valueOf(ladder.get(i % ladder.size()));
    }

    /** Keeps a generated number inside the box, from the left — a truncated
     *  number is a different number, and the leading digits are the ones that
     *  matter. */
    private static String clampDigits(String number, int limit) {
        return number.length() <= limit ? number : number.substring(0, limit);
    }

    /**
     * Exactly n characters of readable filler. "TEST" repeated rather than
     * random, so two runs of the same plan produce the same scenario — the same
     * reason temperature is 0.3.
     */
    private static String filler(int n) {
        StringBuilder out = new StringBuilder();
        String seed = "TESTVERISI";
        while (out.length() < n) {
            out.append(seed);
        }
        return out.substring(0, n);
    }

    /**
     * What to call this scenario in the UI's list.
     *
     * Built from the chosen OPTIONS, because those are what a reader recognises
     * as the point of the test ("E-Fatura + Taksitli"); an invented amount or a
     * filler string is not. A variation whose only difference is data gets its
     * number, which is honest — there is nothing else to say about it.
     */
    private static String name(int index, List<String> distinguishing, String dataHint) {
        if (distinguishing.isEmpty()) {
            // Nothing on this screen offers a choice, which is the normal case
            // here rather than the exception: every catalog in this repo has
            // only single-option dropdowns. So the title falls back to the data
            // that DOES differ. "Senaryo 3" alone would make the list a column
            // of numbers.
            return dataHint != null
                    ? (index + 1) + ". " + dataHint
                    : "Senaryo " + (index + 1) + " · farklı test verisi";
        }
        // Two is enough for a card title; more and it stops being a name. What
        // was excluded is deliberately NOT in the title: the UI shows it as its
        // own chips, where a reader can tell "not on this screen" from
        // "deliberately not ticked" — a count in the title cannot.
        return String.join(" · ", distinguishing.subList(0, Math.min(2, distinguishing.size())));
    }

    /**
     * How many genuinely different scenarios this screen can produce, and a
     * warning when that is fewer than asked for.
     *
     * The number is the PRODUCT of the choice fields' sizes, not the largest
     * single list, because the scenarios now walk combinations rather than a
     * diagonal: two two-option dropdowns are four distinct tests, and reporting
     * "the widest list has 2" would have undersold that by half. With every
     * dropdown offering one option — which is every catalog in this repo today —
     * the product is 1 and the honest answer is that only the data differs.
     *
     * Saying so is the whole difference between a tool an analyst trusts and one
     * that quietly pads.
     */
    private static void describeCeiling(CatalogModel catalog, Slots slots, int count, List<String> notes) {
        int varying = 0;
        for (CatalogModel.CatalogField field : catalog.fields()) {
            if (field.readonly()) {
                continue;
            }
            boolean chooses = "dropdown".equals(field.type()) || "account_picker".equals(field.type());
            if ((chooses && field.options().size() > 1) || "checkbox".equals(field.type())) {
                varying++;
            }
        }

        if (varying == 0) {
            notes.add("Bu ekranda seçim yapılabilen hiçbir alan yok (tüm açılır listeler tek "
                    + "seçenekli ya da readonly). " + count + " senaryo yalnızca tutar, tarih ve "
                    + "metin değerleri bakımından farklı olacak — aynı alanlar her senaryoda "
                    + "farklı değer alır, ama seçimler aynı kalır.");
        } else if (slots.combinations() < count) {
            notes.add("Bu ekranda " + varying + " alan çeşitlenebiliyor ve bunların tüm "
                    + "kombinasyonu " + slots.combinations() + " farklı senaryo demek. İstediğiniz "
                    + count + " senaryodan " + (count - slots.combinations()) + " tanesi aynı "
                    + "seçimleri tekrar eder, yalnızca test verileri farklı olur.");
        }
    }
}
