import { useEffect, useState } from "react";
import { MessageList } from "./MessageList";
import { PanelHead } from "../workspace/side/PanelHead";
import { QuestionCard } from "./QuestionCard";
import { ScenarioPreview } from "./ScenarioPreview";
import { useChat } from "../hooks/useChat";
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
  preselect,
  onHandOff,
  onRunDraft,
}: {
  active: boolean;
  catalogs: string[];
  preselect: string | null;
  onHandOff: (feature: string, origin: Origin) => void;
  onRunDraft: (feature: string, origin: Origin) => void;
}) {
  const chat = useChat();
  const toast = useToast();

  const [catalog, setCatalog] = useState("");
  const [cif, setCif] = useState("");
  const [userid, setUserid] = useState("");
  const [opening, setOpening] = useState("");

  useEffect(() => {
    if (preselect) setCatalog(preselect);
  }, [preselect]);

  const [app = "", flow = ""] = catalog.split("/");
  const needsUserid = app.toUpperCase() !== "NGI";
  /* The WHOLE agreement, not the opening line. The editor re-validates what
     it is handed, and checkAssertionsAreEarned looks for expected values in
     the intent -- so a value the analyst gave mid-conversation has to travel
     with the scenario or the editor asks the question again. */
  const origin: Origin = { app, flow, intent: chat.turn?.request ?? opening };

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
    chat.start({ app, flow, cif: cif.trim(), userid: userid.trim(), text: opening.trim() });
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
          <label className="suggest-label" htmlFor="chatCatalog">Ekran</label>
          <select
            id="chatCatalog"
            className="search"
            value={catalog}
            onChange={(event) => setCatalog(event.target.value)}
          >
            {catalogs.length === 0 ? (
              <option value="">(katalog bulunamadı)</option>
            ) : (
              <>
                <option value="">Seçiniz…</option>
                {catalogs.map((name) => (
                  <option key={name} value={name}>{name}</option>
                ))}
              </>
            )}
          </select>

          <label className="suggest-label" htmlFor="chatCif">CIF / Müşteri kodu</label>
          <input
            id="chatCif"
            className="search"
            value={cif}
            onChange={(event) => setCif(event.target.value)}
          />

          {needsUserid ? (
            <>
              <label className="suggest-label" htmlFor="chatUserid">Kullanıcı ID</label>
              <input
                id="chatUserid"
                className="search"
                value={userid}
                onChange={(event) => setUserid(event.target.value)}
              />
            </>
          ) : null}

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
