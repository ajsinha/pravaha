/*
 * Pravaha console -- the plain-English controls, made live (docs/guides/AI.md).
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * Drishti's bi-ai.js and about-ask.js, for Pravaha. Every control is a box (data-ai-box) drawn by the
 * server only when the engine's AI layer is on and its policy is open to this person (_ai.html): a form
 * naming its task (data-ai-task) and an output (data-ai-out, a live region). This module shows the
 * controls that need it, sends a form's fields to the console's /api/v1/ai/<task> through the one
 * request helper (which carries the session's CSRF token), and puts the fragment the server drew in the
 * output -- or appends it, on the Ask page, as one more turn of the conversation. It draws an answer's
 * chart from the shape the engine chose (data-ai-chart: kind, x, y, series) in the theme's colours, keeps
 * a question box's conversation, opens and closes the describe and confirm steps, and hands "Open in
 * Workbench" to the editor when it is on the page. Nothing a model wrote is ever put in as markup: the
 * fragments are the server's, every model word in them escaped. No key is ever in the page.
 */
import { t, announce, call, token } from "pravaha/lib.js";
import { themedChart, seriesColor } from "pravaha/charts.js";

if (!window.__pravahaAi) {
  window.__pravahaAi = true;

  const reveal = (root) => root.querySelectorAll("[data-ai-control]").forEach((el) => { el.hidden = false; });

  async function post(task, fields) {
    try {
      return { payload: await call(`/api/v1/ai/${task}`, { json: fields }), status: 200 };
    } catch (err) {
      return { payload: err.payload || {}, status: err.status || 0 };
    }
  }

  function busy(button, on) {
    if (!button) return;
    if (on) {
      button.dataset.idleLabel = button.innerHTML;
      button.disabled = true;
      button.textContent = t("ai.working");
    } else if (button.dataset.idleLabel !== undefined) {
      button.innerHTML = button.dataset.idleLabel;
      delete button.dataset.idleLabel;
      button.disabled = false;
    }
  }

  function unreachable(status) {
    const box = document.createElement("div");
    box.className = "ai-failure";
    box.setAttribute("role", "alert");
    box.setAttribute("data-ai-failure", "transport");
    box.textContent = t("ai.unreachable", { status });
    return box.outerHTML;
  }

  /* ---- an answer's chart: Pravaha's choice from the result's shape, drawn in the theme ---- */
  function drawChart(el) {
    let spec;
    try { spec = JSON.parse(el.dataset.aiChart || "{}"); } catch (e) { return; }
    const xi = spec.columns.indexOf(spec.x);
    const yi = spec.columns.indexOf(spec.y);
    const si = spec.series ? spec.columns.indexOf(spec.series) : -1;
    if (xi < 0 || yi < 0) return;
    const categories = [...new Set(spec.rows.map((r) => String(r[xi])))];
    const groups = new Map();
    spec.rows.forEach((r) => {
      const name = si >= 0 ? String(r[si]) : spec.y;
      if (!groups.has(name)) groups.set(name, new Map());
      groups.get(name).set(String(r[xi]), typeof r[yi] === "number" ? r[yi] : null);
    });
    themedChart(el, () => {
      const ink = token("--d-ink", "#15181D");
      const muted = token("--d-muted", "#646B78");
      const rule = token("--d-border", "#D9DEE6");
      return {
        animation: false,
        textStyle: { fontFamily: token("--d-font-sans", "sans-serif"), color: ink },
        grid: { left: 60, right: 16, top: groups.size > 1 ? 32 : 16, bottom: 40 },
        tooltip: { trigger: "axis", backgroundColor: token("--d-surface", "#fff"), borderColor: rule, textStyle: { color: ink } },
        legend: groups.size > 1 ? { top: 0, textStyle: { color: muted } } : undefined,
        xAxis: { type: "category", data: categories, axisLabel: { color: muted }, axisLine: { lineStyle: { color: rule } } },
        yAxis: { type: "value", name: spec.y, axisLabel: { color: muted }, splitLine: { lineStyle: { color: rule } } },
        series: [...groups.entries()].map(([name, values], i) => ({
          name, type: spec.kind, data: categories.map((c) => (values.has(c) ? values.get(c) : null)),
          itemStyle: { color: seriesColor(i) }, lineStyle: { color: seriesColor(i) }, connectNulls: false,
        })),
      };
    }).catch(() => { el.hidden = true; });
  }

  function placed(node, failed) {
    reveal(node);
    node.querySelectorAll("[data-ai-chart]").forEach(drawChart);
    announce(failed ? t("ai.failed") : t("ai.answered"));
  }

  function conversationOf(form, node) {
    const holder = form.querySelector("[data-ai-conversation]");
    const answers = node.querySelectorAll("[data-ai-answer][data-ai-conversation]");
    if (!holder || !answers.length) return;
    holder.value = answers[answers.length - 1].dataset.aiConversation;
    const fresh = form.querySelector("[data-ai-new]");
    if (fresh) fresh.hidden = false;
    const q = form.querySelector("[data-ai-q]");
    if (q) q.value = "";
  }

  document.addEventListener("submit", async (event) => {
    const form = event.target.closest && event.target.closest("form[data-ai-task]");
    if (!form) return;
    const box = form.closest("[data-ai-box]");
    const out = box && box.querySelector(":scope > [data-ai-out]");
    if (!out) return;
    event.preventDefault();
    const task = form.dataset.aiTask;
    const fields = Object.fromEntries(new FormData(form));
    delete fields.csrf_token;
    if (fields.conversation !== undefined) {
      try { fields.conversation = JSON.parse(fields.conversation || "[]"); } catch (e) { fields.conversation = []; }
    }
    if (form.hasAttribute("data-ai-changes")) {
      fields.changes = window.PravahaLive ? window.PravahaLive.recent() : [];
    }
    const button = event.submitter || form.querySelector("button[type=submit]");
    const say = box.querySelector(":scope > [data-ai-msg]");
    busy(button, true);
    out.setAttribute("aria-busy", "true");
    if (say) say.textContent = t("ai.working");
    const { payload, status } = await post(task, fields);
    busy(button, false);
    out.setAttribute("aria-busy", "false");
    if (say) say.textContent = "";
    const markup = payload.html || unreachable(status);
    let node = out;
    if (box.hasAttribute("data-ai-append")) {
      node = document.createElement("div");
      node.className = "ai-turn";
      node.innerHTML = markup;
      out.appendChild(node);
      node.scrollIntoView({ block: "nearest" });
    } else {
      out.innerHTML = markup;
    }
    placed(node, !payload.ok);
    if (task === "ask") conversationOf(form, node);
    if (payload.ok && (task === "register" || task === "create-alert")) form.hidden = true;
    out.focus({ preventScroll: true });
  });

  document.addEventListener("click", (event) => {
    const target = event.target.closest ? event.target : null;
    if (!target) return;
    const open = target.closest("[data-ai-open]");
    if (open) {
      const panel = document.getElementById(open.dataset.aiOpen);
      if (panel) {
        panel.hidden = false;
        const first = panel.querySelector("textarea, input:not([type=hidden]), button[type=submit]");
        if (first) first.focus();
      }
      return;
    }
    const close = target.closest("[data-ai-close]");
    if (close) {
      const panel = document.getElementById(close.dataset.aiClose);
      if (panel) panel.hidden = true;
      return;
    }
    const fresh = target.closest("[data-ai-new]");
    if (fresh) {
      const box = fresh.closest("[data-ai-box]");
      const holder = box.querySelector("[data-ai-conversation]");
      if (holder) holder.value = "[]";
      box.querySelector(":scope > [data-ai-out]").innerHTML = "";
      fresh.hidden = true;
      announce(t("ai.new_conversation"));
      return;
    }
    const workbench = target.closest("[data-ai-workbench]");
    if (workbench && document.getElementById("workbench-app")) {
      /* On the Workbench itself, the SQL opens as a new editor tab rather than a new page. */
      event.preventDefault();
      const sql = new URL(workbench.href, location.href).searchParams.get("sql") || "";
      window.dispatchEvent(new CustomEvent("pravaha:open-draft", { detail: { sql, title: "" } }));
      const plain = document.getElementById("sql");
      if (plain && plain.tagName === "TEXTAREA") plain.value = sql;
      announce(t("ai.copied"));
      return;
    }
    /* The Workbench island's diagnostics: Explain in plain words beside a refusal code. */
    const why = target.closest("[data-ai-explain-code]");
    if (why) {
      event.preventDefault();
      const slot = why.nextElementSibling;
      if (!slot || !slot.classList.contains("ai-slot")) return;
      busy(why, true);
      post("explain", { kind: "refusal", code: why.dataset.aiExplainCode, sql: why.dataset.aiSql || "" })
        .then(({ payload, status }) => {
          slot.innerHTML = payload.html || unreachable(status);
          placed(slot, !payload.ok);
        })
        .finally(() => busy(why, false));
    }
  });

  reveal(document);
  document.querySelectorAll("[data-ai-chart]").forEach(drawChart);
}
