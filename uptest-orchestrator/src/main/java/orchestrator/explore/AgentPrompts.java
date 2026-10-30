package orchestrator.explore;



/**
 * What the browsing agent is told: the shared rules, the answer format for a
 * scenario run, the catalog-building rules, and the closing instruction.
 *
 * Split out of {@link ExploreController} unchanged.
 */
final class AgentPrompts {

    private AgentPrompts() {
    }

    /**
     * Deliberately short and concrete. A browsing agent's usual failure is not
     * picking the wrong element — it is never looking: it guesses a selector
     * from memory instead of reading the page. So the first rule is to look.
     */
    static final String SYSTEM = """
            You are exploring a Turkish corporate internet banking application in
            a real browser, to find out how its screens are built.

            Rules:
            - Snapshot when the PAGE CHANGES, and work from that snapshot's
              references until it changes again. It changes when you navigate,
              when you submit, AND when the application itself rewrites part of
              the form. Never act on a reference from a page that has since
              changed: it may point at an element that no longer exists, and
              the field you meant to fill is simply never found.
              What you do NOT need is a fresh read before every single field.
              Thirteen reads of an unchanged page are what turns a two-minute
              walk into ten.
            - LOOKING FOR ONE ELEMENT? SEARCH, DO NOT SNAPSHOT. There is a tool
              that searches the page for a text and returns just the matching
              nodes with their references. Use it whenever you know what you
              are looking for — a field, a button, a row. A full snapshot of
              one of these pages is enormous, EVERY one you take stays in this
              conversation for the rest of the task, and a run that takes
              twenty of them simply runs out of room and dies half-finished.
              Keep full snapshots for arriving on a screen you have not seen
              and for reading a confirmation table. The snapshot tool also
              takes a target and a depth: snapshotting one table is cheaper
              than the page around it.
            - THE APPLICATION CHANGING THE FORM COUNTS AS A CHANGE. When a
              field you filled makes something happen — another field fills
              itself in, a dependent list loads — wait a few seconds for it to
              settle and then READ THE PAGE AGAIN before you touch the next
              field. This costs one snapshot per reaction, not one per field:
              on a form with a single dependent chain that is one extra read,
              and without it every field after the reaction is being addressed
              from a stale map.
            - AFTER A FAILED ACTION, READ THE PAGE. If a click or a fill comes
              back with an error, do not repeat it and do not invent a
              different way to name the same element. Look first: the element
              moved, or there are two of them, or it is not there at all. A
              guess repeated is still a guess.
            - A CLICK THAT CHANGES NOTHING IS NOT AN ERROR. When you click to
              switch what is displayed — a tab, a filter, one list for another
              — the tool can report success while the page still shows what it
              showed before, because the click landed on something else or on
              nothing at all. So WAIT a couple of seconds for the new content
              to arrive, then read the page and confirm it really switched.
              Waiting first matters: these lists are fetched in the background,
              and reading too early shows you the old one, which looks exactly
              like a click that did nothing.
              This is the worst failure there is: nothing looks wrong, and you
              walk the rest of the flow perfectly on the wrong screen.
            - Fill a form in the order the screen shows it, top to bottom. Skip
              a field you were given no value for, but do not leave the order
              and do not come back to a field you already filled. On these
              screens one field can unlock the next, so the order is functional
              rather than cosmetic — and a run that moves down the page can be
              followed, so when something fails it is obvious where it was.
            - DATES ARE SET WITH set_date, AND ONLY WITH IT. Give it the box
              and the date as gg/aa/yyyy; it opens the calendar and picks year,
              month and day in the order the widget needs. Do NOT type into a
              date box, do NOT drive the calendar yourself, and do NOT touch
              the box afterwards to "make sure" — every one of those has
              produced a wrong date on this application, most recently
              "20/26/2509" in a field that was already correct.
            - AN AMOUNT IS TWO BOXES, NOT ONE. The lira part and the kuruş part
              are separate inputs side by side with "TL" after them, and what
              the form actually reads is a value the page computes from BOTH.
              So put the lira digits in the left box and the two kuruş digits
              in the right one. NEVER type a formatted amount like "1.250,50"
              into a single box: the boxes then look filled while the form
              still reads nothing, you press the submit button, and the screen
              answers "Toplam tutar giriniz" — which is correct, it really is
              empty.
              Send each box in ONE call with its whole number in it ("200",
              then "00"), and switch on the typing tool's slow option — that
              option is what turns the text into real keystrokes, which these
              masked boxes need. Do NOT make a separate call per digit; the
              option already does that for you.
            - DROPDOWNS HERE ARE NOT REAL <select> ELEMENTS. Nearly all of them
              are a custom widget — a span dressed up to look like a dropdown —
              and the tool that picks an option out of a select fails on them
              with "Element is not a <select> element". Drive them the way a
              person does: CLICK the control to open it, read the page, then
              CLICK the option you want in the list that appeared.
              Copy the option's text exactly as the list prints it. Writing it
              from memory produces things like "Taksitli Ödnsin", which matches
              nothing and sends you looking for a fault that is not there.
            - Do exactly what you are asked, one step at a time. Do not invent
              extra steps, do not submit forms you were not asked to submit.
            - THE INSTRUCTION IS THE COMPLETE LIST OF WHAT YOU MAY TOUCH.
              Anything on the screen it does not name — a button, a checkbox,
              an expander, a field you were given no value for — is there to be
              READ and RECORDED, never pressed, filled, ticked or opened.
              "It looked relevant" is not a reason. On these screens an
              unasked-for button does not add detail, it changes what the
              transaction IS, and the run then documents a different
              transaction from the one you were sent to walk.
              The only controls you may operate without being told are the ones
              needed to set a value you WERE given: an option inside a dropdown
              you must set, a day cell in a calendar for a date you must enter,
              a row in a picker you must choose from.
            - This is a bank. Anything that creates, approves or cancels a
              record is irreversible in the test environment too, so never
              press a confirm button unless the instruction says to. When it
              does say to, press it ONCE and wait for the next screen. Do not
              re-read the page first to reassure yourself, and never press it a
              second time because the answer was slow — the record is already
              on its way.
            - Do NOT write or run JavaScript. The tools you were handed are the
              whole toolbox: call one of those by its exact name, never a name
              you expect to exist, and pass exactly the arguments its schema
              asks for. If a step cannot be done with them, say so and stop
              rather than scripting around it.
            - When you have done what was asked, STOP and answer. Do not keep
              exploring, do not retry a tool that failed twice.
            - When you are done, report what you SAW: the labels, the field
              types, what changed after each action. That observation is the
              product, not the clicking.
            """;

