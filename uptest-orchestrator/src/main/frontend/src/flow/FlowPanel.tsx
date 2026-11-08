import { useCallback, useEffect, useRef, useState } from "react";
import {
  deleteScreenshot, getFlow, putScreenshot, readScreens, saveCatalog, saveFlow,
} from "../api/endpoints";
import type { FlowNode, FlowTree, ProductSummary, ScreenRead } from "../api/types";
import { PanelHead } from "../workspace/side/PanelHead";
import { ConfirmDialog } from "../ui/ConfirmDialog";
import { useToast } from "../ui/Toast";
import { useAsyncAction } from "../hooks/useAsyncAction";
import { FlowCanvas, newEdge, nextNodeId, placeChild } from "./FlowCanvas";

const EMPTY: FlowTree = { nodes: [], edges: [], intent: "", outOfScope: "" };

/*
  Senaryo Üret — the analyst draws the flow, the vision model reads the
  screens, and the scenarios come from both plus the product's rules.

  Different from "AI ile Senaryo Üret" in two ways that matter: it is a FLOW
  rather than one screen, so it can produce journeys across screens; and it
  needs no running application, so tests can be written from a design mockup
  before the screen exists.

  The drawing is saved on the server, in the product's own folder — it is the
  documentation that replaces flow.md, and a canvas lost on F5 would be twenty
  minutes of work thrown away.
*/
export function FlowPanel({
  active,
  products,
}: {
  active: boolean;
  products: ProductSummary[];
}) {
  const toast = useToast();
  const [app, setApp] = useState("");
  const [product, setProduct] = useState("");
  const [flow, setFlow] = useState<FlowTree>(EMPTY);
  const [screenshots, setScreenshots] = useState<Record<string, boolean>>({});
  const [reads, setReads] = useState<Record<string, string>>({});
  const [visionReady, setVisionReady] = useState(false);
  const [selected, setSelected] = useState<string | null>(null);
  const [imageVersion, setImageVersion] = useState(1);
  const [deleting, setDeleting] = useState<FlowNode | null>(null);
  const [results, setResults] = useState<ScreenRead[] | null>(null);
  const [dirty, setDirty] = useState(false);
  const [loaded, setLoaded] = useState(false);

  const chosen = products.find((p) => p.app === app && p.product === product) ?? null;

  /* First product by default: the panel is useless without one, and asking
     the analyst to pick before they can see anything is a step for nothing. */
  useEffect(() => {
    if (!chosen && products.length > 0 && products[0]) {
      setApp(products[0].app);
      setProduct(products[0].product);
    }
  }, [chosen, products]);

  const load = useCallback(async () => {
    if (!app || !product) return;
    setLoaded(false);
    const result = await getFlow(app, product);
    if (result.ok) {
      setFlow(result.body.flow ?? EMPTY);
      setScreenshots(result.body.screenshots ?? {});
      setReads(result.body.reads ?? {});
      setVisionReady(result.body.visionReady);
      setResults(null);
      setSelected(null);
      setDirty(false);
    } else {
      toast("Akış okunamadı: " + result.error, true);
    }
    setLoaded(true);
  }, [app, product, toast]);

  useEffect(() => {
    if (active) void load();
  }, [active, load]);

  const change = (next: FlowTree) => {
    setFlow(next);
    setDirty(true);
  };

  const save = useAsyncAction(async () => {
    const result = await saveFlow(app, product, flow);
    if (result.ok) {
      setDirty(false);
      toast("Akış kaydedildi");
    } else {
      toast("Kaydedilemedi: " + result.error, true);
    }
  });

  /* ------------------------------ the tree ------------------------------ */

  const addRoot = () => {
    const id = nextNodeId(flow.nodes);
    const y = flow.nodes.length === 0 ? 40
      : Math.max(...flow.nodes.map((n) => n.y)) + 220;
    change({
      ...flow,
      nodes: [...flow.nodes, { id, title: "Yeni ekran", flow: "", note: "", x: 40, y }],
    });
    setSelected(id);
  };

  const addChild = (parentId: string, side: "right" | "down") => {
    const parent = flow.nodes.find((n) => n.id === parentId);
    if (!parent) return;
    const id = nextNodeId(flow.nodes);
    const { x, y } = placeChild(flow, parent, side);
    change({
      ...flow,
      nodes: [...flow.nodes, { id, title: "Yeni ekran", flow: "", note: "", x, y }],
      edges: [...flow.edges, newEdge(parentId, id, flow.edges)],
    });
    setSelected(id);
  };

  const removeNode = async (node: FlowNode) => {
    // The screenshot is a file; deleting the box has to take it with it, or
    // the folder fills with images nothing points at.
    if (screenshots[node.id]) await deleteScreenshot(app, product, node.id);
    const next: FlowTree = {
      ...flow,
      nodes: flow.nodes.filter((n) => n.id !== node.id),
      edges: flow.edges.filter((e) => e.from !== node.id && e.to !== node.id),
    };
    setFlow(next);
    setSelected(null);
    const result = await saveFlow(app, product, next);
    setDirty(!result.ok);
    setScreenshots((was) => ({ ...was, [node.id]: false }));
  };

  const patchNode = (id: string, patch: Partial<FlowNode>) =>
    change({ ...flow, nodes: flow.nodes.map((n) => (n.id === id ? { ...n, ...patch } : n)) });

  /* ---------------------------- screenshots ---------------------------- */

  const attach = useCallback(async (nodeId: string, file: Blob) => {
    const dataUrl = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.onerror = () => reject(reader.error);
      reader.readAsDataURL(file);
    });
    const result = await putScreenshot(app, product, nodeId, dataUrl);
    if (result.ok) {
      setScreenshots((was) => ({ ...was, [nodeId]: true }));
      setImageVersion((v) => v + 1);
      toast("Görüntü eklendi");
    } else {
      toast("Görüntü eklenemedi: " + result.error, true);
    }
  }, [app, product, toast]);

  /* Ctrl+V anywhere in the panel attaches to the SELECTED box — the way a
     person actually works: take a screenshot, click the box, paste. */
  const panel = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!active || !selected) return;
    const onPaste = (event: ClipboardEvent) => {
      const item = Array.from(event.clipboardData?.items ?? [])
        .find((entry) => entry.type.startsWith("image/"));
      const file = item?.getAsFile();
      if (!file) return;
      event.preventDefault();
      void attach(selected, file);
    };
    window.addEventListener("paste", onPaste);
    return () => window.removeEventListener("paste", onPaste);
  }, [active, selected, attach]);

  /* ---------------------------- read screens ---------------------------- */

  const read = useAsyncAction(async () => {
    if (dirty) {
      const saved = await saveFlow(app, product, flow);
      if (!saved.ok) {
        toast("Önce akış kaydedilemedi: " + saved.error, true);
        return;
      }
      setDirty(false);
    }
    const result = await readScreens(app, product);
    if (!result.ok) {
      toast("Ekranlar okunamadı: " + result.error, true);
      return;
    }
    setResults(result.body.screens);
    const next: Record<string, string> = {};
    result.body.screens.forEach((screen) => {
      if (screen.yaml) next[screen.nodeId] = screen.yaml;
    });
    setReads((was) => ({ ...was, ...next }));
    const failed = result.body.screens.filter((s) => s.error).length;
    toast(result.body.screens.length - failed + " ekran okundu"
      + (failed ? ", " + failed + " okunamadı" : ""), failed > 0);
  });

  const keepCatalog = async (screen: ScreenRead) => {
    const node = flow.nodes.find((n) => n.id === screen.nodeId);
    const name = node?.flow?.trim();
    if (!name) {
      toast("Önce bu ekrana bir katalog adı verin (ekran kutusunu seçip \"Katalog adı\").", true);
      return;
    }
    const result = await saveCatalog(app, name, screen.yaml ?? "");
    toast(result.ok ? "Katalog kaydedildi: " + app + "/" + name
      : "Katalog kaydedilemedi: " + result.error, !result.ok);
  };

  const node = flow.nodes.find((n) => n.id === selected) ?? null;
  const withShots = flow.nodes.filter((n) => screenshots[n.id]).length;

  return (
    <div id="side-flow" className={"side-panel flow-panel" + (active ? " is-active" : "")} ref={panel}>
      <PanelHead title="Senaryo Üret">
        <label className="fc-product">
          <span>Ürün</span>
          <select className="search" value={app + "/" + product} disabled={save.busy || read.busy}
                  onChange={(event) => {
                    const [a, p] = event.target.value.split("/");
                    setApp(a ?? ""); setProduct(p ?? "");
                  }}>
            {products.length === 0 ? <option value="/">(ürün yok)</option> : null}
            {products.map((p) => (
              <option key={p.app + p.product} value={p.app + "/" + p.product}>
                {p.name} ({p.app.toUpperCase()})
              </option>
            ))}
          </select>
        </label>
        <button type="button" className="btn btn-ghost" onClick={addRoot} disabled={save.busy}>
          + Ekran ekle
        </button>
        <button type="button" className="btn btn-ghost" disabled={!dirty || save.busy}
                onClick={() => void save.run()}>
          {save.busy ? "Kaydediliyor…" : dirty ? "Kaydet" : "Kaydedildi"}
        </button>
        <button type="button" className="btn btn-primary"
                disabled={read.busy || withShots === 0 || !visionReady}
                title={!visionReady ? "Görsel model kapalı (uptest.vision.enabled)"
                  : withShots === 0 ? "Önce kutulara ekran görüntüsü ekleyin" : undefined}
                onClick={() => void read.run()}>
          {read.busy ? "Okunuyor…" : "Ekranları Oku"}
        </button>
      </PanelHead>

      <div className="flow-body">
        {!visionReady && loaded ? (
          <p className="product-warn">
            Görsel model kapalı: akışı çizebilir ve görüntü ekleyebilirsiniz, ama
            "Ekranları Oku" çalışmaz. Sunucuda <code>uptest.vision.enabled=true</code> ve
            <code> uptest.vision.api-key</code> gerekiyor.
          </p>
        ) : null}

        <p className="db-note">
          Kutular ekran, oklar geçiş. Bir okun etiketi, o geçişi yapan kontrolün
          ÜSTÜNDEKİ yazıdır ("Fatura Oluştur") — senaryo onu <code>click</code> olarak
          kullanır. Bir kutuyu seçip <strong>Ctrl+V</strong> ile ekran görüntüsü yapıştırın.
        </p>

        {flow.nodes.length === 0 && loaded ? (
          <p className="db-empty">Henüz ekran yok. "+ Ekran ekle" ile başlayın.</p>
        ) : (
          <FlowCanvas
            app={app} product={product} flow={flow} screenshots={screenshots}
            imageVersion={imageVersion} selected={selected} busy={save.busy || read.busy}
            onSelect={setSelected} onChange={change} onAddChild={addChild}
            onDeleteNode={(id) => setDeleting(flow.nodes.find((n) => n.id === id) ?? null)}
          />
        )}

        {node ? (
          <section className="fc-editor">
            <h3 className="db-card-title">{node.title}</h3>
            <div className="fc-editor-grid">
              <label className="users-field">
                <span>Ekran adı</span>
                <input className="search" value={node.title}
                       onChange={(event) => patchNode(node.id, { title: event.target.value })} />
              </label>
              <label className="users-field">
                <span>Katalog adı</span>
                <input className="search" value={node.flow} placeholder="ör. firma-fatura-islemleri"
                       onChange={(event) => patchNode(node.id, { flow: event.target.value })} />
              </label>
            </div>
            <label className="users-field">
              <span>Not — görüntüde görünmeyen, modelin bilmesi gereken şey</span>
              <textarea className="search fc-note" rows={2} value={node.note}
                        placeholder="ör. bu ekran yalnız yetkili kullanıcıda açılır; listede üç kayıt var"
                        onChange={(event) => patchNode(node.id, { note: event.target.value })} />
            </label>
            <div className="fc-editor-actions">
              <label className="btn btn-ghost fc-file">
                Görüntü seç
                <input type="file" accept="image/png,image/jpeg" hidden
                       onChange={(event) => {
                         const file = event.target.files?.[0];
                         if (file) void attach(node.id, file);
                         event.target.value = "";
                       }} />
              </label>
              {screenshots[node.id] ? (
                <button type="button" className="btn btn-ghost" onClick={async () => {
                  await deleteScreenshot(app, product, node.id);
                  setScreenshots((was) => ({ ...was, [node.id]: false }));
                  setReads((was) => { const next = { ...was }; delete next[node.id]; return next; });
                }}>Görüntüyü kaldır</button>
              ) : null}
              <span className="db-note">Ya da kutu seçiliyken Ctrl+V.</span>
            </div>
            {reads[node.id] ? (
              <details className="fc-read">
                <summary>Modelin okuduğu katalog taslağı</summary>
                <pre className="yaml-output">{reads[node.id]}</pre>
              </details>
            ) : null}
          </section>
        ) : null}

        <section className="fc-editor">
          <h3 className="db-card-title">Üretim bağlamı</h3>
          <label className="users-field">
            <span>Neyi test etmek istiyorsunuz</span>
            <textarea className="search fc-note" rows={2} value={flow.intent}
                      placeholder="ör. fatura oluşturmanın mutlu yolu ve tutar/tarih doğrulamaları"
                      onChange={(event) => change({ ...flow, intent: event.target.value })} />
          </label>
          <label className="users-field">
            <span>Kapsam dışı — neye dokunulmasın</span>
            <textarea className="search fc-note" rows={2} value={flow.outOfScope}
                      placeholder="ör. dosya yükleme dalı; fatura iptali"
                      onChange={(event) => change({ ...flow, outOfScope: event.target.value })} />
          </label>
        </section>

        {results ? (
          <section className="fc-editor">
            <h3 className="db-card-title">Okuma sonucu</h3>
            <p className="db-note">
              Bu bir TASLAK. Bir insan okuyup düzeltmeden katalog değildir — görsel
              model etiketleri yanlış okuyabilir, kapalı bir listenin seçeneklerini göremez.
            </p>
            {results.map((screen) => (
              <div key={screen.nodeId} className="fc-result">
                <div className="fc-result-head">
                  <strong>{screen.title}</strong>
                  {screen.yaml ? (
                    <button type="button" className="btn btn-ghost"
                            onClick={() => void keepCatalog(screen)}>
                      Katalog olarak kaydet
                    </button>
                  ) : null}
                </div>
                {screen.error ? <p className="product-warn">{screen.error}</p> : null}
                {screen.problems.map((problem, i) => (
                  <p key={i} className="product-warn">{problem}</p>
                ))}
                {screen.yaml ? <pre className="yaml-output">{screen.yaml}</pre> : null}
              </div>
            ))}
          </section>
        ) : null}
      </div>

      {deleting ? (
        <ConfirmDialog
          title="Ekranı silmek istediğinize emin misiniz?"
          detail={"“" + deleting.title + "” ve ona bağlı oklar silinecek"
            + (screenshots[deleting.id] ? ", ekran görüntüsü de kaldırılacak" : "") + "."}
          confirmLabel="Sil"
          onCancel={() => setDeleting(null)}
          onConfirm={async () => { await removeNode(deleting); setDeleting(null); }}
        />
      ) : null}
    </div>
  );
}
