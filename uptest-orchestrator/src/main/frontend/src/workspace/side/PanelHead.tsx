import type { ReactNode } from "react";

/*
  A side panel's title bar.

  The tab strip used to be the panel's header: whichever tab was lit told you
  what you were looking at, and the strip itself drew the line between the
  pane's chrome and its content. Moving the tabs out to the rail took both of
  those away -- the panel's content now starts flush against the top of the
  card with nothing naming it.

  Deliberately built from app.css's existing .pane-head / .pane-title /
  .pane-actions, which the editor pane has used all along. No new CSS: this is
  the same object in a different place, and giving it its own class names
  would be two rules to keep in step for one appearance.
*/
export function PanelHead({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="pane-head">
      <h2 className="pane-title">{title}</h2>
      {children ? <div className="pane-actions">{children}</div> : null}
    </div>
  );
}
