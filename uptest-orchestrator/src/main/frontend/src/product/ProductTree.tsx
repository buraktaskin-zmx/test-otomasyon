import { useState } from "react";
import type { ProductRegistry, ProductSummary } from "../api/types";

/** What the Kayıtlı Senaryolar screen is currently showing. `flow` = that
 *  screen's table; a `path` as well = one scenario open. */
export interface TreeSelection {
  app: string;
  product: string;
  flow: string | null;
  path: string | null;
}

/*
  İş alanı → ekran. Two levels, on purpose.

  The category row opens and closes; a screen row only selects, and its
  scenarios appear in the table on the right. The earlier tree also listed
  every scenario under its screen, which put the same fifty rows in the
  sidebar AND the table, and pushed every other screen off the panel.

  The product row is not shown: the breadcrumb above the table already names
  the product. Screens with no scenarios are not shown either -- this panel
  answers "what can I run", and a declared-but-empty screen is a to-do that
  ProductPicker lists at generation time, where it matters.
*/
export function ProductTree({
  products,
  registryOf,
  selection,
  onSelect,
  expanded,
  onToggle,
  onCreateMenu,
  onAddScreen,
}: {
  products: ProductSummary[];
  registryOf: (app: string, product: string) => ProductRegistry | null;
  selection: TreeSelection | null;
  onSelect: (next: TreeSelection) => void;
  /** Category keys currently open. See `categoryKey`. */
  expanded: ReadonlySet<string>;
  onToggle: (key: string) => void;
  /** Resolve to null when created, otherwise the reason it was not. */
  onCreateMenu: (name: string, app: string) => Promise<string | null>;
  onAddScreen: (app: string, product: string, name: string) => Promise<string | null>;
}) {
  const groups = groupByCategory(products);

  return (
    <nav className="tree" aria-label="Kayıtlı senaryolar">
      <div className="tree-head">
        <span>KAYITLI SENARYOLAR</span>
      </div>

      <AddRow
        label="Ana menü"
        placeholder="Örn: NAKİT YÖNETİMİ"
        withApp
        onSubmit={(name, app) => onCreateMenu(name, app)}
      />

      {products.length === 0 ? (
        <p className="tree-empty">
          Henüz ürün yok. <code>products/&lt;app&gt;/&lt;ürün&gt;/product.yaml</code> açın.
        </p>
      ) : null}

      {groups.map((group) => {
        const catKey = categoryKey(group.name);
        // A product with no category has no heading to collapse under, so its
        // screens are always shown -- otherwise they would be unreachable.
        const catOpen = !group.name || expanded.has(catKey);

        const screens = group.products.flatMap((product) =>
          (registryOf(product.app, product.product)?.screens ?? [])
            // Every DECLARED screen (a new one starts empty), plus any folder
            // with scenarios that product.yaml forgot to declare.
            .filter((screen) => screen.declared || screen.scenarioCount > 0)
            .map((screen) => ({ product, screen })));

        return (
          <div key={group.name || "(kategorisiz)"} className="tree-group">
            {group.name ? (
              <button
                type="button"
                className="tree-row is-category unstyled-button"
                aria-expanded={catOpen}
                onClick={() => onToggle(catKey)}
              >
                <span className="tree-caret" aria-hidden="true">{catOpen ? "▼" : "▶"}</span>
                <span className="tree-label">{group.name.toLocaleUpperCase("tr-TR")}</span>
              </button>
            ) : null}

            {catOpen
              ? screens.map(({ product, screen }) => {
                  const selected = selection?.app === product.app
                    && selection?.product === product.product
                    && selection?.flow === screen.flow;

                  return (
                    <button
                      key={product.app + "/" + product.product + "/" + screen.flow}
                      type="button"
                      className={"tree-row is-screen unstyled-button"
                        + (selected ? " is-active" : "")}
                      onClick={() => onSelect({
                        app: product.app, product: product.product,
                        flow: screen.flow, path: null,
                      })}
                    >
                      <span className="tree-caret" aria-hidden="true">▸</span>
                      <span className="tree-label">
                        {screen.name || screen.flow}
                        {screen.declared ? null : (
                          <span className="tree-note"
                                title="product.yaml bu ekranı listelemiyor">
                            bildirilmemiş
                          </span>
                        )}
                      </span>
                      <span className="tree-count">{screen.scenarioCount}</span>
                    </button>
                  );
                })
              : null}

            {/* New screens go on the category's first product -- a fresh ana
                menü has exactly one. */}
            {catOpen && group.products[0] ? (
              <AddRow
                label="Ekran ekle"
                placeholder="Örn: Ödeme Oluştur"
                nested
                onSubmit={(name) => onAddScreen(
                  group.products[0]!.app, group.products[0]!.product, name)}
              />
            ) : null}
          </div>
        );
      })}
    </nav>
  );
}

/** "+ …" that opens into a one-line form in place. */
function AddRow({
  label,
  placeholder,
  withApp,
  nested,
  onSubmit,
}: {
  label: string;
  placeholder: string;
  withApp?: boolean;
  nested?: boolean;
  onSubmit: (name: string, app: string) => Promise<string | null>;
}) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [app, setApp] = useState("ngc");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const close = () => {
    setOpen(false);
    setName("");
    setError(null);
  };

  const submit = async () => {
    if (!name.trim()) return;
    setBusy(true);
    const refused = await onSubmit(name.trim(), app);
    setBusy(false);
    if (refused) setError(refused);
    else close();
  };

  if (!open) {
    return (
      <button
        type="button"
        className={"tree-row tree-add unstyled-button" + (nested ? " is-nested" : "")}
        onClick={() => setOpen(true)}
      >
        + {label}
      </button>
    );
  }

  return (
    <div className={"tree-add-form" + (nested ? " is-nested" : "")}>
      <input
        className="search"
        autoFocus
        value={name}
        placeholder={placeholder}
        disabled={busy}
        onChange={(event) => setName(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === "Enter") void submit();
          if (event.key === "Escape") close();
        }}
      />
      {withApp ? (
        <select className="search" value={app} disabled={busy}
                onChange={(event) => setApp(event.target.value)}>
          <option value="ngc">NGC</option>
          <option value="ngi">NGI</option>
        </select>
      ) : null}
      <div className="tree-add-actions">
        <button type="button" className="btn btn-ghost" disabled={busy} onClick={close}>
          Vazgeç
        </button>
        <button type="button" className="btn btn-primary" disabled={busy || !name.trim()}
                onClick={() => void submit()}>
          {busy ? "Ekleniyor…" : "Ekle"}
        </button>
      </div>
      {error ? <p className="product-warn">{error}</p> : null}
    </div>
  );
}

export const categoryKey = (name: string) => "cat:" + name;

/** Grouped by business area, with one deliberate hole: a product that
 *  declares no category gets an EMPTY group name and renders at the top
 *  level with no heading above it. Inventing one ("Sınıflandırılmamış")
 *  printed a word nobody had chosen as though somebody had. */
function groupByCategory(products: ProductSummary[]) {
  const byName = new Map<string, ProductSummary[]>();
  for (const product of products) {
    const name = product.category?.trim() ?? "";
    const bucket = byName.get(name);
    if (bucket) bucket.push(product);
    else byName.set(name, [product]);
  }
  return [...byName.entries()].map(([name, members]) => ({ name, products: members }));
}