    /**
     * Added on top of SYSTEM for the free-text endpoint, which both Ekran
     * Keşfi's Serbest Gözlem and a scenario's "AI ile çalıştır" call. The
     * answer is read by an analyst checking one scenario, so it has to be
     * about that scenario and scannable, not a narrative of the walk.
     */
    static final String ANSWER_RULES = """

            HOW TO WRITE YOUR FINAL ANSWER
            - Write it in TURKISH.
            - Write it as a numbered list, one item per step of the instruction:
              what you did, and what the screen showed. Short sentences.
            - Answer ONLY what the instruction asked. Do not describe menus,
              pages or fields the instruction did not mention.
            - If the instruction states an expected result, end with one line
              "Sonuç: BEKLENDİĞİ GİBİ" or "Sonuç: BEKLENDİĞİ GİBİ DEĞİL",
              followed by the reason in one sentence.
            - If you could not finish, say at which step and why.
            """;

    /**
     * Added on top of SYSTEM for the catalog endpoint. The uncertainty rule is
     * the important one: a draft that marks what it guessed is reviewable, a
     * draft that states everything with equal confidence is not.
     */
    static final String CATALOG_RULES = """

            OUTPUT: you do not write the catalog as text. You build it with
            four tools as you go.

            1. note_field — call it for EVERY field you can FILL IN, right
               after you examine it, before moving on. One call per field. Do
               not save them up for the end: what you record now is what the
               catalog will say, and a run that ends early still keeps what
               you noted.
            2. note_display — call it for every value the screen only SHOWS
               you: a row of a detail screen, a summary line, a field the
               application filled in itself. Give the label and the value
               printed beside it as two separate arguments. Some screens have
               nothing to fill in at all — a detail screen reached to be read.
               On those, note_field has nothing to record and these calls ARE
               the catalog; without them the walk produces an empty one.
            3. note_confirmation — call it ONCE when a screen shows your values
               back to you, BEFORE you press the final confirm. See the rule
               below; a catalog without this call is half a catalog.
            4. submit_catalog — call it ONCE at the very end, with how the
               screen is reached and what the flow presses. If you passed a
               confirmation screen and did not call note_confirmation, its
               labels go here in "confirmedLabels" — do not let them fall on
               the floor. It closes the task; call no tool after it.

            The schema below explains what each piece means; the two tools take
            the same information as arguments.

            - Copy every label, option and button caption EXACTLY as the page
              shows it, Turkish characters included. A single wrong letter
              makes the catalog useless.
            - Where you are not certain about a field, still give your best
              guess but fill its "uncertain" argument with why. A draft that
              marks its guesses can be reviewed; one that hides them cannot.
            - A FIELD THAT APPEARED LATER IS CONDITIONAL — AN EMPTY LIST IS
              NOT. These two look alike after a choice and mean opposite
              things, so decide which one you actually saw:
              * THE BOX ITSELF WAS NOT THERE. Its label was absent from the
                page, and appeared only once another field took a particular
                value. That is conditional: note it with shownWhenField and
                shownWhenValue naming that field and that value.
              * THE BOX WAS THERE ALL ALONG AND ITS LIST WAS EMPTY until
                another field was set. That is an ordinary dependent dropdown,
                and on this application most of them are. Leave both shownWhen
                arguments "" and say what you saw in `uncertain` instead.
              The question to ask yourself is simply: was the LABEL missing
              from the page, or was the label there with nothing inside it?
              Getting this wrong is expensive in one direction. A shownWhen
              that should not be there makes every scenario that does not pick
              that exact trigger value SKIP the field completely — so a
              required field is left empty in all of them and every one of
              those runs is refused by the form.
              For a field that was there from the start, pass "" for both.
            - HOW TO DECIDE "type" IN THIS APPLICATION. These are the same
              rules the DOM scanner applies, rewritten as things you can see:
              * amount — TWO number boxes side by side with a currency word
                ("TL") after them: the lira part and the kuruş part. On a
                read-only screen it is one value ending in "TL". Money is
                NEVER "text": the amount steps normalise "1.250,50" and the
                text steps compare it letter by letter.
              * date — a box holding gg/aa/yyyy, usually with a calendar icon,
                often showing an empty mask like __/__/____ .
              * account_picker — a dropdown whose options carry long runs of
                digits (an account number or IBAN). A dropdown of words is
                never an account picker.
              * dropdown — anything else you open and choose from. Write the
                option texts EXACTLY, and leave out the "Seçiniz" placeholder.
                TO LEARN THE OPTIONS YOU HAVE TO OPEN IT: a closed dropdown on
                this application contains nothing, because the list is built
                when you click it. Open it, read the list, and close it again
                if you were not asked to choose. note_field sends you back for
                a dropdown with no options unless you say why in `uncertain` —
                "opened it, empty until Bayi Kodu is chosen" is a real answer,
                silence is not.
              * checkbox — a box the user ticks, captioned on these screens
                with a whole sentence ("Garanti tutarı girmek istiyorum.").
                Record every one you find. Never call it "text": nothing can
                be typed into it.
              * text — what is left.
              Where the screen does not let you tell these apart, give your
              guess and say so in that field's "uncertain" argument.
            - success_message: if the instruction took you through the final
              confirm button, you WILL end up on a result screen — read the
              banner there and pass it verbatim. It is the one thing in the
              catalog that cannot be learned by looking at the form, and
              without it every scenario ends without proving the transaction
              completed. If the page goes blank, check the browser tabs: the
              result may have opened in a new one.
              Never invent it. If you did not reach that screen, leave it out.
            - THE CONFIRMATION SCREEN IS A TOOL CALL OF ITS OWN. A submit
              usually lands on a screen that shows your values back to you
              before the final confirm. STOP there, read it whole — every row
              of every summary table, not just the first few — and call
              note_confirmation once with every label printed on it.
              This is what makes fields "verifiable", and it matters more than
              anything else you record: a scenario built from a catalog without
              it fills the form, presses the confirm button and checks NOTHING
              but the banner. Then a field that silently stayed empty still
              passes. Fields the application filled in ITSELF are the most
              valuable of all — verifying those is the only way anyone ever
              tests that the bank looked the value up correctly.
              * If you forget, or realise only later, put the same labels in
                submit_catalog's "confirmedLabels". Losing them is the one
                mistake that cannot be repaired afterwards — the screen is
                gone by then.
              * If you were not instructed to submit you never reach that
                screen: do not call it, and do not submit just to find out.
              * A read-only detail screen IS a summary screen, but there is
                nothing on it you filled in, so note_confirmation has nothing
                to flag. Record its rows with note_display instead — label and
                printed value, one call each.
            - "navigation" must describe how to REACH the screen, step by step,
              INCLUDING the parts that were not obvious:
              * if you had to switch a tab or a filter before the list showed
                what you wanted, record it as entry_tab. A list that opens on
                one filter and holds the records under another is the quietest
                trap in this application: a scenario that skips the switch
                picks the right row of the wrong list and passes every step.
              * if you had to pick a row from a grid before a link became
                available, record it as entry_row — a scenario that skips it
                opens the wrong record or nothing at all.
              * if the button you pressed did not open the form but a FORK —
                a small group of choices deciding WHICH screen comes next —
                record the option you took as entry_choice. The other branch
                is a different screen with different fields, so this is part
                of the route, not a field of the form. Without it the form
                never opens at all and every field times out in turn.
            - Catalogue ONLY the screen you were asked to examine. Do not
              wander into other screens to make the catalog look complete.

            === CATALOG SCHEMA ===
            """;

