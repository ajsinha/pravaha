/*
 * Pravaha console — stepping a debug session.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * The server rendered the session and every control is a real form, so the screen steps with
 * no script at all — each post answers with the page carrying that step's report. What this
 * adds is the thing a person stepping fifty times wants: the step happens without navigating,
 * and the reports stack newest first, so the sequence is readable as a sequence.
 *
 * Nothing is polled and no socket is opened. A fork moves only when somebody steps it
 * (ADR-048 3: no feed thread, no watermark clock, no periodic checkpointer), so there is
 * nothing to arrive that was not asked for. Design 23.11 reserves a WebSocket for this
 * screen and ADR-048 did not build one, for exactly that reason.
 *
 * After a step the panels below follow the fork, each from the best source it has:
 *
 * - The view is redrawn from the step's own report. The page carries the whole view it read
 *   (the engine's debug view is the whole view, not a page of it), and that view plus the
 *   step's viewChanges, consolidated as a Z-set, is the view after the step. The result is
 *   checked against the step's viewSize before it is drawn; a mismatch is said, not drawn.
 * - Operator state cannot be: a step reports each operator's rows in and out, not how many
 *   entries it holds, and those are not derivable from each other (a window fires and
 *   evicts, a join retains). So the entry counts are read again from the engine after the
 *   step, which reads the same position because a fork moves only when it is stepped.
 * - An open page of one operator's entries is not re-read; it says it is as loaded.
 */
