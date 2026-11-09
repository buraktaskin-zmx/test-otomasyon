import { useRef } from "react";
import type { FlowTree } from "../api/types";

/*
  The context that is not a picture and not a drawing: what the application
  must DO, and what must already be true before it can be tested.

  Two boxes rather than one, which is the distinction this whole feature
  turns on. A RULE that fails is a DEFECT, so the scenario asserts it with a
  `verify`. A PRECONDITION that fails means the test COULD NOT RUN and the
  application is fine, so it goes in the "Ön koşul" section and is never
  verified. One box for both is how "fatura ödenmemiş olmalı" comes back as
  an expectation and a missing test fixture reads like a bug in the bank.

  Neither is an oracle. `rules.yaml` keeps that job -- its rules carry ids,
  scenarios bind to them, `SourceSnapshot` diffs them when they move and the
  validator enforces their `messages` and `degerler`. Free text can do none
  of that; it only steers the prompt, exactly as a node's `note` does. That
  cost is printed on the card rather than left to be discovered.

  A .txt is read in the BROWSER into the box, not uploaded. The analyst sees
  and can edit what will be sent before it is sent, no endpoint stores a file
  nothing later reads, and there is no third place a rule can hide.
*/
export function FlowRulesCard({
  flow,
  busy,
  onChange,
}: {
  flow: FlowTree;
  busy: boolean;
  onChange: (flow: FlowTree) => void;
}) {
  return (
    <section className="fc-editor">
      <h3 className="db-card-title">Kurallar ve ön koşullar</h3>

      <TextSource
        label="Kurallar"
        hint={"Her biri bir satır. Senaryo bunları `verify` ile doğrular: düşerse"
          + " uygulamada hata var demektir. Kimliği olmadığı için hiçbir senaryoya"
          + " bağlanmaz ve değiştiğinde kimse uyarılmaz — kalıcı olması"
          + " gerekenleri rules.yaml'a taşıyın."}
        placeholder={"ör. Tutar alanı boş bırakılırsa \"Toplam tutar giriniz\" yazar\n"
          + "ör. Vade tarihi bugünden önce seçilemez"}
        value={flow.rules}
        busy={busy}
        onChange={(text) => onChange({ ...flow, rules: text })}
      />

      <TextSource
        label="Ön Koşullar"
        hint={"Bunlar DOĞRULANMAZ: senaryonun \"Ön koşul\" bölümüne yazılır. Biri"
          + " tutmazsa test koşamamıştır, uygulama hatalı değildir. Kuralla aynı"
          + " kutuya yazılırsa model ikisini ayırt edemez ve eksik bir test"
          + " verisi kırmızı bir koşuya dönüşür."}
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

/** A textarea with its explanation beside it and a "load a .txt" button. */
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
      <span className="users-field-label">{label}</span>
      <textarea className="search fc-note" rows={4} value={value} disabled={busy}
                placeholder={placeholder}
                onChange={(event) => onChange(event.target.value)} />
      <span className="fc-source-hint">{hint}</span>
      <span className="fc-source-actions">
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
