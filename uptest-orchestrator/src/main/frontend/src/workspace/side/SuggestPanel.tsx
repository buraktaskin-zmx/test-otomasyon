import { useEffect, useState } from "react";
import { PanelHead } from "./PanelHead";
import { suggestScenario } from "../../api/endpoints";
import { useAsyncAction } from "../../hooks/useAsyncAction";
import { NO_PRODUCT, ProductPicker } from "../../product/ProductPicker";
import { UserPicker } from "../../users/UserPicker";
import type { useTestUsers } from "../../hooks/useTestUsers";
import type { ProductSelection } from "../../product/ProductPicker";
import type { useProducts } from "../../hooks/useProducts";
import { Badge } from "../../ui/Badge";
import { useToast } from "../../ui/Toast";
import type { Origin, SuggestResponse } from "../../api/types";

function SuggestStatus({
  busy,
  response,
  error,
}: {
  busy: boolean;
  response: SuggestResponse | null;
  error: string | null;
}) {
  if (busy) {
    return (
      <div id="suggestStatus" className="suggest-status">
        <div className="s-line">Model düşünüyor, bu 10-30 saniye sürebilir…</div>
      </div>
    );
  }
  if (error) {
    return (
      <div id="suggestStatus" className="suggest-status">
        <div className="s-line">{error}</div>
      </div>
    );
  }
  if (!response) return <div id="suggestStatus" className="suggest-status" />;

  return (
    <div id="suggestStatus" className="suggest-status">
      <div className="s-head">
        <Badge variant={response.valid ? "ok" : "fail"}>{response.valid ? "geçerli" : "geçersiz"}</Badge>
        <span className="s-line">{response.attempts} model çağrısı</span>
      </div>
      {response.log.map((line, i) => (
        <div className="s-line mono" key={i}>
          {line}
        </div>
      ))}
    </div>
  );
}

/*
  app.js:546-646 + index.html:78-101. Pins origin on a successful generation
  only -- see lib/originScreen.ts. The live catalog/intent selection is
  reported upward on every change via `onFallbackChange`, which is the
  "live dropdown" half of the origin fallback (used only when nothing has
  been pinned yet).
*/
export function SuggestPanel({
  active,
  catalogs,
  products,
  users,
  preselect,
  onGenerated,
  onFallbackChange,
}: {
  active: boolean;
  catalogs: string[];
  products: ReturnType<typeof useProducts>;
  users: ReturnType<typeof useTestUsers>;
  /** Set right after a catalog is saved from the Ajan panel -- re-read from
   *  the server (app.js:1071: "a name the server normalised differently
   *  would otherwise be wrong here"), then adopted here so the newly saved
   *  screen is immediately selected rather than requiring a manual pick. */
  preselect: string | null;
  onGenerated: (feature: string, origin: Origin) => void;
  onFallbackChange: (origin: Origin) => void;
}) {
  const [selection, setSelection] = useState<ProductSelection>(NO_PRODUCT);
  const catalog = selection.catalog;

  useEffect(() => {
    if (preselect) setSelection((was) => ({ ...was, catalog: preselect }));
  }, [preselect]);
  /* The cif and user id are no longer typed here: they come from a saved
     login, so the same digits are not retyped into four panels and a typo
     cannot reach a browser. */
  const [userId, setUserId] = useState("");
  const chosen = userId ? users.byId(userId) : null;
  const cif = chosen?.cif ?? "";
  const userid = chosen?.userid ?? "";
  const [intent, setIntent] = useState("");
  const [response, setResponse] = useState<SuggestResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const toast = useToast();

  const [appPart = "", flowPart = ""] = catalog.split("/");
  const isNGI = appPart.toUpperCase() === "NGI";

  /** Everything the editor and the run gate need to re-check this draft the
   *  way the generator checked it. `product` belongs here and `ruleIds` does
   *  not: the ticks shape the prompt, the product shapes the verdict. */
  const origin: Origin = { app: appPart, flow: flowPart, intent, product: selection.product };

  useEffect(() => {
    onFallbackChange({ app: appPart, flow: flowPart, intent, product: selection.product });
  }, [appPart, flowPart, intent, selection.product, onFallbackChange]);

  const generate = useAsyncAction(async () => {
    if (!catalog) {
      toast("Önce bir ekran seçin", true);
      return;
    }
    if (!intent.trim()) {
      toast("Ne test edileceğini yazın", true);
      return;
    }
    // The orchestrator builds the login step from these, so a missing
    // userid would otherwise surface as a 400 AFTER the model was called.
    if (cif && !isNGI && !userid) {
      toast("NGC için kullanıcı ID zorunlu", true);
      return;
    }

    setError(null);
    const result = await suggestScenario({ ...origin, cif, userid, ruleIds: selection.ruleIds });
    if (!result.ok) {
      setResponse(null);
      setError(result.error);
      toast("Üretim başarısız: " + result.error, true);
      return;
    }

    setResponse(result.body);
    onGenerated(result.body.feature, origin);
    toast(
      result.body.valid ? "Senaryo üretildi ve doğrulandı" : "Taslak üretildi ama geçersiz — hataları düzeltin",
      !result.body.valid,
    );
  });

  return (
    <div id="side-suggest" className={"side-panel" + (active ? " is-active" : "")}>
      <PanelHead title="AI Üret" />
      <div className="suggest-form">
        <ProductPicker
          idPrefix="suggest"
          products={products}
          catalogs={catalogs}
          value={selection}
          onChange={setSelection}
          disabled={generate.busy}
        />
        <UserPicker
          idPrefix="suggest"
          users={users}
          value={userId}
          onChange={setUserId}
          needsUserid={!isNGI}
          disabled={generate.busy}
        />
        <label className="suggest-label" htmlFor="suggestIntent">
          Ne test edilsin?
        </label>
        <textarea
          id="suggestIntent"
          className="search suggest-intent"
          rows={5}
          placeholder='Örn: DI00002018 / 3014 ile giriş yapıp 1.250,55 TL ödeme talebi oluştur ve tutarı onay ekranında doğrula'
          value={intent}
          onChange={(event) => setIntent(event.target.value)}
          onKeyDown={(event) => {
            if ((event.ctrlKey || event.metaKey) && event.key === "Enter") generate.run();
          }}
        />
        <button
          id="btnSuggest"
          className="btn btn-primary suggest-go"
          type="button"
          disabled={generate.busy || catalogs.length === 0}
          onClick={() => generate.run()}
        >
          {generate.busy ? "Üretiliyor…" : "Senaryo Üret"}
        </button>
        <p className="suggest-hint">
          Model yalnızca sağdaki sözlükteki adımları kullanabilir. Giriş adımını model değil
          orchestrator yazar — kataloğun uygulamasına göre. Üretilen taslak doğrulanır; geçersizse
          model bir kez düzeltmeyi dener. Sonuç editöre yazılır — çalıştırmadan önce gözden geçirin.
        </p>
      </div>
      <SuggestStatus busy={generate.busy} response={response} error={error} />
    </div>
  );
}
