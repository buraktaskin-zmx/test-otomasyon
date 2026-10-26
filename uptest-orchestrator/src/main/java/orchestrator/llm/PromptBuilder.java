package orchestrator.llm;

import orchestrator.product.Rule;
import orchestrator.validation.ValidationError;
import orchestrator.validation.ValidationResult;
import orchestrator.vocabulary.Vocabulary;
import orchestrator.vocabulary.VocabularyStep;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Turns the deployed step vocabulary, a screen catalog and an analyst's intent
 * into the two prompts the model sees.
 *
 * The vocabulary is never hardcoded here: it arrives from
 * {@link Vocabulary}, which the orchestrator fetched from a live executor. A
 * hand-written list in this file would be a third copy of the step catalogue
 * and would drift the first time someone adds a step.
 */
@Component
public class PromptBuilder {

    /**
     * The rules the model must not break. Written as constraints rather than
     * suggestions because the validator enforces exactly these, and every
     * violation costs a repair round trip.
     */
    public String system(Vocabulary vocabulary) {
        StringBuilder sb = new StringBuilder();

        sb.append("""
                You write Cucumber/Gherkin test scenarios for a Turkish internet banking
                application. You are given a CLOSED vocabulary of step patterns and a
                catalog describing one screen.

                HARD RULES — a scenario breaking any of these is rejected automatically:

                1. Use ONLY the step patterns listed below. Never invent a step, never
                   reword one, never translate one. The text outside the quotes must
                   match a pattern character for character.
                2. {string} placeholders are replaced by text in DOUBLE QUOTES.
                   {int} placeholders are replaced by a bare integer.
                3. Every value you put in quotes must come from the catalog: a field
                   label, a button caption, a menu name, a listed option or account.
                   Invent ONLY test data: amounts, dates, names, tax numbers.
                   An account number is NOT test data -- copy one from the field's
                   "accounts" list character for character, because the step matches
                   it against the account numbers the screen really offers. Neither
                   is a confirmation message: assert one only when the catalog gives
                   a success_message, and otherwise end the scenario without it.
                4. Output the .feature file and NOTHING else. No explanation, no
                   commentary, no markdown code fences, no <think> blocks.
                5. Start with "Feature:" and use exactly one "Scenario:" unless the
                   intent clearly asks for several input rows, in which case use one
                   "Scenario Outline:" with an "Examples:" table.
                6. Feature and Scenario titles are written in Turkish. Step lines keep
                   the English pattern text exactly as listed.
                7. A scenario starts by logging in and navigating to the screen, then
                   fills fields, then presses the buttons, then verifies. The
                   catalog's "app" key decides WHICH login step: NGC is corporate
                   and takes cif AND userid, NGI is the individual app and takes a
                   customer code only. They are different applications, so the NGI
                   step on an NGC catalog logs into the wrong place entirely.
                   The "navigation" block is performed in the order it is
                   written, and every part of it that is present must appear in
                   the scenario:
                   - "entry_tab": the sub-menu lands on a list that is already
                     filtered, and the records this test is about are under a
                     different filter. Click it with `click "<entry_tab>"`
                     immediately after `navigate to`. Omitting it is the
                     quietest mistake there is: the scenario then picks the
                     right row of the WRONG list, every step passes, and the
                     run documents a record nobody asked about.
                   - "entry_row": the sub-menu lands on a result grid and not
                     on a form. Pick that row with `select row "<entry_row>"`,
                     AFTER the tab and BEFORE the `click "<entry_button>"` that
                     opens the form. Skipping it leaves the form bound to no
                     record, or never opens it at all.
                   - "entry_choice": the form is gated behind a radio
                     choice that decides WHICH form you get. Pick it with
                     `choose "<entry_choice>"`, AFTER the entry_button and
                     BEFORE the first field. It is not a field of the form and
                     must never be varied: the other option opens a different
                     screen entirely, where none of the catalogued fields
                     exist and every one of them times out.
                8. FILL EVERY FIELD the catalog lists, in the order it lists them,
                   unless the intent explicitly says to leave one empty. A banking
                   form rejects a half-filled submission with a modal error and the
                   run is wasted -- a real case: skipping "Ad Soyad / Unvan" produced
                   "uygun musteri bulunmamaktadir (ME7004)" and the scenario hung on
                   the dialog until it timed out.
                   ONE exception: a field marked "readonly: true" is filled by the
                   APPLICATION, not by you. It is a disabled input, so it does not
                   merely refuse the value -- the step waits 30 s for something that
                   will never accept typing. Never `enter` into it; verify it if the
                   intent asks. A real case: "Bayi Ünvanı" is looked up from the
                   chosen "Bayi Kodu".
                   A SECOND exception: a field carrying "shown_when: {field, value}"
                   is not on the screen until that field holds that value. Fill it
                   ONLY in a scenario that also sets that value, and set the value
                   BEFORE filling it. Otherwise leave it out entirely -- filling a
                   field that is not on the page times out. Real case: "E-Fatura
                   Hash Kodu" appears only when "Fatura Tipi" is "E-Fatura".
                9. THE FIELD'S "type" DECIDES THE VERB. Every type, not just the
                   unusual ones -- the validator cannot catch a wrong verb,
                   because `enter "Bayi Kodu" "93002"` is a perfectly valid
                   pattern and only fails in the browser, hunting for a text box
                   that is really a dropdown:
                     text            -> enter
                     date            -> enter date
                     amount          -> enter amount
                     dropdown        -> select ... from
                     account_picker  -> select account ... from
                   A DATE IS WRITTEN AS AN OFFSET, NOT AS A DAY. `enter date`
                   takes "bugün", "yarın", "dün", or a shift like "+3 gün",
                   "-1 hafta", "2 ay", "1 yıl", and resolves it when the browser
                   runs. Write the offset you were given and do NOT convert it
                   to a calendar date: a literal date freezes the day this
                   scenario was written, and a maturity date behind today is
                   refused by the form. An absolute gg/aa/yyyy is accepted when
                   one is explicitly asked for, and only then.
                   COPY THE OFFSET EXACTLY AS GIVEN. "bugün" is not to be
                   rewritten as "+0 gün", "yarın" not as "+1 gün". They resolve
                   to the same day, but the scenario is read by a person and the
                   word they were given is the one that says what the test
                   means.
                   AND A DATE IS VERIFIED WITH ITS OWN STEP:
                     verify the "<label>" has date "<the same offset>"
                   NEVER `verify the {} field equals {}` for a date. That step
                   compares text exactly and the screen shows 11/10/2026, not
                   "+3 gün" — so a field you set correctly fails its own
                   assertion. Use the same offset you entered.
                   A value that looks like a number ("93002") is still a
                   dropdown option when the catalog says "type: dropdown".
                10. ONLY verify a value the scenario itself entered. A field you
                   never filled holds whatever the application derived -- a real
                   case: "Alıcı Ad Soyad / Unvan" is filled in by the bank from
                   the chosen account, and a scenario asserting the name it had
                   typed into the DIFFERENT field "Ad Soyad / Unvan" failed on a
                   screen where nothing was actually wrong. If you did not type
                   it, do not assert it.
                11. ARGUMENT ORDER. A pattern containing the word "from" takes the
                   VALUE first and the field label last; every other pattern takes
                   the LABEL first and the value second. The validator cannot
                   catch a swap -- both are {string}, so the scenario passes
                   validation and then hunts for a field labelled "200,00" until
                   it times out. Read the pattern, do not assume:
                     enter amount "Ödeme Tutarı" "200,00"        label, value
                     select "Vadesinde Peşin Ödensin" from "Ödeme Talebi Türü"
                                                                value, label

                AVAILABLE STEP PATTERNS:
                """);

        for (VocabularyStep step : vocabulary.steps()) {
            sb.append("  ").append(step.keyword()).append(' ').append(step.pattern()).append('\n');
        }

        sb.append("""

                SHAPE OF A VALID SCENARIO (structure only — use the catalog for values):

                Feature: <Turkish title>

                  Scenario: <Turkish sentence describing what is verified>
                    Given <login step with the credentials given in the intent>
                    And navigate to "<main menu>" "<sub menu>"
                    And enter "<field label>" "<value>"
                    And click "<button>"
                    Then verify "<expected text>" text is displayed
                """);

        return sb.toString();
    }

