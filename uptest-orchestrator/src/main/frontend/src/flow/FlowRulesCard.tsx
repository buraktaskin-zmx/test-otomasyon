import { useRef } from "react";
import type { FlowTree, ProductRule } from "../api/types";

/*
  Where the context that is NOT a picture comes from.

  Three sources, in descending order of how much the machine can do with
  them, and the order is the point:

  1. `rules.yaml` — the oracle. Every rule carries an id, a scenario binds to
     it, `SourceSnapshot` marks the scenario stale when the rule's wording
     moves, and the validator accepts a scenario asserting one of its
     `messages` or `degerler` because the rule wrote them down. Ticking boxes
     here chooses which of them reach the prompt; none ticked means all of
     them, which is what the server already means by an empty ruleIds.
  2. Free-text RULES — what the application must do, written in a hurry or
     pasted out of an analysis document before anyone has turned it into
     rules.yaml. The prompt carries it; nothing enforces it, nothing binds a
     scenario to it, nothing notices when it changes. That is the whole cost
     of the convenience and it is stated on the card.
  3. Free-text PRECONDITIONS — what has to be true before the test can run.

  2 and 3 are separate boxes rather than one, which is the distinction this
  feature turns on. A rule that fails is a DEFECT, so the scenario asserts it
  with a `verify`. A precondition that fails means the test COULD NOT RUN and
  the application is fine, so it goes in the "Ön koşul" section and is never
  verified. One box for both is how "fatura ödenmemiş olmalı" comes back as
  an expectation and a missing test fixture reads like a bug in the bank.

  A .txt is read in the BROWSER into the box, not uploaded. The analyst sees
  and can edit what will be sent before it is sent, no endpoint stores a file
  nothing later reads, and there is no third place a rule can hide.
*/
export function FlowRulesCard({
  flow,
  rules,
  picked,
  busy,
  onChange,
  onPick,
}: {
  flow: FlowTree;
  /** The product's rules.yaml, or empty when it has none. */
  rules: ProductRule[];
  /** Ticked rule ids; empty means every rule. */
  picked: string[];
  busy: boolean;
  onChange: (flow: FlowTree) => void;
  onPick: (ids: string[]) => void;
}) {
  const toggle = (id: string) =>
    onPick(picked.includes(id) ? picked.filter((p) => p !== id) : [...picked, id]);

  return (
    <section className="fc-editor">
      <div className="db-card-head">
        <h3 className="db-card-title">Kurallar ve ön koşullar</h3>
        <span className="db-card-aside">
          {rules.length > 0
            ? (picked.length === 0 ? rules.length + " kuralın hepsi" : picked.length + " kural seçili")
            : "rules.yaml yok"}
        </span>
      </div>

      {rules.length > 0 ? (
        <>
          <p className="db-note">
            Ürünün <code>rules.yaml</code> kuralları. Hiçbirini seçmezseniz hepsi
            gönderilir; seçerseniz yalnız seçtikleriniz. Prompt zaten adım listesini
            ve okunan ekranları taşıyor — uzaktaki bir kural, modelin okumadığı
            kuraldır.
          </p>
          <ul className="fc-rules">
            {rules.map((rule) => (
              <li key={rule.id}>
                <label>
                  <input type="checkbox" disabled={busy}
                         checked={picked.length === 0 || picked.includes(rule.id)}
                         onChange={() => toggle(rule.id)} />
                  <span className="rule-chip">{rule.id}</span>
                  <span>{rule.title}</span>
                  {!rule.confirmed ? <span className="db-risk">varsayım</span> : null}
                </label>
              </li>
            ))}
          </ul>
        </>
      ) : (
        <p className="db-note">
          Bu ürünün <code>rules.yaml</code> dosyası yok ya da kuralsız. Kimlikli
          kurallar oradan gelir ve senaryoya bağlanır; aşağıdaki serbest metin
          bağlanmaz.
        </p>
      )}

      <TextSource
        label="Ek kurallar — uygulamanın YAPMAK ZORUNDA olduğu şeyler"
        hint={"Her biri bir satır. Senaryo bunları `verify` ile doğrular: düşerse"
          + " uygulamada hata var demektir. Kimliği olmadığı için hiçbir senaryoya"
          + " bağlanmaz ve değiştiğinde kimse uyarılmaz — kalıcı olması gerekenleri"
          + " rules.yaml'a taşıyın."}
        placeholder={"ör. Tutar alanı boş bırakılırsa \"Toplam tutar giriniz\" yazar\n"
          + "ör. Vade tarihi bugünden önce seçilemez"}
        value={flow.rules}
        busy={busy}
        onChange={(text) => onChange({ ...flow, rules: text })}
      />

      <TextSource
        label="Ön koşullar — koşmadan ÖNCE ortamda doğru olması gerekenler"
        hint={"Bunlar DOĞRULANMAZ: senaryonun \"Ön koşul\" bölümüne yazılır. Biri"
          + " tutmazsa test koşamamıştır, uygulama hatalı değildir. Kuralla aynı"
          + " kutuya yazılırsa model ikisini ayırt edemez ve eksik bir test verisi"
          + " kırmızı bir koşuya dönüşür."}
        placeholder={"ör. QZ06395288 kullanıcısının Fatura Oluştur yetkisi var\n"
          + "ör. 93002 bayi kodu tanımlı ve aktif\n"
          + "ör. Listede en az bir ödenmemiş fatura var"}
        value={flow.preconditions}
        busy={busy}
        onChange={(text) => onChange({ ...flow, preconditions: text })}
      />
    </section>
  );
}

/** A textarea with a "load a .txt into it" button beside it. */
function TextSource({
  label, hint, placeholder, value, busy, onChange,
}: {
  label: string;
  hint: string;
  placeholder: string;
  value: string;
  busy: boolean;
  onChange: (text: string) => void;
}) {
  const input = useRef<HTMLInputElement>(null);

  /* Appended, not replaced: an analyst loading a second file, or loading one
     after typing, means "and these as well". Replacing would throw away work
     with no undo, and the box is right there to delete from. */
  const load = (file: File) => {
    const reader = new FileReader();
    reader.onload = () => {
      const text = String(reader.result ?? "").replace(/\r\n/g, "\n").trim();
      if (!text) return;
      onChange(value.trim() ? value.trimEnd() + "\n" + text : text);
    };
    reader.readAsText(file, "utf-8");
  };

  return (
    <label className="users-field">
      <span>{label}</span>
      <textarea className="search fc-note" rows={4} value={value} disabled={busy}
                placeholder={placeholder}
                onChange={(event) => onChange(event.target.value)} />
      <span className="db-note">{hint}</span>
      <span className="fc-editor-actions">
        <button type="button" className="btn btn-ghost" disabled={busy}
                onClick={() => input.current?.click()}>
          .txt yükle
        </button>
        {value.trim() ? (
          <button type="button" className="btn btn-ghost" disabled={busy}
                  onClick={() => onChange("")}>
            Temizle
          </button>
        ) : null}
        <input ref={input} type="file" accept=".txt,.md,text/plain" hidden
               onChange={(event) => {
                 const file = event.target.files?.[0];
                 if (file) load(file);
                 event.target.value = "";
               }} />
      </span>
    </label>
  );
}
