/*
 * Pravaha console -- a virtualised result grid.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * Only the rows in view (plus a margin) are in the DOM, so a result of tens of thousands
 * of rows scrolls at the same cost as one of fifty -- design 23.15's "no pagination
 * theatre". Plain DOM rather than a component: this is a hot loop on scroll, and the
 * cheapest thing it can do is set textContent on cells it already has.
 */
import { formatValue, isNumericType, t } from "pravaha/lib.js";

const ROW = 28;
const MARGIN = 12;

export class Grid {
  constructor(container, { columns = [], types = [], rows = [], caption = t("grid.caption") } = {}) {
    this.container = container;
    this.container.classList.add("vgrid");
    this.container.setAttribute("tabindex", "0");
    this.container.setAttribute("role", "region");
    this.container.setAttribute("aria-label", t("grid.scrollable", { caption }));
    this.table = document.createElement("table");
    this.table.setAttribute("aria-rowcount", "0");
    const cap = document.createElement("caption");
    cap.className = "visually-hidden";
    cap.textContent = caption;
    this.table.appendChild(cap);
    this.thead = this.table.createTHead();
    this.tbody = this.table.createTBody();
    this.container.replaceChildren(this.table);
    this.onScroll = () => this.paint();
    this.container.addEventListener("scroll", this.onScroll, { passive: true });
    this.set(columns, types, rows);
  }

  set(columns, types, rows) {
    this.columns = columns || [];
    this.types = types || [];
    this.rows = rows || [];
    this.numeric = this.columns.map((_, i) => isNumericType(this.types[i]));
    const head = document.createElement("tr");
    this.columns.forEach((name, i) => {
      const th = document.createElement("th");
      th.scope = "col";
      th.textContent = name;
      if (this.types[i]) {
        const type = document.createElement("span");
        type.className = "field-type";
        type.textContent = this.types[i];
        th.appendChild(type);
      }
      if (this.numeric[i]) th.style.textAlign = "right";
      head.appendChild(th);
    });
    this.thead.replaceChildren(head);
    this.table.setAttribute("aria-rowcount", String(this.rows.length + 1));
    this.first = -1; this.last = -1;
    this.container.scrollTop = 0;
    this.paint(true);
  }

  paint(force = false) {
    const height = this.container.clientHeight || 360;
    const top = this.container.scrollTop;
    const first = Math.max(0, Math.floor(top / ROW) - MARGIN);
    const last = Math.min(this.rows.length, Math.ceil((top + height) / ROW) + MARGIN);
    if (!force && first === this.first && last === this.last) return;
    this.first = first; this.last = last;
    const frag = document.createDocumentFragment();
    frag.appendChild(this.spacer(first * ROW));
    for (let r = first; r < last; r++) {
      const tr = document.createElement("tr");
      tr.setAttribute("aria-rowindex", String(r + 2));
      const row = this.rows[r];
      for (let c = 0; c < this.columns.length; c++) {
        const td = document.createElement("td");
        const value = row[c];
        td.textContent = formatValue(value);
        if (value === null || value === undefined) td.className = "null";
        else if (this.numeric[c]) td.className = "num";
        tr.appendChild(td);
      }
      frag.appendChild(tr);
    }
    frag.appendChild(this.spacer((this.rows.length - last) * ROW));
    this.tbody.replaceChildren(frag);
  }

  spacer(px) {
    const tr = document.createElement("tr");
    tr.className = "spacer";
    tr.setAttribute("aria-hidden", "true");
    const td = document.createElement("td");
    td.colSpan = Math.max(1, this.columns.length);
    td.style.height = px + "px";
    tr.appendChild(td);
    return tr;
  }

  /** The rows as CSV, for a reader who wants them in a spreadsheet. */
  csv() {
    const quote = (v) => {
      const text = v === null || v === undefined ? "" : (typeof v === "object" ? JSON.stringify(v) : String(v));
      return /[",\n]/.test(text) ? '"' + text.replace(/"/g, '""') + '"' : text;
    };
    return [this.columns.map(quote).join(",")]
      .concat(this.rows.map((row) => row.map(quote).join(","))).join("\n");
  }
}
