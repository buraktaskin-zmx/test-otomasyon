import { useEffect, useRef, useState } from "react";

/*
  One screen's captures, full size.

  The node boxes are 210px wide and crop their image to fill, which is right
  for a map of the flow and useless for reading the page the analyst pasted.
  This is where they check that the right screen was captured — and, once the
  vision model has read it, where they compare the draft against what is
  actually there.

  A screen is a LIST of pictures, not one: a banking page does not fit in a
  single capture, so the analyst scrolls and pastes again, and the vision
  model is given all of them as one page. Hence the arrows and the counter.

  Deliberately not ConfirmDialog with an image in it: that component asks a
  question and puts focus on "Vazgeç". Nothing is being decided here.
*/
export function ScreenPreview({
  title,
  count,
  start,
  href,
  busy,
  onDelete,
  onClose,
}: {
  title: string;
  /** How many pictures this screen has. */
  count: number;
  /** Which one to open on. */
  start: number;
  href: (index: number) => string;
  busy: boolean;
  /** Remove the picture being looked at. The server closes the gap. */
  onDelete: (index: number) => void;
  onClose: () => void;
}) {
  const close = useRef<HTMLButtonElement>(null);
  const [at, setAt] = useState(start);

  /* Deleting the last picture leaves `at` past the end, and the server has
     renumbered the rest — so clamp rather than trusting the index to survive. */
  const index = Math.min(Math.max(at, 0), Math.max(count - 1, 0));

  useEffect(() => {
    close.current?.focus();
  }, []);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.stopPropagation();
        onClose();
      } else if (event.key === "ArrowRight") {
        setAt((was) => Math.min(was + 1, count - 1));
      } else if (event.key === "ArrowLeft") {
        setAt((was) => Math.max(was - 1, 0));
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose, count]);

  if (count === 0) return null;

  return (
    <div className="confirm-backdrop fc-preview" role="dialog" aria-modal="true"
         aria-label={title + " ekran görüntüleri"} onClick={onClose}>
      {/* The click handler is on the backdrop, so stop it here: clicking the
          picture to look closer must not close the thing showing it. */}
      <div className="fc-preview-card" onClick={(event) => event.stopPropagation()}>
        <div className="fc-preview-head">
          <strong>{title}</strong>
          {count > 1 ? (
            <span className="fc-preview-nav">
              <button type="button" className="btn btn-ghost" disabled={index === 0}
                      aria-label="Önceki görüntü"
                      onClick={() => setAt(index - 1)}>‹</button>
              <span className="db-note">{index + 1} / {count}</span>
              <button type="button" className="btn btn-ghost" disabled={index >= count - 1}
                      aria-label="Sonraki görüntü"
                      onClick={() => setAt(index + 1)}>›</button>
            </span>
          ) : null}
          <span className="fc-preview-actions">
            <button type="button" className="btn btn-ghost" disabled={busy}
                    onClick={() => onDelete(index)}>
              Bu görüntüyü sil
            </button>
            <button type="button" className="btn btn-ghost" ref={close} onClick={onClose}>
              Kapat
            </button>
          </span>
        </div>
        <img className="fc-preview-img" src={href(index)}
             alt={title + " ekran görüntüsü " + (index + 1)} />
      </div>
    </div>
  );
}
