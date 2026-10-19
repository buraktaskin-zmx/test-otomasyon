import { useEffect, useState } from "react";
import { MessageList } from "./MessageList";
import { PanelHead } from "../workspace/side/PanelHead";
import { QuestionCard } from "./QuestionCard";
import { ScenarioPreview } from "./ScenarioPreview";
import { useChat } from "../hooks/useChat";
import type { useProducts } from "../hooks/useProducts";
import { NO_PRODUCT, ProductPicker } from "../product/ProductPicker";
import { UserPicker } from "../users/UserPicker";
import type { useTestUsers } from "../hooks/useTestUsers";
import type { ProductSelection } from "../product/ProductPicker";
import { useToast } from "../ui/Toast";
import type { Origin } from "../api/types";

/*
  Writing a scenario by talking about it.

  No browser. This is the generator with its repair turn pointed at the
  analyst: generate once, and when the validator objects, ask instead of
  letting the model guess. The guessing is not hypothetical -- asked to verify
  a read-only field, the model invented a value, was shown the complaint, and
  deleted the assertion. The analyst could have answered that in one click,
  and here they do.

  Takes the whole workspace width when it is open, because a conversation and
  a growing draft side by side do not fit in the 40% column the other panels
  live in.
*/
export function ChatView({
  active,
  catalogs,
  products,
  users,
  preselect,
  onHandOff,
  onRunDraft,
}: {
  active: boolean;
  catalogs: string[];
  products: ReturnType<typeof useProducts>;
  users: ReturnType<typeof useTestUsers>;
  preselect: string | null;
  onHandOff: (feature: string, origin: Origin) => void;
  onRunDraft: (feature: string, origin: Origin) => void;
}) {
  const chat = useChat();
  const toast = useToast();

  const [selection, setSelection] = useState<ProductSelection>(NO_PRODUCT);
  const catalog = selection.catalog;
  const [userId, setUserId] = useState("");
  const chosen = userId ? users.byId(userId) : null;
  const cif = chosen?.cif ?? "";
  const userid = chosen?.userid ?? "";
  const [opening, setOpening] = useState("");

  useEffect(() => {
    if (preselect) setSelection((was) => ({ ...was, catalog: preselect }));
  }, [preselect]);

  const [app = "", flow = ""] = catalog.split("/");
  const needsUserid = app.toUpperCase() !== "NGI";
  /* Both fields answer the same question: what was this draft ACTUALLY
     generated against? The editor re-validates whatever the chat hands it, so
     anything the verdict depends on has to travel with the scenario.

     `intent` is the WHOLE agreement, not the opening line --
     checkAssertionsAreEarned looks for expected values in the intent, so a
     value the analyst gave mid-conversation lives there and nowhere else.

     `product` comes from the SERVER's turn rather than the picker, because
     the conversation was opened under one product and stays there. */
  const origin: Origin = {
    app,
    flow,
    intent: chat.turn?.request ?? opening,
    product: chat.turn?.product ?? selection.product,
  };

  useEffect(() => {
    if (chat.error) toast(chat.error, true);
  }, [chat.error, toast]);

  const begin = () => {
    if (!catalog) {
      toast("Önce bir ekran seçin", true);
      return;
    }
    if (!opening.trim()) {
      toast("Ne test edileceğini yazın", true);
      return;
    }
    if (cif.trim() && needsUserid && !userid.trim()) {
      toast(app + " için kullanıcı ID zorunlu", true);
      return;
    }
    chat.start({
      app,
      flow,
      cif: cif.trim(),
      userid: userid.trim(),
      text: opening.trim(),
      product: selection.product,
      ruleIds: selection.ruleIds,
    });
  };

  return (
    <div id="side-chat" className={"side-panel chat-panel" + (active ? " is-active" : "")}>
      <PanelHead title={chat.turn ? "Chat · " + catalog : "Chat"}>
        {chat.turn ? (
          <button className="btn btn-ghost" type="button" onClick={chat.reset}>
            Yeni sohbet
          </button>
        ) : null}
      </PanelHead>

      {chat.turn === null ? (
        <div className="suggest-form">
          <ProductPicker
            idPrefix="chat"
            products={products}
            catalogs={catalogs}
            value={selection}
            onChange={setSelection}
            disabled={chat.busy}
          />

          <UserPicker
            idPrefix="chat"
            users={users}
            value={userId}
            onChange={setUserId}
            needsUserid={needsUserid}
            disabled={chat.busy}
          />

          <label className="suggest-label" htmlFor="chatOpening">Ne test edilsin?</label>
          <textarea
            id="chatOpening"
            className="search suggest-intent"
            rows={4}
            value={opening}
            placeholder="Fatura Oluştur ekranında 93002 bayisine 200,00 TL taksitli fatura oluşturulsun. Bayi Ünvanı doğrulansın."
            onChange={(event) => setOpening(event.target.value)}
            onKeyDown={(event) => {
              if ((event.ctrlKey || event.metaKey) && event.key === "Enter") begin();
            }}
          />

          <button
            className="btn btn-primary suggest-go"
            type="button"
            disabled={chat.busy || catalogs.length === 0}
            onClick={begin}
          >
            {chat.busy ? "Başlatılıyor…" : "Sohbeti Başlat"}
          </button>

          <p className="suggest-hint">
            Sohbet katalogdan ve adım sözlüğünden konuşur; tarayıcı sürmez. Eksik bir değer
            ya da doğrulanamayan bir alan olduğunda uydurmak yerine <b>size sorar</b>.
          </p>
        </div>
      ) : (
        <div className="chat-body">
          <div className="chat-side">
            <MessageList messages={chat.turn.messages} busy={chat.busy} />
            {chat.turn.question ? (
              <QuestionCard
                question={chat.turn.question}
                disabled={chat.busy}
                onAnswer={chat.reply}
              />
            ) : null}
          </div>

          {chat.turn.feature ? (
            <ScenarioPreview
              feature={chat.turn.feature}
              validation={chat.turn.validation}
              complete={chat.turn.complete}
              busy={chat.busy}
              onRun={() => onRunDraft(chat.turn!.feature!, origin)}
              onHandOff={() => {
                onHandOff(chat.turn!.feature!, origin);
                toast("Senaryo editöre aktarıldı");
              }}
            />
          ) : null}
        </div>
      )}
    </div>
  );
}
