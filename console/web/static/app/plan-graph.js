/*
 * Pravaha console -- a query plan, drawn as a graph.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * Operators are nodes; rows flow left to right from the scans to the root, which is the
 * direction a reader traces data. Laid out by ELK's layered algorithm, which is
 * deterministic: the same plan draws the same picture every time, so nodes never jump
 * between renders (design 23.8 -- instability here destroys trust in the whole screen).
 *
 * Drawn as SVG rather than canvas so it is crisp at any zoom, keyboard-focusable node by
 * node, and exportable for an incident write-up. The plan's text form stays beside it as
 * the accessible equivalent.
 */

import { t } from "pravaha/lib.js";

/* Operator families, labelled from the string catalog (js.plan.family.*). */
const FAMILIES = ["source", "filter", "project", "aggregate", "join", "sink", "other"];
const SVG = "http://www.w3.org/2000/svg";

/* ELK and ECharts are UMD bundles. On a page where Monaco's AMD loader has defined
   `define`, a UMD bundle registers itself as an AMD module and never sets its global --
   so `define` is hidden while the script runs. */
export function loadGlobalScript(src, globalName) {
  if (window[globalName]) return Promise.resolve(window[globalName]);
  return new Promise((resolve, reject) => {
    const saved = window.define;
    window.define = undefined;
    const script = document.createElement("script");
    script.src = src;
    script.onload = () => { window.define = saved; resolve(window[globalName]); };
    script.onerror = () => { window.define = saved; reject(new Error("could not load " + src)); };
    document.head.appendChild(script);
  });
}

let elk = null;
async function engine() {
  if (!elk) {
    const ELK = await loadGlobalScript("/static/vendor/elkjs/elk.bundled.js", "ELK");
    elk = new ELK();
  }
  return elk;
}

function textWidth(text, px) { return Math.ceil(String(text).length * px * 0.6); }

function el(name, attrs = {}, parent) {
  const node = document.createElementNS(SVG, name);
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, String(v));
  if (parent) parent.appendChild(node);
  return node;
}

let drawn = 0;
/* How a compared plan marks an operator (the workbench's diff, design 23.7): never by colour
   alone -- a glyph in the node and a word in its accessible name say it too. */
const MARKS = { added: "+", removed: "\u2212", changed: "~" };

/**
 * Renders `graph` ({nodes, edges} from the console's /sql/explain) into `container`.
 * `onSelect(node)` is called when a node is clicked or chosen with Enter. `marks`, from a plan
 * diff, maps a node id to added / removed / changed / same; `label` names the whole graph.
 */