    /**
     * The screen and the intent, with the model left to write its own login
     * line — the behaviour before the orchestrator started writing that line
     * itself (LoginStep).
     *
     * Kept so a deployment that has not taken the login-step change yet can
     * still pick up newer catalog rules from {@link #system}: the two are
     * independent, and coupling them would mean holding back every prompt fix
     * until the whole orchestrator is upgraded. Deliberately no reference to
     * LoginStep here — this overload has to compile where that class is absent.
     */
    public String user(String catalogYaml, String intent) {
        return user(catalogYaml, intent, null);
    }

    /**
     * The screen, plus what the analyst actually wants tested.
     *
     * @param loginStep when the analyst supplied credentials the orchestrator
     *                  writes this line itself, so the model is told to skip it
     *                  rather than guess an application and a userid
     */
    public String user(String catalogYaml, String intent, String loginStep) {
        return user(catalogYaml, intent, loginStep, null);
    }

    /**
     * @param variation one of N data combinations, or null for a single
     *                  scenario. When present it is NOT a hint: the values were
     *                  chosen from the catalog by {@link VariationPlanner} and
     *                  are already known to be valid, so the model's only job
     *                  here is to write the steps around them. Asking a model at
     *                  temperature 0.3 to "make this one different" instead
     *                  returns the same scenario five times.
     */
    public String user(String catalogYaml, String intent, String loginStep,
                       VariationPlanner.Variation variation) {
        return user(catalogYaml, intent, loginStep, variation, List.of());
    }

