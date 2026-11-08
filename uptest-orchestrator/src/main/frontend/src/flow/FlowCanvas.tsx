import { useCallback, useEffect, useRef, useState } from "react";
import type { PointerEvent as ReactPointerEvent } from "react";
import { screenshotHref } from "../api/endpoints";
import type { FlowEdge, FlowNode, FlowTree } from "../api/types";

/*
  The drawing surface: screens as boxes, transitions as labelled arrows.

  Hand-written, because this app ships no drawing library — corporate SSL
  blocks every CDN, so the bundle is React and nothing else. Two layers: one
  SVG underneath for the edges (the first computed geometry in this codebase;
  an inline style cannot draw a diagonal) and absolutely positioned real
  <button> boxes on top, so every node stays keyboard-reachable and screen
  readers get a name.

  Positions are stored on the node, in canvas coordinates. The canvas scrolls
  rather than zooms: an analyst drawing six screens needs to read the labels,
  and a zoomed-out tree of unreadable boxes helps nobody.
*/

export const NODE_W = 210;
export const NODE_H = 132;
const GAP_X = 150;
const GAP_Y = 90;
const PAD = 40;

interface Props {
  app: string;
  product: string;
  flow: FlowTree;
  screenshots: Record<string, boolean>;
  /** Bumped after a screenshot is replaced, to get past the browser cache. */
  imageVersion: number;
  selected: string | null;
  busy: boolean;
  onSelect: (nodeId: string | null) => void;
  onChange: (flow: FlowTree) => void;
  onAddChild: (parentId: string, side: "right" | "down") => void;
  onDeleteNode: (nodeId: string) => void;
}

