import { useCallback, useRef, useState } from "react";
import { endChat, replyToChat, startChat } from "../api/endpoints";
import type { ChatReplyRequest, ChatStartRequest, ChatTurn } from "../api/types";

/*
  One conversation's worth of state.

  Every call returns the WHOLE turn -- the transcript, the pending question,
  the current draft and its verdict -- so there is nothing to merge here and
  no way for the two halves to disagree. The server owns the conversation;
  this hook owns a copy of the last thing it said.

  Single-flight by ref, the same guard useAsyncAction carries and for a
  related reason: a turn is a model call, and a double-click would start a
  second one that answers a question the first one has already replaced.
*/
export function useChat() {
  const [turn, setTurn] = useState<ChatTurn | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inFlight = useRef(false);

  const send = useCallback(async (call: () => Promise<Awaited<ReturnType<typeof startChat>>>) => {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setError(null);
    try {
      const result = await call();
      if (result.ok) {
        setTurn(result.body);
      } else {
        setError(result.error);
      }
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }, []);

  const start = useCallback(
    (request: ChatStartRequest) => void send(() => startChat(request)),
    [send],
  );

  const reply = useCallback(
    (request: ChatReplyRequest) => {
      const id = turn?.sessionId;
      if (!id) return;
      void send(() => replyToChat(id, request));
    },
    [send, turn],
  );

  /* Tells the server too, rather than just forgetting locally: sessions do
     expire on their own, but an abandoned transcript sitting in memory for
     the idle timeout is an analyst's intent nobody asked to keep. */
  const reset = useCallback(() => {
    const id = turn?.sessionId;
    if (id) void endChat(id);
    setTurn(null);
    setError(null);
  }, [turn]);

  return { turn, busy, error, start, reply, reset };
}