    /**
     * @param rules the product rules the analyst ticked for THIS generation,
     *              or empty. Not the product's whole rule book: the prompt
     *              already carries eleven hard rules, the live step list and
     *              the entire catalog, and this project has measured what
     *              happens to a rule that sits far from where it applies —
     *              "the prompt is read ONCE, and by field eleven it is far
     *              away". A rule the analyst did not choose is a rule the
     *              model does not read.
     */
    public String user(String catalogYaml, String intent, String loginStep,
                       VariationPlanner.Variation variation, List<Rule> rules) {
        String login = (loginStep == null)
                ? ""
                : """

                        THE LOGIN STEP IS ALREADY DECIDED — do not write one, it is
                        inserted for you as the scenario's first step:
                          %s
                        """.formatted(loginStep);

        return """
                SCREEN CATALOG (YAML):
                %s
                %s
                WHAT TO TEST:
                %s
                %s%s
                Write the .feature file now. Output nothing but the file contents.
                """.formatted(catalogYaml, rulesBlock(rules), intent, login, variationBlock(variation));
    }

    /**
     * What the application must DO, as opposed to what the screen HAS.
     *
     * Placed between the catalog and the intent on purpose, because that is
     * the order the three are meant to be read in: the catalog gives the
     * nouns, the rules say what is true of them, and only then does the
     * analyst's request say which of it to exercise. Put after the intent it
     * reads as an afterthought to a task already described.
     *
     * <p>In the USER turn rather than the system one, for the same reason the
     * variation block is: the eleven hard rules are identical for every
     * generation and belong where the model weighs them most, while this
     * changes with every screen and every tick box.
     *
     * <p>The instruction about copying a message verbatim is not politeness.
     * {@code checkMessageIsCatalogued} compares the asserted sentence against
     * the known ones character for character after folding, so a model that
     * rewords "Geçersiz IBAN numarası girdiniz." into something tidier has
     * written a scenario that cannot pass — the same failure the explore agent
     * produced when it retyped an option as "Taksitli Ödnsin" instead of
     * copying it.
     */
    private static String rulesBlock(List<Rule> rules) {
        if (rules == null || rules.isEmpty()) {
            return "";
        }
        StringBuilder block = new StringBuilder("\nÜRÜN KURALLARI — bu ekran bunlara uymak ZORUNDA:\n");
        for (Rule rule : rules) {
            block.append("  [").append(rule.id()).append("] ");
            if (!rule.confirmed()) {
                // Said out loud because the model cannot tell a verified fact
                // from someone's best guess, and the analyst deliberately
                // ticked this one: an assumption may be tested, but a scenario
                // built on it should be recognisable as such.
                block.append("(VARSAYIM — kesinleşmemiş) ");
            }
            block.append(rule.title()).append('\n');
            if (!rule.description().isEmpty()) {
                block.append("    ").append(oneLine(rule.description())).append('\n');
            }
            for (String message : rule.messages()) {
                block.append("    Uygulamanın yazdığı mesaj, BİREBİR kopyala: \"")
                        .append(message).append("\"\n");
            }
        }
        return block.toString();
    }

