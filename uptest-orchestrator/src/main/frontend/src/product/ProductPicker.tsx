import { useEffect } from "react";
import type { ProductSummary } from "../api/types";
import type { useProducts } from "../hooks/useProducts";

/** What a generation panel needs to know about the product side. */
export interface ProductSelection {
  /** Directory name, e.g. "maas-odemeleri". "" means no product. */
  product: string;
  /** "app/flow", exactly as the catalog list spells it. */
  catalog: string;
  /** The rule ids ticked for the NEXT generation. */
  ruleIds: string[];
}

export const NO_PRODUCT: ProductSelection = { product: "", catalog: "", ruleIds: [] };

/*
  Ürün → Ekran → Kurallar, shared by AI Üret, Çoklu Üret and Chat.

  Those three panels each wrote the catalog <select> out in full, in
  byte-identical copies; this closes that too. But the reason it exists is the
  rule list underneath.

  TICKING IS THE POINT. The prompt already carries eleven hard rules, the live
  step list and the entire catalog, and this project has measured what distance
  does to an instruction -- "the prompt is read ONCE, and by field eleven it is
  far away". Pushing a whole rule book in on every generation would dilute the
  part that currently works. So the analyst chooses, and what they chose is
  visible on screen rather than applied invisibly: an unseen input is the most
  dangerous kind, because a silent wrong answer is the hardest to notice.

  Note what is NOT filtered by ticking: the validator. It sees the product's
  whole message set either way, or a draft accepted during generation would be
  refused by the identical check at the run gate.
*/
export function ProductPicker({
  idPrefix,
  products,
  catalogs,
  value,
  onChange,
  disabled,
}: {
  idPrefix: string;
  products: ReturnType<typeof useProducts>;
  catalogs: string[];
  value: ProductSelection;
  onChange: (next: ProductSelection) => void;
  disabled?: boolean;
}) {
  const chosen = products.products.find((p) => p.product === value.product) ?? null;
  const detail = chosen ? products.detail(chosen.app, chosen.product) : null;
  const problem = chosen ? products.problem(chosen.app, chosen.product) : null;

  // Pulled when the product changes, never on every render: useProducts
  // caches by "app/product", so this settles after one request per product.
  useEffect(() => {
    if (chosen) void products.open(chosen.app, chosen.product);
  }, [chosen, products]);

  /* NOTHING is ticked by default, and that was not the first answer.
     "All confirmed rules on" is right for a product with four of them and
     wrong for a real one: Maaş Ödemeleri declares fifty, forty-two of them
     confirmed, and defaulting to all forty-two would push the entire rule
     book into every prompt -- the dilution this picker exists to prevent.
     A rule reaches the model because someone decided it was relevant to THIS
     scenario, which is a judgement no default can make. */

  const visibleCatalogs = chosen ? forProduct(chosen, catalogs) : catalogs;

  const pickProduct = (next: string) => {
    const product = products.products.find((p) => p.product === next) ?? null;
    // The screen is cleared when it does not belong to the new product.
    // Leaving a stale one would generate against a catalog the chosen rule
    // book says nothing about, which reads as the rules being ignored.
    const keepsCatalog = product === null || forProduct(product, catalogs).includes(value.catalog);
    onChange({
      product: next,
      catalog: keepsCatalog ? value.catalog : "",
      ruleIds: [],
    });
  };

  const toggleRule = (id: string) => {
    const next = value.ruleIds.includes(id)
      ? value.ruleIds.filter((other) => other !== id)
      : [...value.ruleIds, id];
    onChange({ ...value, ruleIds: next });
  };

  return (
    <>
      <label className="suggest-label" htmlFor={idPrefix + "Product"}>Ürün</label>
      <select
        id={idPrefix + "Product"}
        className="search"
        value={value.product}
        disabled={disabled}
        onChange={(event) => pickProduct(event.target.value)}
      >
        <option value="">(ürünsüz)</option>
        {products.products.map((product) => (
          <option key={product.app + "/" + product.product} value={product.product}>
            {product.name}
          </option>
        ))}
      </select>

      <label className="suggest-label" htmlFor={idPrefix + "Catalog"}>Ekran</label>
      <select
        id={idPrefix + "Catalog"}
        className="search"
        value={value.catalog}
        disabled={disabled}
        onChange={(event) => onChange({ ...value, catalog: event.target.value })}
      >
        <option value="">
          {visibleCatalogs.length === 0 ? "(katalog bulunamadı)" : "Seçiniz…"}
        </option>
        {visibleCatalogs.map((name) => (
          <option key={name} value={name}>{name}</option>
        ))}
      </select>

      {chosen && chosen.missingCatalogs.length > 0 ? (
        <p className="product-gap">
          Kataloğu olmayan {chosen.missingCatalogs.length} ekran:{" "}
          {chosen.missingCatalogs.join(", ")} — önce “Ekranı Keşfet”.
        </p>
      ) : null}

      {problem ? <p className="product-warn">Ürün okunamadı: {problem}</p> : null}

      {chosen?.hasOpenQuestions ? (
        <p className="product-warn">
          Bu üründe cevaplanmamış sorular var. “Ürün” panelinden okuyun —
          oradaki konular hakkında senaryo üretmeyin, model varsayım uydurur.
        </p>
      ) : null}

      {detail && detail.rules.length > 0 ? (
        <>
          <span className="suggest-label">Uygulanacak kurallar</span>
          <div className="rule-list">
            {detail.rules.map((rule) => (
              <label key={rule.id} className="rule-row">
                <input
                  type="checkbox"
                  checked={value.ruleIds.includes(rule.id)}
                  disabled={disabled}
                  onChange={() => toggleRule(rule.id)}
                />
                <span className="rule-id">{rule.id}</span>
                <span className="rule-title">{rule.title || "(başlıksız)"}</span>
                {rule.confirmed ? null : <span className="rule-guess">VARSAYIM</span>}
              </label>
            ))}
          </div>
        </>
      ) : null}

      {detail && detail.rules.length === 0 ? (
        <p className="suggest-hint">
          Bu ürünün henüz hiç kuralı yazılmamış. <code>products/{detail.app}/
          {detail.product}/rules.yaml</code> dosyasına ekleyin.
        </p>
      ) : null}
    </>
  );
}

/** The catalogs this product claims, in the list's own "app/flow" spelling. */
function forProduct(product: ProductSummary, catalogs: string[]): string[] {
  const owned = new Set(product.flows.map((flow) => product.app + "/" + flow));
  return catalogs.filter((name) => owned.has(name));
}
