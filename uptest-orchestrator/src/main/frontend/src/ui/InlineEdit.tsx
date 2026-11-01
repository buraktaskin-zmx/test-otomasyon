import { useCallback, useEffect, useRef } from "react";
import type { ReactNode } from "react";

/*
  Yerinde düzenleme: başlatan kalem, bitiren onay ve vazgeçme, ve metnine
  göre büyüyen kutular.

  The icons are inline stroke SVG inheriting `currentColor`, the same
  convention SidePane's rail icons use -- no icon package, no font, nothing
  fetched at runtime.
*/

const STROKE = {
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 2,
  strokeLinecap: "round",
  strokeLinejoin: "round",
} as const;

const GLYPHS = {
  // A pencil over its stroke.
  pencil: (
    <>
      <path d="M9.4 2.9 11.1 4.6 5 10.7l-2.2.5.5-2.2z" />
      <path d="M8.4 3.9 10.1 5.6" />
    </>
  ),
  check: <path d="M3 7.4 5.8 10.2 11 4.4" />,
  close: (
    <>
      <path d="M3.8 3.8 10.2 10.2" />
      <path d="M10.2 3.8 3.8 10.2" />
    </>
  ),
} as const;

export type GlyphName = keyof typeof GLYPHS;

/**
 * A frameless round icon button: 14px glyph in a 28px hit area.
 *
 * A real {@code <button>}, so it is reached by Tab, fires on Enter and
 * Space and announces itself as a button -- adding role="button" and
 * tabindex="0" to one would only repeat what the element already says.
 *
 * @param tone "ok" for the tick (green), "mute" for the cross, the default
 *             grey-to-blue for the pencil
 */
export function IconButton({
  glyph,
  label,
  title,
  tone,
  disabled,
  onClick,
}: {
  glyph: GlyphName;
  /** What a screen reader says. */
  label: string;
  /** What the tooltip says; may be longer than the label. */
  title: string;
  tone?: "ok" | "mute";
  disabled?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      className={"sd-icon-btn" + (tone ? " is-" + tone : "")}
      aria-label={label}
      title={title}
      disabled={disabled}
      onClick={onClick}
    >
      <Glyph>{GLYPHS[glyph]}</Glyph>
    </button>
  );
}

function Glyph({ children }: { children: ReactNode }) {
  return (
    <svg viewBox="0 0 14 14" width="14" height="14" aria-hidden="true" {...STROKE}>
      {children}
    </svg>
  );
}

/**
 * Textareas exactly as tall as their own text.
 *
 * MEASURED, never guessed from a `rows` count: `rows` counts LINES, and in
 * these two narrow columns one long step wraps into three of them, so any
 * row count cuts the end off some scenario. The height comes from
 * `scrollHeight` instead -- what the browser says the text actually needs
 * once it has laid it out.
 *
 * Re-measured in three places, because each catches a case the others
 * miss: on every keystroke (the text grew), one frame after every render
 * (a value arrived from outside -- a save, a reload, edit mode opening),
 * and on window resize (the column got narrower, so the same text wraps
 * into more lines).
 *
 * @returns `attach` as a ref for each textarea, and `fit` for its onChange
 */
export function useAutoGrow() {
  const boxes = useRef(new Set<HTMLTextAreaElement>());

  const fit = useCallback((box: HTMLTextAreaElement) => {
    // "auto" first: without it scrollHeight can never report LESS than the
    // height already set, so a box that lost a line would stay tall.
    box.style.height = "auto";
    box.style.height = box.scrollHeight + "px";
  }, []);

  const fitAll = useCallback(() => {
    boxes.current.forEach(fit);
  }, [fit]);

  /* React 19 ref callbacks may return their own cleanup, so a textarea that
     unmounts takes its entry out of the set with it -- otherwise the set
     would keep growing with detached nodes nobody can measure. */
  const attach = useCallback((box: HTMLTextAreaElement | null) => {
    if (!box) {
      return undefined;
    }
    boxes.current.add(box);
    fit(box);
    return () => {
      boxes.current.delete(box);
    };
  }, [fit]);

  // No dependency list on purpose: this is "after every render", and a
  // frame later, since a textarea measured in the same tick it was given
  // new text still reports the old height.
  useEffect(() => {
    const frame = requestAnimationFrame(fitAll);
    window.addEventListener("resize", fitAll);
    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener("resize", fitAll);
    };
  });

  return { attach, fit };
}
