import { readinessLabel } from "./readiness";
import type { ProductRegistry, ProductSummary } from "../api/types";

/** What the Kayıtlı Senaryolar screen is currently showing. `flow` null = the
 *  product's own overview; a flow = that screen's table; a `path` as well =
 *  one scenario open. */
export interface TreeSelection {
  app: string;
  product: string;
  flow: string | null;
  path: string | null;
}

/*
  İş alanı → ürün → ekran → senaryo.

  AÇMA/KAPAMA SEÇİMDEN AYRI. The first version expanded a node by selecting
  it, which meant a node could never be closed again and the tree only grew:
  with fifty scenarios under one screen, opening a second screen pushed the
  first one off the panel. Now the caret toggles and the label selects, which
  is what every tree does and the only arrangement where "show me less" is
  expressible at all.

  Nested buttons are invalid HTML, so a row is a div holding two buttons --
  the caret and the label. That is also why the caret is a real button rather
  than a decorated span: it is clicked, so it has to be reachable by keyboard.
*/
export function ProductTree({
  products,
  registryOf,
  selection,
  onSelect,
  expanded,
  onToggle,
}: {
  products: ProductSummary[];
  registryOf: (app: string, product: string) => ProductRegistry | null;
  selection: TreeSelection | null;
  onSelect: (next: TreeSelection) => void;
  /** Node keys currently open. See `categoryKey` / `productKey` / `screenKey`. */
  expanded: ReadonlySet<string>;
  onToggle: (key: string) => void;
}) {
  const groups = groupByCategory(products);

  return (
    <nav className="tree" aria-label="Kayıtlı senaryolar">
      <div className="tree-head">
        <span>KAYITLI SENARYOLAR</span>
        <span className="tree-count">{products.length}</span>
      </div>

      {products.length === 0 ? (
        <p className="tree-empty">
          Henüz ürün yok. <code>products/&lt;app&gt;/&lt;ürün&gt;/product.yaml</code> açın.
        </p>
      ) : null}

      {groups.map((group) => {
        const catKey = categoryKey(group.name);
        // A product with no category has no heading to collapse under, so it
        // is always shown -- otherwise it would be unreachable.
        const catOpen = !group.name || expanded.has(catKey);

        return (
          <div key={group.name || "(kategorisiz)"} className="tree-group">
            {group.name ? (
              <div className="tree-row is-category">
                <Caret open={catOpen} onClick={() => onToggle(catKey)} />
                <span className="tree-label">{group.name.toUpperCase()}</span>
                <span className="tree-count">{group.total}</span>
              </div>
            ) : null}

            {catOpen
              ? group.products.map((product) => {
                  const registry = registryOf(product.app, product.product);
                  const prodKey = productKey(product.app, product.product);
                  const prodOpen = expanded.has(prodKey);
                  const prodSelected = selection?.app === product.app
                    && selection?.product === product.product
                    && selection?.flow === null;

                  // Declared flows plus any folder the registry found that
                  // nobody declared -- the second only exists once the
                  // product has been read, and it is the case worth seeing.
                  const screens = registry
                    ? registry.screens
                    : product.flows.map((flow) => ({
                        flow, catalog: null, declared: true, scenarioCount: 0,
                      }));

                  return (
                    <div key={prodKey}>
                      <div className={"tree-row is-product"
                        + (prodSelected ? " is-active" : "")}>
                        <Caret open={prodOpen} onClick={() => onToggle(prodKey)} />
                        <button
                          type="button"
                          className="tree-main unstyled-button"
                          onClick={() => {
                            if (!prodOpen) onToggle(prodKey);
                            onSelect({
                              app: product.app, product: product.product,
                              flow: null, path: null,
                            });
                          }}
                        >
                          {product.name}
                        </button>
                        <span className="tree-count">{product.scenarioCount}</span>
                      </div>

                      {prodOpen
                        ? screens.map((screen) => {
                            const scrKey = screenKey(product.app, product.product, screen.flow);
                            const scrOpen = expanded.has(scrKey);
                            const scrSelected = selection?.flow === screen.flow
                              && selection?.product === product.product
                              && selection?.path === null;
                            const rows = (registry?.scenarios ?? [])
                              .filter((row) => row.flow === screen.flow);

                            return (
                              <div key={scrKey}>
                                <div className={"tree-row is-screen"
                                  + (scrSelected ? " is-active" : "")}>
                                  <Caret open={scrOpen} onClick={() => onToggle(scrKey)} />
                                  <button
                                    type="button"
                                    className="tree-main unstyled-button"
                                    onClick={() => {
                                      if (!scrOpen) onToggle(scrKey);
                                      onSelect({
                                        app: product.app, product: product.product,
                                        flow: screen.flow, path: null,
                                      });
                                    }}
                                  >
                                    {screen.flow}
                                    {screen.declared ? null : (
                                      <span className="tree-note"
                                            title="product.yaml bu ekranı listelemiyor">
                                        bildirilmemiş
                                      </span>
                                    )}
                                  </button>
                                  <span className="tree-count">{screen.scenarioCount}</span>
                                </div>

                                {/* The readiness square is the one status
                                    worth repeating here: "which of these can
                                    I run today" is what this panel is asked
                                    most, and a colour answers it without
                                    opening a row. */}
                                {scrOpen
                                  ? rows.map((row) => (
                                      <button
                                        key={row.path}
                                        type="button"
                                        className={"tree-row is-scenario unstyled-button"
                                          + (selection?.path === row.path ? " is-active" : "")}
                                        onClick={() => onSelect({
                                          app: product.app, product: product.product,
                                          flow: screen.flow, path: row.path,
                                        })}
                                        title={row.description || row.title}
                                      >
                                        <span
                                          className={"tree-dot is-"
                                            + readinessLabel(row.readiness, row.needsNone).kind}
                                          aria-hidden="true"
                                        />
                                        <span className="tree-label">{row.title}</span>
                                      </button>
                                    ))
                                  : null}
                              </div>
                            );
                          })
                        : null}
                    </div>
                  );
                })
              : null}
          </div>
        );
      })}
    </nav>
  );
}

function Caret({ open, onClick }: { open: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      className="tree-caret unstyled-button"
      aria-expanded={open}
      aria-label={open ? "Kapat" : "Aç"}
      onClick={onClick}
    >
      {open ? "▼" : "▶"}
    </button>
  );
}

export const categoryKey = (name: string) => "cat:" + name;
export const productKey = (app: string, product: string) => "prod:" + app + "/" + product;
export const screenKey = (app: string, product: string, flow: string) =>
  "screen:" + app + "/" + product + "/" + flow;

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
  return [...byName.entries()].map(([name, members]) => ({
    name,
    products: members,
    total: members.reduce((sum, product) => sum + product.scenarioCount, 0),
  }));
}
