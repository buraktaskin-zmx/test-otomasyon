import { useEffect, useState } from "react";
import { PanelHead } from "./PanelHead";
import { ProductTree } from "../../product/ProductTree";
import type { TreeSelection } from "../../product/ProductTree";
import { ScenarioDetail } from "../../product/ScenarioDetail";
import { ScenarioTable } from "../../product/ScenarioTable";
import type { useProducts } from "../../hooks/useProducts";
import type { Origin } from "../../api/types";

/*
  Kayıtlı Senaryolar — ürün > ekran > senaryo kütüğü.

  Read-only, and that is a decision rather than an unfinished half. Rules,
  test data and scenarios all live in the repository, where git gives review,
  history and a diff, and where a reviewer can refuse a change. A Save button
  here would turn them into free text arriving over HTTP.

  What the screen is FOR is the two joins nothing else can show. A .feature
  file on disk states its steps; it cannot state that the rule it claims to
  cover was deleted last week, or that the alias it needs is still nobody's.
  Without those columns a registry is a directory listing in a nicer font.
*/
export function ProductPanel({
  active,
  products,
  onRun,
}: {
  active: boolean;
  products: ReturnType<typeof useProducts>;
  /** Hands a registered scenario to the ordinary run path. The registry does
   *  not grow a second way to start a run -- there is one gate, and it
   *  re-validates. */
  onRun: (feature: string, origin: Origin) => void;
}) {
  const [selection, setSelection] = useState<TreeSelection | null>(null);

  /* Which tree nodes are open. Kept apart from `selection` because the first
     version expanded by selecting and so could never close anything -- with
     fifty scenarios under one screen the panel only ever grew. */
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(new Set());
  const toggle = (key: string) =>
    setExpanded((was) => {
      const next = new Set(was);
      if (!next.delete(key)) next.add(key);
      return next;
    });

  const registry = selection
    ? products.registry(selection.app, selection.product)
    : null;
  const busy = selection
    ? products.registryBusy(selection.app, selection.product)
    : false;
  const problem = selection
    ? products.problem(selection.app, selection.product)
    : null;

  /* Re-read on every product change rather than trusting a cache: the
     .feature files are edited by hand in an IDE while this screen is open,
     and a step count from five minutes ago is the kind of wrong nobody
     notices. */
  useEffect(() => {
    if (selection) void products.openRegistry(selection.app, selection.product);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selection?.app, selection?.product]);

  /* Changing screen closes whatever scenario was open. Without this the
     detail view would survive into a screen the file does not belong to,
     and the breadcrumb above it would be lying. */
  useEffect(() => {
    if (selection?.path) {
      void products.openScenario(selection.app, selection.product, selection.path);
    } else {
      products.closeScenario();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selection?.app, selection?.product, selection?.flow, selection?.path]);

  const screen = selection?.flow
    ? registry?.screens.find((candidate) => candidate.flow === selection.flow) ?? null
    : null;

  const rows = selection?.flow
    ? (registry?.scenarios ?? []).filter((row) => row.flow === selection.flow)
    : registry?.scenarios ?? [];

  return (
    <div id="side-product" className={"side-panel product-panel" + (active ? " is-active" : "")}>
      <PanelHead title="Kayıtlı Senaryolar">
        <button className="btn btn-ghost" type="button" onClick={() => void products.reload()}>
          Yenile
        </button>
      </PanelHead>

      <div className="registry-body">
        <ProductTree
          products={products.products}
          registryOf={products.registry}
          selection={selection}
          onSelect={setSelection}
          expanded={expanded}
          onToggle={toggle}
        />

        <section className="registry-main">
          {selection === null ? (
            <p className="registry-empty">
              Soldan bir ürün ya da ekran seçin. Ekran seçtiğinizde o ekrana kayıtlı
              senaryolar, bağlı oldukları kurallar ve koşmak için gereken veri listelenir.
            </p>
          ) : (
            <>
              <div className="registry-crumbs">
                {registry?.category ? <span>{registry.category}</span> : null}
                <span>{registry?.name ?? selection.product}</span>
                {screen ? (
                  <span className="is-file">
                    {screen.catalog
                      ? "catalogs/" + screen.catalog + ".yaml"
                      : "katalog yok"}
                  </span>
                ) : null}
              </div>

              <h2 className="registry-title">
                {selection.flow ?? registry?.name ?? selection.product}
              </h2>

              {/* A read failure, and nothing else, is allowed between the
                  title and the scenarios. It is not decoration: without it
                  the table below falls back to "Bu ekranda kayıtlı senaryo
                  yok", which is a different claim and a false one.

                  Two paragraphs that used to live here are gone. Neither was
                  wrong, both were PERMANENT: no screen in this product has a
                  catalog yet, so "kataloğu henüz üretilmedi" printed three
                  lines above every table, every time, and an undeclared
                  folder was already named "bildirilmemiş" in the tree. A
                  warning that is always on is furniture, and this screen is
                  read by someone scanning fifty rows. The catalog fact still
                  travels in three words: the breadcrumb above says "katalog
                  yok", and ProductPicker names every screen missing one at
                  the moment it matters -- the generation that would have to
                  run without it. */}
              {problem ? <p className="product-warn">Okunamadı: {problem}</p> : null}

              {/* Scenarios at every level. Picking the PRODUCT used to open
                  an overview instead -- its description, its counts, its
                  fifty rules, the state machine, the roles and the whole of
                  open-questions.md dumped as preformatted text. All of it
                  real, none of it what the panel is called: a registry of
                  scenarios answering "which of these can I run". The product
                  row now lists every scenario it owns, across screens, and a
                  screen row narrows that to one.

                  Where the rest went, since it was not noise:
                  - the rules are in the generation panels' ProductPicker,
                    beside the checkbox that decides whether each one reaches
                    the model, which is the only moment they are acted on;
                  - open-questions.md, flow.md and roles.md are repository
                    files. This panel is read-only BECAUSE they live in git,
                    so an IDE already shows them with history and a diff --
                    rendering them here bought a second, worse reader. */}
              {selection.path ? (
                <ScenarioDetail
                  detail={products.scenario}
                  busy={products.scenarioBusy}
                  onBack={() => setSelection({ ...selection, path: null })}
                  onRun={(feature) => onRun(feature, {
                    app: selection.app,
                    flow: selection.flow ?? "",
                    intent: products.scenario?.description ?? "",
                    product: selection.product,
                  })}
                />
              ) : (
                <ScenarioTable
                  scenarios={rows}
                  busy={busy}
                  onOpen={(scenario) =>
                    setSelection({ ...selection, flow: scenario.flow, path: scenario.path })}
                />
              )}
            </>
          )}
        </section>
      </div>
    </div>
  );
}