export function FlowCanvas({
  app, product, flow, screenshots, imageVersion, selected, busy,
  onSelect, onChange, onAddChild, onDeleteNode,
}: Props) {
  const surface = useRef<HTMLDivElement>(null);
  const [editingEdge, setEditingEdge] = useState<string | null>(null);
  /* While a box is being dragged its position lives here, so the parent is
     not re-rendered (and the flow not marked dirty) on every pointer move. */
  const [dragging, setDragging] = useState<{ id: string; x: number; y: number } | null>(null);
  const drag = useRef<{ id: string; dx: number; dy: number } | null>(null);

  const at = useCallback((node: FlowNode) => (
    dragging && dragging.id === node.id ? { x: dragging.x, y: dragging.y } : { x: node.x, y: node.y }
  ), [dragging]);

  const onPointerDown = (node: FlowNode, event: ReactPointerEvent<HTMLElement>) => {
    // Only the box's own surface drags it; the buttons inside it do their job.
    if ((event.target as HTMLElement).closest("button,input,textarea,a")) return;
    const box = surface.current?.getBoundingClientRect();
    if (!box) return;
    const scrollLeft = surface.current?.scrollLeft ?? 0;
    const scrollTop = surface.current?.scrollTop ?? 0;
    drag.current = {
      id: node.id,
      dx: event.clientX - box.left + scrollLeft - node.x,
      dy: event.clientY - box.top + scrollTop - node.y,
    };
    setDragging({ id: node.id, x: node.x, y: node.y });
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
    onSelect(node.id);
  };

  const onPointerMove = (event: ReactPointerEvent<HTMLElement>) => {
    const d = drag.current;
    const box = surface.current?.getBoundingClientRect();
    if (!d || !box) return;
    const scrollLeft = surface.current?.scrollLeft ?? 0;
    const scrollTop = surface.current?.scrollTop ?? 0;
    setDragging({
      id: d.id,
      x: Math.max(0, event.clientX - box.left + scrollLeft - d.dx),
      y: Math.max(0, event.clientY - box.top + scrollTop - d.dy),
    });
  };

  const endDrag = () => {
    const d = drag.current;
    drag.current = null;
    if (!d || !dragging) {
      setDragging(null);
      return;
    }
    const moved = dragging;
    setDragging(null);
    const node = flow.nodes.find((n) => n.id === moved.id);
    if (!node || (node.x === moved.x && node.y === moved.y)) return;
    onChange({
      ...flow,
      nodes: flow.nodes.map((n) => (n.id === moved.id ? { ...n, x: moved.x, y: moved.y } : n)),
    });
  };

  const setEdgeLabel = (edgeId: string, label: string) =>
    onChange({ ...flow, edges: flow.edges.map((e) => (e.id === edgeId ? { ...e, label } : e)) });

  /* Arrow keys move between boxes, so the tree is navigable without a mouse:
     the nearest node in that direction wins, which on a tree laid out left to
     right is the parent, the child or the sibling. */
  const step = (dir: "ArrowLeft" | "ArrowRight" | "ArrowUp" | "ArrowDown") => {
    const from = flow.nodes.find((n) => n.id === selected);
    if (!from) return;
    const horizontal = dir === "ArrowLeft" || dir === "ArrowRight";
    const sign = dir === "ArrowLeft" || dir === "ArrowUp" ? -1 : 1;
    const best = flow.nodes
      .filter((n) => n.id !== from.id)
      .filter((n) => sign * ((horizontal ? n.x : n.y) - (horizontal ? from.x : from.y)) > 0)
      .sort((a, b) =>
        Math.hypot(a.x - from.x, a.y - from.y) - Math.hypot(b.x - from.x, b.y - from.y))[0];
    if (best) onSelect(best.id);
  };

  useEffect(() => {
    if (!selected) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onSelect(null);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [selected, onSelect]);

  const width = Math.max(900, ...flow.nodes.map((n) => at(n).x + NODE_W + PAD + GAP_X));
  const height = Math.max(460, ...flow.nodes.map((n) => at(n).y + NODE_H + PAD + GAP_Y));

  return (
    <div className="fc-surface" ref={surface} onPointerUp={endDrag} onPointerCancel={endDrag}>
      <div className="fc-canvas" style={{ width, height }}
           onPointerMove={onPointerMove}
           onClick={(event) => { if (event.target === event.currentTarget) onSelect(null); }}>
        <svg className="fc-edges" width={width} height={height} aria-hidden="true">
          <defs>
            <marker id="fc-arrow" viewBox="0 0 10 10" refX="9" refY="5"
                    markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M 0 0 L 10 5 L 0 10 z" fill="var(--primary)" />
            </marker>
          </defs>
          {flow.edges.map((edge) => {
            const from = flow.nodes.find((n) => n.id === edge.from);
            const to = flow.nodes.find((n) => n.id === edge.to);
            if (!from || !to) return null;
            return <path key={edge.id} className="fc-edge" d={curve(at(from), at(to))}
                         markerEnd="url(#fc-arrow)" />;
          })}
        </svg>

        {flow.edges.map((edge) => {
          const from = flow.nodes.find((n) => n.id === edge.from);
          const to = flow.nodes.find((n) => n.id === edge.to);
          if (!from || !to) return null;
          const mid = midpoint(at(from), at(to));
          return (
            <div key={edge.id} className="fc-label" style={{ left: mid.x, top: mid.y }}>
              {editingEdge === edge.id ? (
                <input
                  className="search fc-label-input"
                  autoFocus
                  defaultValue={edge.label}
                  aria-label="Geçiş etiketi"
                  onBlur={(event) => { setEdgeLabel(edge.id, event.target.value.trim()); setEditingEdge(null); }}
                  onKeyDown={(event) => {
                    if (event.key === "Enter") (event.target as HTMLInputElement).blur();
                    if (event.key === "Escape") setEditingEdge(null);
                  }}
                />
              ) : (
                <button type="button" className="fc-label-button unstyled-button"
                        title="Geçişin adı — kontrolün üstündeki yazı"
                        onClick={() => setEditingEdge(edge.id)}>
                  {edge.label || "geçiş adı"}
                </button>
              )}
            </div>
          );
        })}

        {flow.nodes.map((node) => {
          const pos = at(node);
          const isSelected = selected === node.id;
          return (
            <div
              key={node.id}
              className={"fc-node" + (isSelected ? " is-selected" : "")}
              style={{ left: pos.x, top: pos.y, width: NODE_W, height: NODE_H }}
              onPointerDown={(event) => onPointerDown(node, event)}
            >
              <button
                type="button"
                className="fc-node-main unstyled-button"
                aria-pressed={isSelected}
                aria-label={node.title + (screenshots[node.id] ? ", görüntü eklendi" : ", görüntü yok")}
                onClick={() => onSelect(node.id)}
                onKeyDown={(event) => {
                  if (event.key.startsWith("Arrow")) {
                    event.preventDefault();
                    step(event.key as "ArrowLeft");
                  }
                }}
              >
                <span className="fc-node-title">{node.title}</span>
                {screenshots[node.id] ? (
                  <img className="fc-node-shot" alt=""
                       src={screenshotHref(app, product, node.id, imageVersion)} />
                ) : (
                  <span className="fc-node-empty">görüntü yok</span>
                )}
                {node.note ? <span className="fc-node-note" title={node.note}>not</span> : null}
              </button>

              <button type="button" className="fc-node-x unstyled-button" disabled={busy}
                      title="Ekranı sil" aria-label={node.title + " ekranını sil"}
                      onClick={() => onDeleteNode(node.id)}>×</button>
              <button type="button" className="fc-plus is-right unstyled-button" disabled={busy}
                      title="Sağa yeni ekran" aria-label={node.title + " sağına yeni ekran ekle"}
                      onClick={() => onAddChild(node.id, "right")}>+</button>
              <button type="button" className="fc-plus is-down unstyled-button" disabled={busy}
                      title="Aşağı yeni ekran" aria-label={node.title + " altına yeni ekran ekle"}
                      onClick={() => onAddChild(node.id, "down")}>+</button>
            </div>
          );
        })}
      </div>
    </div>
  );
}

/** Where a new child goes, so two children never land on each other. */
export function placeChild(flow: FlowTree, parent: FlowNode, side: "right" | "down") {
  const base = side === "right"
    ? { x: parent.x + NODE_W + GAP_X, y: parent.y }
    : { x: parent.x, y: parent.y + NODE_H + GAP_Y };
  let { x, y } = base;
  while (flow.nodes.some((n) => Math.abs(n.x - x) < NODE_W && Math.abs(n.y - y) < NODE_H)) {
    y += NODE_H + GAP_Y;
  }
  return { x, y };
}

/** Right edge of the source to left edge of the target, as a flat S. */
function curve(from: { x: number; y: number }, to: { x: number; y: number }) {
  const x1 = from.x + NODE_W;
  const y1 = from.y + NODE_H / 2;
  const x2 = to.x;
  const y2 = to.y + NODE_H / 2;
  const bend = Math.max(40, Math.abs(x2 - x1) / 2);
  return `M ${x1} ${y1} C ${x1 + bend} ${y1}, ${x2 - bend} ${y2}, ${x2} ${y2}`;
}

function midpoint(from: { x: number; y: number }, to: { x: number; y: number }) {
  return {
    x: (from.x + NODE_W + to.x) / 2,
    y: (from.y + to.y) / 2 + NODE_H / 2,
  };
}

/** The ids already taken, so a new node never collides with a stored file. */
export function nextNodeId(nodes: FlowNode[]): string {
  let n = 1;
  while (nodes.some((node) => node.id === "e" + n)) n++;
  return "e" + n;
}

export function newEdge(from: string, to: string, edges: FlowEdge[]): FlowEdge {
  let n = 1;
  while (edges.some((edge) => edge.id === "t" + n)) n++;
  return { id: "t" + n, from, to, label: "" };
}
