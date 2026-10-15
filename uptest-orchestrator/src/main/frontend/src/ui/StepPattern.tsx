import { Fragment } from "react";

/*
  Highlights {string}/{int} placeholders in a step pattern (app.js's
  renderPattern, app.js:119-121: esc(pattern).replace(/\{(\w*)\}/g, ...)).

  No esc() call is needed here on the way in -- React escapes every text
  node by default, which is exactly the class of bug (app.js's 25 innerHTML
  sites, each needing its own manual esc()) this rewrite is meant to retire.
*/
const PLACEHOLDER = /\{(\w*)\}/g;

export function StepPattern({ pattern }: { pattern: string }) {
  const parts: React.ReactNode[] = [];
  let lastIndex = 0;
  let key = 0;

  PLACEHOLDER.lastIndex = 0;
  let match: RegExpExecArray | null;
  while ((match = PLACEHOLDER.exec(pattern)) !== null) {
    if (match.index > lastIndex) {
      parts.push(<Fragment key={key++}>{pattern.slice(lastIndex, match.index)}</Fragment>);
    }
    parts.push(
      <span className="ph" key={key++}>
        {match[0]}
      </span>,
    );
    lastIndex = PLACEHOLDER.lastIndex;
  }
  if (lastIndex < pattern.length) {
    parts.push(<Fragment key={key++}>{pattern.slice(lastIndex)}</Fragment>);
  }

  return <>{parts}</>;
}