    /** A rule's description folded onto one line, so the block stays scannable. */
    private static String oneLine(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    /**
     * The one part of the prompt that differs between the N scenarios of a
     * batch.
     *
     * It goes in the USER turn, not the system one: the rules are identical for
     * every scenario and belong where the model weighs them most heavily, while
     * this changes on every call. Mixing them would mean rebuilding the long
     * prompt N times for one varying paragraph.
     *
     * Phrased as a closed list on purpose. "Vary the values" is a request and
     * produced five identical files; "use exactly these" is an instruction whose
     * compliance the validator can see, because every value here came out of the
     * catalog's own option lists.
     */
    private static String variationBlock(VariationPlanner.Variation variation) {
        if (variation == null) {
            return "";
        }
        // Rule 5 of the system prompt permits a Scenario Outline "when the
        // intent asks for several input rows". That is right for ONE scenario
        // and wrong here, and the model read it as permission: a draft whose
        // combination was "Fatura Tipi = E-Fatura" came back as an Outline with
        // `select "<Fatura Tipi>" from "Fatura Tipi"` and a one-row Examples
        // table saying "Normal" — a placeholder AND the wrong value. It was
        // obeying rule 5. So the contradiction is cancelled here, in the user
        // turn, which is the only place that knows this is a batch.
        //
        // Said once, and backed by code: OutlineFlattener resolves the
        // placeholders from the plan and deletes the table, because a prompt
        // rule that contradicts another prompt rule is not settled by adding a
        // third.
        StringBuilder sb = new StringBuilder("\nBU BİR ÇOKLU ÜRETİM. Düz bir \"Scenario:\" yaz — "
                + "\"Scenario Outline:\" ve \"Examples:\" tablosu KULLANMA, <yer tutucu> yazma. "
                + "Kombinasyon zaten seçildi ve aşağıda duruyor; tablo o kararı yeniden açar.\n"
                + "\nBU SENARYONUN VERİ KOMBİNASYONU — bunlar seçildi, "
                + "değiştirme ve kendin başka değer uydurma:\n");

        variation.values().forEach((label, value) ->
                sb.append("  \"").append(label).append("\" = \"").append(value).append("\"\n"));
        if (variation.values().isEmpty()) {
            sb.append("  (bu ekranda doldurulacak alan yok)\n");
        }

        if (!variation.ticked().isEmpty()) {
            sb.append("İŞARETLENECEK KUTULAR — her biri için `check \"<etiket>\"` yaz, bir kez:\n");
            for (String box : variation.ticked()) {
                sb.append("  \"").append(box).append("\"\n");
            }
        }

        if (!variation.excluded().isEmpty()) {
            // The hard half. A shown_when field that is not on the screen costs
            // a 30 s timeout, and a checkbox ticked twice is UNticked — both are
            // failures the validator cannot see, so the exclusion has to be
            // spelled out rather than left implied by its absence above.
            sb.append("BU SENARYODA DOKUNULMAYACAK ALANLAR — hiçbir adımda adını bile geçirme, "
                    + "ne doldur ne doğrula:\n");
            for (String label : variation.excluded()) {
                sb.append("  \"").append(label).append("\"\n");
            }
        }
        return sb.toString();
    }

    /**
     * The user turn for a scenario written FROM A RECORDED WALK.
     *
     * <p>The system prompt is the shared one, and three of its rules are
     * wrong for this mode: rule 7 asks for a login line (the run grafts it),
     * rule 8 asks to fill every catalogued field (the walk decides which were
     * filled), and rule 9 asks for date offsets (the analysts chose literal
     * dates for these). A contradiction is not settled by adding a third rule
     * somewhere else, so each one is cancelled HERE, by name, in the only turn
     * that knows this is a walk — the same move the batch path makes for rule
     * 5. What the model may still get wrong is backed by code: the provenance
     * check refuses any quoted word the walk, the catalog and the analyst's
     * text never contained.
     *
     * @param catalogYaml the catalog of the screen, or null when there is none;
     *                    reference only — the walk outranks it
     * @param scenario    the scenario as the analyst wrote it
     * @param expected    its BEKLENEN lines, the only source of a THEN
     * @param evidence    {@code TapeEvidence.digest}
     * @param draft       the steps the code renderer made from the same walk
     * @param draftNotes  what the renderer said it could not express
     */
    public String fromWalk(String catalogYaml, String scenario, List<String> expected, String evidence,
                           List<String> draft, List<String> draftNotes) {
        StringBuilder sb = new StringBuilder();
        sb.append(WALK_RULES).append('\n').append(WALK_EXAMPLES).append('\n');

        sb.append("SCREEN CATALOG (YAML) — reference only; the walk below outranks it:\n");
        sb.append(catalogYaml == null || catalogYaml.isBlank()
                ? "  (bu ekranın kataloğu yok — etiketleri yalnızca yürüyüşten al)\n"
                : catalogYaml + "\n");

        sb.append("\nTHE SCENARIO AS THE ANALYST WROTE IT:\n").append(scenario == null ? "" : scenario.trim())
                .append("\n\nBEKLENEN (the ONLY source of verify lines):\n");
        if (expected == null || expected.isEmpty()) {
            sb.append("  (boş — verify yazma)\n");
        } else {
            for (String line : expected) {
                sb.append("  - ").append(line).append('\n');
            }
        }

        sb.append("\nWALK EVIDENCE:\n").append(evidence == null ? "" : evidence);

        sb.append("\nCODE DRAFT (made by code from the same walk; usually right, its notes say where not):\n");
        if (draft == null || draft.isEmpty()) {
            sb.append("  (boş)\n");
        } else {
            for (String step : draft) {
                sb.append("  ").append(step).append('\n');
            }
        }
        if (draftNotes != null && !draftNotes.isEmpty()) {
            sb.append("DRAFT NOTES:\n");
            for (String note : draftNotes) {
                sb.append("  - ").append(note).append('\n');
            }
        }
        sb.append("\nWrite the .feature file now. Output nothing but the file contents.\n");
        return sb.toString();
    }

    private static final String WALK_RULES = """
            THIS SCENARIO IS WRITTEN FROM A RECORDED WALK.
            An agent has already performed it in a real browser. Below is what the
            agent did, step by step, and what the screens showed. Write the .feature
            that replays the walk's SUCCESSFUL path with the step patterns above.

            RULES FOR THIS MODE — where they differ from the general rules, THESE win:
            A. NO LOGIN STEP. This cancels rule 7's login: the run inserts the login
               from the test user chosen at run time. Start with `navigate to`.
            B. WRITE THE PATH THAT WORKED, NOT EVERY ATTEMPT. Leave out:
               - a click the application refused and the agent pressed again later
                 (a "… giriniz." / "… seçiniz." message after it is the sign) —
                 keep only the press that went through;
               - a "Tamam" that closed an error dialog;
               - typing into a dropdown's search box, and re-opening the same
                 dropdown: opening it and clicking an option is ONE
                 `select "<option>" from "<label>"`;
               - a click into a text box before typing into it;
               - the calendar icon ("...") and a clicked day number: a date is ONE
                 `enter date "<label>" "<gg/aa/yyyy>"`;
               - key presses (Tab, Escape, Delete, Control+a);
               - tab switching, closing tabs, retries after a "Ref … not found".
            C. FILL THE FORM TOP TO BOTTOM, in the order EKRANLARDA GÖRÜLEN ALAN
               ETİKETLERİ lists the fields, each field ONCE, with the LAST value the
               walk gave it. Only fields the walk actually filled — this cancels
               rule 8's "fill every catalogued field".
            D. INVENT NOTHING. Every label, option and button in quotes is spelled
               exactly as the evidence shows it; every value is one the walk typed
               or chose. A checker rejects any quoted text that appears nowhere in
               the walk, the catalog or the analyst's text.
            E. DATES ARE LITERAL. Write a date exactly as the walk entered it,
               gg/aa/yyyy, with `enter date`. This scenario EXPLICITLY asks for
               absolute dates — do NOT turn them into offsets like "+3 gün"; this
               cancels rule 9's offsets. Verify one with
               `verify the "<label>" has date "<gg/aa/yyyy>"`.
            F. An amount typed into two boxes (lira, then kuruş) is ONE
               `enter amount "<label>" "<lira>,<kuruş>"`.
            G. A dropdown's label is the label printed beside it ON THE SCREEN WHERE
               IT WAS CHOSEN — never a word printed on a later screen. If the agent
               called it "Firma Seçimi combobox", the label is "Firma Seçimi".
            H. VERIFY ONLY WHAT BEKLENEN SAYS, after the last action, one `verify`
               per BEKLENEN line that names a field and a value, the label spelled
               as the screen printed it. Add no checks of your own. A BEKLENEN line
               that names no field (for example a bare "oldugunu doğrula") is not a
               check — skip it.
            I. If the walk never managed something the scenario asks for, do not
               pretend it did: leave the step out. Never write a step for something
               the evidence does not show.
            J. Exactly one "Scenario:". No "Scenario Outline", no "Examples", no
               "#" comment lines, no `wait` steps.
            """;

    private static final String WALK_EXAMPLES = """
            EXAMPLE 1 — the shape of a good result (a different screen, written by an
            analyst; the login line is left out exactly as rule A asks):

            Feature: Online DBS Fatura Oluştur
              Scenario: Bayi seçilip fatura oluşturulur
                Given navigate to "NAKİT YÖNETİMİ" "Online DBS İşlemleri"
                And select row "FORD DBS ODEMELERI"
                And click "Fatura Oluştur"
                And choose "Bilgi Girişi"
                And select "93002" from "Bayi Kodu"
                And enter date "Başlangıç Vadesi" "19/09/2026"
                And enter "Fatura No" "11"
                And enter "Orijinal Fatura Numarası" "1111"
                And select "TL" from "Döviz Cinsi"
                And enter "Taksit Adedi" "3"
                And enter amount "Toplam Tutar" "200,00"
                And enter amount "Orijinal Fatura Tutarı" "66,00"
                And select "Ay" from "Taksit Periyot Birimi"
                And enter "Taksit Periyodu" "2"
                And enter "Fatura Dönemi" "2"
                And enter date "Fatura Tarihi" "30/09/2026"
                And select "Normal" from "Fatura Tipi"
                And click "Devam"
                Then verify the "Bayi Kodu" field equals "93002"
                And verify the "Toplam Tutar" has amount "200,00"

            EXAMPLE 2 — a wandering walk, and the scenario it becomes.
            Walk (shortened):
              #16 browser_click | ajan: "Firma Seçimi combobox" | çalışan: …getByRole('textbox', { name: 'Seçiniz' }).click();
              #19 browser_click | ajan: "Sanko DBS Ödemeleri" | çalışan: …getByRole('option', { name: 'Sanko DBS Ödemeleri' }).click();
              #20 browser_click | ajan: "Fatura Oluştur" | çalışan: …getByRole('link', { name: 'Fatura Oluştur' }).click();
              #23 browser_click | ajan: "Bilgi Girişi radio butonu" | çalışan: …getByRole('radio', { name: 'Bilgi Girişi' }).click();
              #26 browser_click | ajan: "Bayi Kodu combobox" | çalışan: …locator('#select2-bayiKodu-container').click();
              #28 browser_type | ajan: "Bayi Kodu arama kutusu" | text: "151732" | ✗ TimeoutError
              #32 browser_click | ajan: "Bayi Kodu combobox" | çalışan: …locator('#select2-bayiKodu-container').click();
              #34 browser_click | ajan: "bayisanko1" | çalışan: …getByRole('option', { name: 'bayisanko1' }).click();
              #36 browser_type | ajan: "Fatura No alanı" | text: "11113" | çalışan: …fill('11113');
              #37 browser_type | ajan: "Toplam Tutar - lira kutusu" | text: "1000" | çalışan: …pressSequentially('1000');
              #38 browser_type | ajan: "Toplam Tutar - kuruş kutusu" | text: "50" | çalışan: …locator('#toplamTutar-decimal').pressSequentially('50');
              #40 browser_click | ajan: "Devam butonu" | çalışan: …getByRole('link', { name: 'Devam' }).click();
              #41 browser_press_key | key: "Escape"
              #44 set_date | ajan: … | date: "27/10/2026"
              #46 browser_click | ajan: "Devam butonu" | çalışan: …getByRole('link', { name: 'Devam' }).click();
            Screens showed, in order: "Firma Seçimi", then "Bayi Kodu", "Başlangıç Vadesi",
            "Fatura No", "Toplam Tutar"; after #40 the form printed "Başlangıç vadesi seçiniz."
            Result:

            Feature: Firma Fatura İşlemleri
              Scenario: fatura oluştur
                Given navigate to "NAKİT YÖNETİMİ" "Firma Fatura İşlemleri"
                And select "Sanko DBS Ödemeleri" from "Firma Seçimi"
                And click "Fatura Oluştur"
                And choose "Bilgi Girişi"
                And select "bayisanko1" from "Bayi Kodu"
                And enter date "Başlangıç Vadesi" "27/10/2026"
                And enter "Fatura No" "11113"
                And enter amount "Toplam Tutar" "1000,50"
                And click "Devam"

            Why: the search-box typing (#28) and the re-open (#32) are one select; #40
            was refused ("… seçiniz.") and pressed again at #46, so "Devam" is written
            once, at the end; the date was set late (#44) but the screen prints
            "Başlangıç Vadesi" right after "Bayi Kodu", so that is where it goes; the
            Escape is not a step.
            """;

    /**
     * The repair turn. The model gets its own draft back with the validator's
     * verdict, which is cheap — validation costs milliseconds and no browser —
     * and fixes the common failure of a nearly-right step pattern.
     */
    public String repair(String draft, ValidationResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("The scenario you produced was rejected by the validator.\n\n")
                .append("YOUR DRAFT:\n")
                .append(draft)
                .append("\n\nERRORS:\n");

        for (ValidationError error : result.errors()) {
            sb.append("  line ").append(error.line()).append(": ").append(error.message());
            if (error.step() != null) {
                sb.append(" -> ").append(error.step());
            }
            List<String> suggestions = error.suggestions();
            if (suggestions != null && !suggestions.isEmpty()) {
                sb.append("\n      closest valid patterns: ").append(String.join(" | ", suggestions));
            }
            sb.append('\n');
        }

        sb.append("""

                Rewrite the whole file so every step matches an allowed pattern exactly.
                Keep the intent and the values you already chose wherever they were
                valid. Output nothing but the corrected file contents.
                """);
        return sb.toString();
    }
}