export async function renderPlan(container, graph, { onSelect, marks = null, label = null } = {}) {
  container.replaceChildren();
  if (!graph || !graph.nodes || !graph.nodes.length) {
    container.innerHTML = `<div class="state"><h2>${t("plan.empty.title")}</h2><p>${t("plan.empty.body")}</p></div>`;
    return null;
  }
  const layoutGraph = {
    id: "root",
    layoutOptions: {
      "elk.algorithm": "layered",
      "elk.direction": "RIGHT",
      "elk.layered.spacing.nodeNodeBetweenLayers": "56",
      "elk.spacing.nodeNode": "22",
      "elk.edgeRouting": "ORTHOGONAL",
      "elk.layered.nodePlacement.strategy": "BRANDES_KOEPF",
      "elk.padding": "[top=16,left=16,bottom=16,right=16]",
    },
    children: graph.nodes.map((n) => {
      const detail = n.detail.length > 44 ? n.detail.slice(0, 43) + "…" : n.detail;
      const badge = marks && MARKS[marks[n.id]] ? 18 : 0;
      return { id: n.id, width: Math.max(120, textWidth(n.op, 12) + 34 + badge, textWidth(detail, 10.5) + 26),
               height: detail ? 48 : 34, _detail: detail };
    }),
    edges: graph.edges.map((e) => ({ id: e.id, sources: [e.source], targets: [e.target] })),
  };
  const laid = await (await engine()).layout(layoutGraph);
  const byId = Object.fromEntries(graph.nodes.map((n) => [n.id, n]));

  const svg = el("svg", { width: laid.width, height: laid.height, viewBox: `0 0 ${laid.width} ${laid.height}`,
                          role: "group", "aria-label": label || t("plan.label", { n: graph.nodes.length }) });
  const defs = el("defs", {}, svg);
  /* One id per drawing: two plans side by side must not share an arrowhead's id. */
  const arrow = "plan-arrow-" + (drawn += 1);
  const marker = el("marker", { id: arrow, viewBox: "0 0 10 10", refX: 9, refY: 5,
                                markerWidth: 7, markerHeight: 7, orient: "auto-start-reverse" }, defs);
  el("path", { d: "M0,0 L10,5 L0,10 z", class: "plan-arrow" }, marker);

  for (const edge of laid.edges || []) {
    for (const section of edge.sections || []) {
      const points = [section.startPoint, ...(section.bendPoints || []), section.endPoint];
      el("path", { d: "M" + points.map((p) => `${p.x},${p.y}`).join(" L"), class: "plan-edge",
                   "marker-end": `url(#${arrow})` }, svg);
    }
  }

  const nodes = [];
  for (const child of laid.children) {
    const n = byId[child.id];
    const mark = marks ? marks[n.id] : null;
    const said = mark && MARKS[mark] ? ", " + t("plan.mark." + mark) : "";
    const g = el("g", { class: "plan-node" + (mark ? " diff-" + mark : ""), transform: `translate(${child.x},${child.y})`,
                        tabindex: 0, role: "button", "aria-label": `${n.op} ${n.detail}`.trim() + said,
                        "data-op": n.op }, svg);
    el("title", {}, g).textContent = n.label + said;
    el("rect", { width: child.width, height: child.height, rx: 6 }, g);
    el("rect", { class: "bar fam-" + n.family, width: 5, height: child.height, rx: 2 }, g);
    el("text", { x: 14, y: 20, "font-weight": 600 }, g).textContent = n.op;
    if (mark && MARKS[mark]) {
      el("text", { x: child.width - 16, y: 20, class: "mark", "aria-hidden": "true" }, g).textContent = MARKS[mark];
    }
    if (child._detail) el("text", { x: 14, y: 37, class: "detail" }, g).textContent = child._detail;
    const choose = () => {
      nodes.forEach((x) => x.classList.remove("selected"));
      g.classList.add("selected");
      if (onSelect) onSelect(n);
    };
    g.addEventListener("click", choose);
    g.addEventListener("keydown", (event) => {
      if (event.key === "Enter" || event.key === " ") { event.preventDefault(); choose(); }
      const at = nodes.indexOf(g);
      if (event.key === "ArrowRight" || event.key === "ArrowDown") { event.preventDefault(); (nodes[at + 1] || g).focus(); }
      if (event.key === "ArrowLeft" || event.key === "ArrowUp") { event.preventDefault(); (nodes[at - 1] || g).focus(); }
    });
    nodes.push(g);
  }
  container.appendChild(svg);
  return svg;
}

export function legend(families) {
  const used = [...new Set(families)];
  return used.map((f) => `<span><i class="fam-${f}" style="background:var(--${familyVar(f)})"></i>${FAMILIES.includes(f) ? t("plan.family." + f) : f}</span>`).join("");
}

function familyVar(family) {
  return { source: "series-1", filter: "series-3", project: "series-7", aggregate: "series-2",
           join: "series-5", sink: "series-6" }[family] || "edge";
}

/** The SVG as a standalone file, with the theme's colours resolved into it. */
export function exportSvg(svg, filename = "pravaha-plan.svg") {
  const clone = svg.cloneNode(true);
  const style = getComputedStyle(document.documentElement);
  const css = [
    `.plan-node rect{fill:${style.getPropertyValue("--raised")};stroke:${style.getPropertyValue("--edge")}}`,
    `.plan-node text{fill:${style.getPropertyValue("--ink")};font-family:sans-serif;font-size:12px}`,
    `.plan-node text.detail{fill:${style.getPropertyValue("--muted")};font-family:monospace;font-size:10.5px}`,
    `.plan-edge{fill:none;stroke:${style.getPropertyValue("--edge")};stroke-width:1.5}`,
    `.plan-arrow{fill:${style.getPropertyValue("--edge")}}`,
    ...FAMILIES.map(
      (f) => `.plan-node .fam-${f}{fill:${style.getPropertyValue("--" + familyVar(f))}}`),
  ].join("\n");
  const styleEl = document.createElementNS(SVG, "style");
  styleEl.textContent = css;
  clone.insertBefore(styleEl, clone.firstChild);
  clone.setAttribute("xmlns", SVG);
  const blob = new Blob([new XMLSerializer().serializeToString(clone)], { type: "image/svg+xml" });
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  link.download = filename;
  link.click();
  setTimeout(() => URL.revokeObjectURL(link.href), 1000);
}
