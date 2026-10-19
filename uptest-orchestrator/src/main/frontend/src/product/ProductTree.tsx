import type { ProductRegistry, ProductSummary } from "../api/types";

/** What the Ürünler screen is currently showing. `flow` null = the product's
 *  own overview; a flow = that screen's scenario table. */
export interface TreeSelection {
  app: string;
  product: string;
  flow: string | null;
}

/*
  İş alanı → ürün → ekran.

  Three levels because that is how the work is actually divided: a business
  area owns products, a product owns screens, a screen owns scenarios. The
  counts aggregate upward, so the top row answers "how much of this is
  covered at all" without opening anything.

  The screen list comes from the product SUMMARY (its declared flows), not
  from the registry, so the tree is complete before any scenario file has
  been read. Counts arrive later, once a product is opened -- a number is
  worth waiting for, a structure is not.
*/
export function ProductTree({
  products,
  registryOf,
  selection,
  onSelect,
}: {
  products: ProductSummary[];
  registryOf: (app: string, product: string) => ProductRegistry | null;
  selection: TreeSelection | null;
  onSelect: (next: TreeSelection) => void;
}) {
  const groups = groupByCategory(products);

  return (
    <nav className="tree" aria-label="Ürünler">
      <div className="tree-head">
        <span>ÜRÜNLER</span>
        <span className="tree-count">{products.length}</span>
      </div>

      {products.length === 0 ? (
        <p className="tree-empty">
          Henüz ürün yok. <code>products/&lt;app&gt;/&lt;ürün&gt;/product.yaml</code> açın.
        </p>
      ) : null}

      {groups.map((group) => (
        <div key={group.name} className="tree-group">
          <div className="tree-row is-category">
            <span className="tree-label">{group.name.toUpperCase()}</span>
            <span className="tree-count">{group.total}</span>
          </div>

          {group.products.map((product) => {
            const registry = registryOf(product.app, product.product);
            const open = selection?.app === product.app
              && selection?.product === product.product;
            // Declared flows plus any folder the registry found that nobody
            // declared -- the second only exists once the product is open,
            // and it is exactly the case worth surfacing.
            const screens = registry
              ? registry.screens
              : product.flows.map((flow) => ({
                  flow, catalog: null, declared: true, scenarioCount: 0,
                }));

            return (
              <div key={product.app + "/" + product.product}>
                <button
                  type="button"
                  className={"tree-row is-product unstyled-button"
                    + (open && selection?.flow === null ? " is-active" : "")}
                  onClick={() => onSelect({
                    app: product.app, product: product.product, flow: null,
                  })}
                >
                  <span className="tree-label">{product.name}</span>
                  <span className="tree-count">{product.scenarioCount}</span>
                </button>

                {open
                  ? screens.map((screen) => (
                      <button
                        key={screen.flow}
                        type="button"
                        className={"tree-row is-screen unstyled-button"
                          + (selection?.flow === screen.flow ? " is-active" : "")}
                        onClick={() => onSelect({
                          app: product.app, product: product.product, flow: screen.flow,
                        })}
                      >
                        <span className="tree-label">
                          {screen.flow}
                          {screen.declared ? null : (
                            <span className="tree-note" title="product.yaml bu ekranı listelemiyor">
                              bildirilmemiş
                            </span>
                          )}
                        </span>
                        <span className="tree-count">{screen.scenarioCount}</span>
                      </button>
                    ))
                  : null}
              </div>
            );
          })}
        </div>
      ))}
    </nav>
  );
}

/** Products that declare no category are grouped under one heading rather
 *  than floating above the others -- a tree with two different row shapes at
 *  the top level is harder to scan than one with a plain bucket. */
function groupByCategory(products: ProductSummary[]) {
  const byName = new Map<string, ProductSummary[]>();
  for (const product of products) {
    const name = product.category?.trim() || "Sınıflandırılmamış";
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