(function () {
  "use strict";
  var api = window.PravahaApi, States = window.PravahaStates;
  var root = document.getElementById("dbg-app");
  if (!root || !api) { return; }

  var session = root.getAttribute("data-session");
  var log = document.getElementById("dbg-log");
  var empty = document.getElementById("dbg-log-empty");

  function esc(value) { return api.escapeHtml(value); }
  function num(value) { return Number(value || 0).toLocaleString(); }

  function weight(value) {
    /* A Z-set weight, with a real minus sign: an update is the old row withdrawn at −1 and
       the new one inserted at +1, and a hyphen reads as a dash at this size. */
    return Number(value) < 0 ? "−" + Math.abs(Number(value)) : "+" + Number(value);
  }

  function rowsTable(rows) {
    if (!rows.length) {
      return '<p class="small text-muted mb-3" data-empty="rows">' + esc(api.t("debug.no_rows")) + "</p>";
    }
    var head = ["debug.weight", "debug.stream", "debug.position", "debug.event_time", "debug.values"]
      .map(function (key, i) {
        return '<th scope="col"' + (i === 0 ? ' class="num"' : "") + ">" + esc(api.t(key)) + "</th>";
      }).join("");
    var body = rows.map(function (row) {
      return '<tr><td class="num">' + esc(weight(row.weight)) + "</td><td>" + esc(row.stream)
        + "</td><td>" + esc(row.partition) + "#" + esc(row.offset) + '</td><td class="num">'
        + esc(row.eventTimeNanos) + "</td><td>" + esc((row.values || []).join(", ")) + "</td></tr>";
    }).join("");
    return '<div class="table-responsive"><table class="table table-sm small mono mb-3"><thead><tr>' + head
      + "</tr></thead><tbody>" + body + "</tbody></table></div>";
  }

  function operatorsTable(operators) {
    var head = ["debug.node", "debug.operator", "debug.in", "debug.out"]
      .map(function (key, i) {
        return '<th scope="col"' + (i > 1 ? ' class="num"' : "") + ">" + esc(api.t(key)) + "</th>";
      }).join("");
    var body = (operators || []).map(function (op) {
      return '<tr><td class="mono">' + esc(op.id) + "</td><td>" + esc(op.label)
        + '</td><td class="num mono">' + esc(op.rowsIn) + '</td><td class="num mono">'
        + esc(op.rowsOut) + "</td></tr>";
    }).join("");
    return '<div class="table-responsive"><table class="table table-sm small mb-3 dbg-operators"><thead><tr>' + head
      + "</tr></thead><tbody>" + body + "</tbody></table></div>";
  }

  function changesTable(changes) {
    if (!changes.length) {
      return '<p class="small text-muted mb-3" data-empty="changes">' + esc(api.t("debug.no_changes")) + "</p>";
    }
    var body = changes.map(function (change) {
      return '<tr><td class="num">' + esc(weight(change.weight)) + "</td><td>"
        + esc((change.values || []).join(", ")) + "</td></tr>";
    }).join("");
    return '<div class="table-responsive"><table class="table table-sm small mono mb-3">'
      + '<thead><tr><th scope="col" class="num">' + esc(api.t("debug.weight"))
      + '</th><th scope="col">' + esc(api.t("debug.values"))
      + "</th></tr></thead><tbody>" + body + "</tbody></table></div>";
  }

  function report(step) {
    var rows = step.rowsIn || [], changes = step.viewChanges || [];
    var watermark = step.watermarkNanos === null || step.watermarkNanos === undefined
      ? api.t("debug.no_watermark") : String(step.watermarkNanos);
    var article = document.createElement("article");
    article.className = "card mb-3 dbg-report";
    article.innerHTML =
      '<div class="card-header d-flex justify-content-between align-items-center"><span>'
      + esc(api.t("debug.step", {n: step.sequence, kind: step.kind})) + '</span><span class="chip '
      + (step.exhausted ? "warn" : "mute") + '">' + esc(step.stopped) + "</span></div>"
      + '<div class="card-body">'
      + '<h3 class="section-label mt-0">' + esc(api.t("debug.rows_in", {n: rows.length})) + "</h3>"
      + rowsTable(rows)
      + '<h3 class="section-label">' + esc(api.t("debug.operators")) + "</h3>"
      + operatorsTable(step.operators)
      + '<h3 class="section-label">' + esc(api.t("debug.changes", {n: changes.length})) + "</h3>"
      + changesTable(changes)
      + '<p class="small text-muted mb-0">' + esc(api.t("debug.tail", {
        rows: num(step.rowsConsumed), view: num(step.viewSize), watermark: watermark
      })) + "</p></div>";
    return article;
  }

  function summarise(step) {
    var set = function (field, text) {
      var chip = document.querySelector('#dbg-summary [data-field="' + field + '"]');
      if (chip) { chip.textContent = text; }
    };
    set("steps", api.t("debug.steps", {n: step.sequence}));
    set("rowsConsumed", api.t("debug.rows", {n: num(step.rowsConsumed)}));
    set("viewSize", api.t("debug.view_size", {n: num(step.viewSize)}));
    /* The chip's own wording, not the report tail's: "none" ends a sentence there and would
       be a label meaning nothing here. */
    set("watermarkNanos", step.watermarkNanos === null || step.watermarkNanos === undefined
      ? api.t("debug.no_watermark_chip") : api.t("debug.watermark", {n: step.watermarkNanos}));
  }

  function say(id, anchor, text) {
    /* One note per panel, replaced rather than stacked: it describes the latest step. */
    var old = document.getElementById(id);
    if (old) { old.remove(); }
    if (!anchor || !text) { return; }
    var note = document.createElement("p");
    note.className = "small text-muted";
    note.id = id;
    note.setAttribute("role", "status");
    note.textContent = text;
    anchor.parentNode.insertBefore(note, anchor.nextSibling);
  }

  /* The view as the page read it, as a Z-set keyed by the row's values. Null when the page
     has no view to start from (its read failed), which leaves nothing to add a step to. */
  var view = (function () {
    var data = document.getElementById("dbg-view-data");
    if (!data) { return null; }
    var rows;
    try { rows = JSON.parse(data.textContent || "[]"); } catch (e) { return null; }
    return applied([], rows);
  }());

  /* `rows` with `changes` added, as a consolidated Z-set: a row whose weight reaches zero leaves,
     and a row new to the view takes the place of one this same step withdrew, so an update
     (old row at −1, new row at +1) stays where the reader was looking rather than moving to
     the bottom. Otherwise a new row is appended. */
  function applied(rows, changes) {
    var out = rows.slice();
    var vacant = [];
    changes.forEach(function (change) {
      var key = JSON.stringify(change.values || []);
      var by = Number(change.weight || 0);
      var at = out.findIndex(function (row) { return row !== null && row.key === key; });
      if (at >= 0) {
        var weight = out[at].weight + by;
        out[at] = weight === 0 ? null : {key: key, weight: weight, values: out[at].values};
        if (weight === 0) { vacant.push(at); }
      } else if (by !== 0) {
        var entry = {key: key, weight: by, values: change.values || []};
        if (vacant.length) { out[vacant.shift()] = entry; } else { out.push(entry); }
      }
    });
    return out.filter(function (row) { return row !== null; });
  }

  function redrawView(step) {
    var panel = document.getElementById("dbg-view-panel");
    if (!panel || view === null) { return; }
    var next = applied(view, step.viewChanges || []);
    if (next.length !== Number(step.viewSize || 0)) {
      /* The page's view plus this step's changes is not the view the engine reports. Drawing
         it anyway would be a view nobody read; the reader is told, and reloading reads it. */
      view = null;
      say("dbg-view-stale", panel, api.t("debug.view_unredrawn", {got: num(next.length), want: num(step.viewSize)}));
      return;
    }
    view = next;
    var body = document.querySelector("#dbg-view tbody");
    if (body) {
      body.innerHTML = view.map(function (row) {
        return '<tr><td class="num">' + esc(weight(row.weight)) + "</td><td>"
          + esc(row.values.join(", ")) + "</td></tr>";
      }).join("");
    }
    document.getElementById("dbg-view-wrap").hidden = view.length === 0;
    document.getElementById("dbg-no-view-wrap").hidden = view.length !== 0;
  }

  async function rereadSlots() {
    var table = document.getElementById("dbg-slots");
    if (!table) { return; }
    try {
      var answer = await api.call("/debug/sessions/" + encodeURIComponent(session) + "/state");
      var slots = answer.slots || [];
      var rows = table.querySelectorAll("tbody tr[data-slot]");
      var same = slots.length === rows.length && slots.every(function (slot) {
        return table.querySelector('tr[data-slot="' + CSS.escape(String(slot.id)) + '"]');
      });
      if (!same) { throw new Error(api.t("debug.state_changed")); }
      slots.forEach(function (slot) {
        table.querySelector('tr[data-slot="' + CSS.escape(String(slot.id)) + '"] [data-field="entries"]')
          .textContent = num(slot.entries);
      });
      say("dbg-slots-stale", table.parentNode, "");
    } catch (error) {
      say("dbg-slots-stale", table.parentNode,
        api.t("debug.state_unread", {error: (error && error.message) || String(error)}));
    }
  }

  function pageAsLoaded() {
    /* Said once: a page of entries is a read at a position, and is not re-read per step. */
    var page = document.getElementById("dbg-state-page") || document.getElementById("dbg-page-empty")
      || document.getElementById("dbg-page-filtered");
    if (!page || document.getElementById("dbg-page-stale")) { return; }
    var operator = root.getAttribute("data-operator") || "";
    say("dbg-page-stale", page.closest(".table-responsive") || page, api.t("debug.page_stale", {operator: operator}));
  }

  async function follow(step) {
    redrawView(step);
    pageAsLoaded();
    await rereadSlots();
  }

  function failed(error) {
    var banner = document.createElement("div");
    banner.id = "dbg-step-error";
    banner.innerHTML = States.error(error, true);
    log.insertBefore(banner, log.firstChild);
    /* Retry reloads rather than steps again. A step the engine refused by name (PRV-8015)
       offers no retry at all, because the request itself was wrong; one that failed without
       an answer may or may not have been applied, and the honest way to find out is to ask
       the engine what the session has consumed. */
    var retry = banner.querySelector("[data-state-action=retry]");
    if (retry) { retry.addEventListener("click", function () { window.location.reload(); }); }
  }

  async function take(spec, button) {
    var was = button ? button.textContent : "";
    if (button) { button.disabled = true; button.textContent = api.t("debug.stepping"); }
    try {
      var old = document.getElementById("dbg-step-error");
      if (old) { old.remove(); }
      var step = await api.call("/debug/sessions/" + encodeURIComponent(session) + "/step",
        {method: "POST", body: JSON.stringify({step: spec})});
      if (empty) { empty.remove(); empty = null; }
      log.insertBefore(report(step), log.firstChild);
      summarise(step);
      await follow(step);
    } catch (error) {
      /* A refusal is the engine's, with its code: an unreadable step, a predicate naming a
         column the view does not have, a session that has expired. Shown and kept, because
         the next thing the reader does is fix the spec and step again. */
      failed(error);
    } finally {
      if (button) { button.disabled = false; button.textContent = was; }
    }
  }

  function intercept(form, field) {
    if (!form) { return; }
    form.addEventListener("submit", function (event) {
      /* Which button was pressed: the three quick steps are one form and each carries its own
         spec, so the submitter is the step. `activeElement` is the fallback for a browser
         without SubmitEvent.submitter. */
      var pressed = event.submitter
        || (document.activeElement && document.activeElement.form === form
          ? document.activeElement : null);
      var spec = field ? (document.getElementById(field) || {}).value
        : (pressed && pressed.value);
      /* Nothing to send: let the form post and the server refuse it by name, rather than
         deciding here what an empty box meant. */
      if (!spec) { return; }
      event.preventDefault();
      take(spec, pressed && pressed.tagName === "BUTTON" ? pressed : null);
    });
  }
  intercept(document.getElementById("dbg-step-quick"), null);
  intercept(document.getElementById("dbg-step-form"), "dbg-step");
}());
