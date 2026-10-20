import type { Depth } from "../../lib/multiDepth";

/* index.html:122-140. Safe option ("Devam'da dur") checked by default. */
export function DepthChoiceGroup({ value, onChange }: { value: Depth; onChange: (depth: Depth) => void }) {
  return (
    <div className="choice-group">
      <label className="choice">
        <input
          type="radio"
          name="multiDepth"
          value="confirm"
          checked={value === "confirm"}
          onChange={() => onChange("confirm")}
        />
        <span>
          <b>Devam'da dur</b>
          <small>
            Formu doldurur, Devam'a basar, özet ekranındaki değerleri doğrular. Kayıt oluşturmaz.
          </small>
        </span>
      </label>
      <label className="choice">
        <input
          type="radio"
          name="multiDepth"
          value="submit"
          checked={value === "submit"}
          onChange={() => onChange("submit")}
        />
        <span>
          <b>Sonuna kadar — Onay'a bas</b>
          <small className="choice-warn">
            ⚠ Senaryolar test ortamında gerçek kayıt oluşturur, günceller veya siler.
          </small>
        </span>
      </label>
    </div>
  );
}
