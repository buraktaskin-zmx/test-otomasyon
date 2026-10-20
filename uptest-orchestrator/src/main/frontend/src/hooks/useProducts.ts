import { useCallback, useEffect, useState } from "react";
import {
  getProduct, getProductScenarios, getProducts, getScenario, saveScenarioFile,
} from "../api/endpoints";
import type {
  ProductDetail,
  ProductRegistry,
  ProductSummary,
  ScenarioDetail,
} from "../api/types";

/*
  The product list, plus the one product currently open.

  Shaped like useCatalogs for the same reason it was: three panels pick a
  product and one response serves all of them. The split between the summary
  list and the detail fetch is not symmetry for its own sake -- a product may
  carry fifty rules, and loading every rule of every product so that a
  dropdown can show four names would put the whole rule book on the wire
  before anyone has chosen anything.

  `details` is a cache keyed "app/product": an analyst comparing two products
  switches back and forth, and refetching a file that changes on the timescale
  of a code review is waste. `reload` clears it, which is also how the Ürün
  panel's refresh picks up an edit someone just made to rules.yaml.
*/
export function useProducts() {
  const [products, setProducts] = useState<ProductSummary[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [details, setDetails] = useState<Record<string, ProductDetail>>({});
  const [failed, setFailed] = useState<Record<string, string>>({});

  const reload = useCallback(async () => {
    setDetails({});
    setFailed({});
    const result = await getProducts();
    if (!result.ok) {
      // No products is a legitimate state -- nobody has written one yet --
      // and so is an orchestrator that predates this endpoint. Neither is
      // worth a toast: the picker simply offers "(ürünsüz)".
      setLoaded(true);
      return;
    }
    setProducts(result.body.products);
    setLoaded(true);
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  const open = useCallback(
    async (app: string, product: string): Promise<ProductDetail | null> => {
      if (!app || !product) return null;
      const key = app + "/" + product;
      const cached = details[key];
      if (cached) return cached;

      const result = await getProduct(app, product);
      if (!result.ok) {
        // The 404 body carries `detail` -- the parser's own account of what
        // is wrong with the files. Kept rather than swallowed: it is the
        // only way an analyst learns why the product they just wrote is
        // missing from the picker.
        const body = result.body as { detail?: string } | null;
        setFailed((was) => ({ ...was, [key]: body?.detail ?? result.error }));
        return null;
      }
      setDetails((was) => ({ ...was, [key]: result.body }));
      return result.body;
    },
    [details],
  );

  const detail = useCallback(
    (app: string, product: string) => details[app + "/" + product] ?? null,
    [details],
  );

  const problem = useCallback(
    (app: string, product: string) => failed[app + "/" + product] ?? null,
    [failed],
  );

  /* ------------------------- the scenario registry -------------------- */

  const [registries, setRegistries] = useState<Record<string, ProductRegistry>>({});
  const [loadingRegistry, setLoadingRegistry] = useState("");

  /**
   * Deliberately NOT cached the way `details` is.
   *
   * A .feature file is edited in an IDE while this screen is open, so the
   * table has to re-read on every visit; a step count from five minutes ago
   * is the kind of wrong that nobody notices. The previous answer is kept in
   * state only so the rows do not blank out while the new one is in flight.
   */
  const openRegistry = useCallback(
    async (app: string, product: string): Promise<ProductRegistry | null> => {
      if (!app || !product) return null;
      const key = app + "/" + product;
      setLoadingRegistry(key);
      const result = await getProductScenarios(app, product);
      setLoadingRegistry((busy) => (busy === key ? "" : busy));
      if (!result.ok) {
        const body = result.body as { detail?: string } | null;
        setFailed((was) => ({ ...was, [key]: body?.detail ?? result.error }));
        return null;
      }
      setRegistries((was) => ({ ...was, [key]: result.body }));
      return result.body;
    },
    [],
  );

  const registry = useCallback(
    (app: string, product: string) => registries[app + "/" + product] ?? null,
    [registries],
  );

  const registryBusy = useCallback(
    (app: string, product: string) => loadingRegistry === app + "/" + product,
    [loadingRegistry],
  );

  /* ------------------------- one scenario ----------------------------- */

  const [scenario, setScenario] = useState<ScenarioDetail | null>(null);
  const [scenarioBusy, setScenarioBusy] = useState(false);

  const openScenario = useCallback(
    async (app: string, product: string, path: string) => {
      setScenarioBusy(true);
      // Cleared first: showing the PREVIOUS scenario's Gherkin under the new
      // one's title for a moment is the kind of wrong a reader believes.
      setScenario(null);
      const result = await getScenario(app, product, path);
      setScenarioBusy(false);
      setScenario(result.ok ? result.body : null);
    },
    [],
  );

  const closeScenario = useCallback(() => setScenario(null), []);

  /** Writes the file, shows it as written, and re-reads the product's table
   *  so a changed title or description is not stale there. */
  const saveScenario = useCallback(
    async (app: string, product: string, path: string, feature: string) => {
      const result = await saveScenarioFile(app, product, path, feature);
      if (result.ok) {
        setScenario(result.body);
        void openRegistry(app, product);
      }
      return result;
    },
    [openRegistry],
  );

  return {
    products, loaded, reload, open, detail, problem,
    openRegistry, registry, registryBusy,
    scenario, scenarioBusy, openScenario, closeScenario, saveScenario,
  };
}
