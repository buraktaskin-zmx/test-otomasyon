import { useState } from "react";
import type { ChatQuestion, ChatReplyRequest } from "../api/types";

/*
  The pending question's answers.

  The question TEXT is not repeated here -- it is already the last thing in
  the transcript, said by the AI. This is only the buttons, so the analyst
  reads the question once and answers it where their eye already is.

  Two shapes of button. Most resolve on the click; one kind ("Beklenen
  değeri ben gireyim") cannot, because the answer is a value only the analyst
  knows. That one opens an input rather than sending an empty answer, which
  the server would reject anyway -- ChatQuestions.applied returns null for a
  value-taking option with no value, deliberately, so a half-answer can never
  be mistaken for a whole one.
*/
export function QuestionCard({
  question,
  disabled,
  onAnswer,
}: {
  question: ChatQuestion;
  disabled: boolean;
  onAnswer: (reply: ChatReplyRequest) => void;
}) {
  const [asking, setAsking] = useState<string | null>(null);
  const [value, setValue] = useState("");
  const [typed, setTyped] = useState("");

  const option = question.options.find((candidate) => candidate.id === asking) ?? null;

  const answerWithValue = () => {
    if (!option || !value.trim()) return;
    onAnswer({ optionId: option.id, value: value.trim() });
    setAsking(null);
    setValue("");
  };

  const answerWithText = () => {
    if (!typed.trim()) return;
    onAnswer({ text: typed.trim() });
    setTyped("");
  };

  return (
    <div className="chat-answers">
      {option ? (
        <div className="chat-ask">
          <label className="suggest-label" htmlFor="chatValue">
            {option.valuePrompt ?? option.label}
          </label>
          <div className="chat-row">
            <input
              id="chatValue"
              className="search"
              value={value}
              autoFocus
              disabled={disabled}
              onChange={(event) => setValue(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter") answerWithValue();
                if (event.key === "Escape") setAsking(null);
              }}
            />
            <button
              className="btn btn-primary"
              type="button"
              disabled={disabled || !value.trim()}
              onClick={answerWithValue}
            >
              Gönder
            </button>
            <button className="btn btn-ghost" type="button" onClick={() => setAsking(null)}>
              Vazgeç
            </button>
          </div>
        </div>
      ) : (
        <>
          <div className="chat-options">
            {question.options.map((candidate) => (
              <button
                key={candidate.id}
                className="btn btn-primary"
                type="button"
                disabled={disabled}
                onClick={() =>
                  candidate.needsValue
                    ? setAsking(candidate.id)
                    : onAnswer({ optionId: candidate.id })
                }
              >
                {candidate.label}
              </button>
            ))}
          </div>

          {question.allowsFreeText ? (
            <div className="chat-row">
              <input
                className="search"
                placeholder="Ya da kendiniz yazın…"
                value={typed}
                disabled={disabled}
                onChange={(event) => setTyped(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter") answerWithText();
                }}
              />
              <button
                className="btn btn-ghost"
                type="button"
                disabled={disabled || !typed.trim()}
                onClick={answerWithText}
              >
                Gönder
              </button>
            </div>
          ) : null}
        </>
      )}
    </div>
  );
}
