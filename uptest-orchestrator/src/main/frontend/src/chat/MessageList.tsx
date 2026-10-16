import { useEffect, useRef } from "react";
import type { ChatMessage } from "../api/types";

/*
  The transcript.

  Scrolled to the end on every new message, because the thing worth reading
  is always the last one -- a question waiting for an answer, or the sentence
  saying the scenario is ready. Scrolling the container rather than calling
  scrollIntoView on a child: scrollIntoView moves the nearest scrollable
  ancestor, which in a panel inside a pane inside a flex column is not
  reliably the one meant.
*/
export function MessageList({ messages, busy }: { messages: ChatMessage[]; busy: boolean }) {
  const boxRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const box = boxRef.current;
    if (box) box.scrollTop = box.scrollHeight;
  }, [messages, busy]);

  return (
    <div className="chat-log" ref={boxRef}>
      {messages.map((message, index) => (
        <div key={index} className={"chat-msg chat-" + message.role}>
          <div className="chat-who">{message.role === "ai" ? "AI" : "siz"}</div>
          <div className="chat-text">{message.text}</div>
        </div>
      ))}
      {busy ? (
        <div className="chat-msg chat-ai">
          <div className="chat-who">AI</div>
          <div className="chat-text chat-thinking">düşünüyor…</div>
        </div>
      ) : null}
    </div>
  );
}