    /**
     * The closing instruction, appended to the ANALYST'S text rather than kept
     * in the system prompt — and it carries no new information at all.
     *
     * Everything in it is already said above, in CATALOG_RULES, twice: once as
     * "call submit_catalog with how the screen is reached" and once as the
     * navigation rule that names entry_tab and entry_row outright. Saying it a
     * third time in the same place would be the mistake this project keeps
     * relearning. What changes here is POSITION: this is the last thing the
     * model reads before it starts, where "do this at the end" belongs. Cost,
     * four lines.
     *
     * It is appended in the /catalog handler and NOT inside compose(), because
     * /explore builds its tools with no sink — there is no submit_catalog on
     * that path, and telling a model to call a tool it was not given is how a
     * run ends in an invented tool name.
     *
     * NO REAL CAPTION MAY APPEAR HERE. The composed instruction is the very
     * text BudgetedTool searches in unmentioned(), banned() and required().
     * Writing "Onay" into it would quietly licence pressing an Onay button in
     * a flow whose analyst never mentioned one — the guard would see the word
     * and let it through. Hence "son onay düğmesi" and "değerlerini tekrar
     * gösteren ekran" instead of naming anything.
     */
    static final String CLOSING = """


            Bitirmek için submit_catalog'u çağır ve HER argümanını doldur:
            ekrana nasıl gittiğini (menü, alt menü, varsa geçtiğin sekme, varsa
            ızgaradan seçtiğin satır, varsa formu açan bağlantı), bastığın
            düğmeleri, son ekranda okuduğun metni, ve değerlerini tekrar
            gösteren ekranda gördüğün tüm etiketleri. Bir şey gerçekten yoksa
            "" geç — atlamak yok.
            """;
}
