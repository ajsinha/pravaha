/*
 * Pravaha console — the eight states of design 23.12.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * Most internal tools are polished on the happy path and raw everywhere else.
 * These are the other seven, written once so a screen cannot quietly implement
 * six of them. The classic scripts call them directly; the islands through
 * lib.js's `states`, which renders the same markup; and the server-rendered
 * screens through the macros in templates/_states.html, which write the same
 * markup again for a page that has no script yet. One look, three callers.
 *
 * An action -- retry, clear the filter -- is either an inline handler (a string,
 * for the classic scripts) or `true`, which marks the button with
 * data-state-action for the caller to wire (lib.js does, for the islands).
 */
(function () {
  "use strict";

  var esc = window.PravahaApi.escapeHtml;
  var t = window.PravahaApi.t;

  function button(kind, handler, label, extra) {
    var wired = handler === true ? ' data-state-action="' + kind + '"'
      : ' onclick="' + esc(handler) + '"';
    return '<button type="button" class="btn btn-sm ' + extra + '"' + wired + ">" + esc(label) + "</button>";
  }

  var States = {
    /* Loading, first time: a skeleton the shape of the answer, never a spinner
       on a blank page. As table rows, for a table that is waiting for its rows. */
    loadingFirst: function (columns, rows) {
      var cells = "";
      for (var c = 0; c < (columns || 4); c++) { cells += '<td><div class="skeleton"></div></td>'; }
      var out = "";
      for (var r = 0; r < (rows || 5); r++) { out += "<tr>" + cells + "</tr>"; }
      return out;
    },

    /* The same, for an answer that is not a table: a block the height of what is coming.
       Labelled, because a skeleton is invisible to a screen reader otherwise. */
    loadingBlock: function (height) {
      return '<div class="skeleton" role="status" aria-label="' + esc(t("states.loading")) +
        '" style="height:' + (Number(height) || 120) + 'px"></div>';
    },

    /* Never had any. Explains what this is and offers the action that makes the
       first one. `body` says what this is on the screen asking; without one, the
       sentence about registered queries the queries list has always shown. */
    emptyNever: function (what, action, body) {
      return '<div class="state"><h2>' + esc(t("states.never_title", {what: what})) + "</h2>" +
        "<p>" + esc(body || t("states.never_body")) + "</p>" + (action || "") + "</div>";
    },

    /* Filtered to nothing, which is a different thing and offers a different way
       out. Conflating the two tells somebody there is no data when there is
       plenty and their filter is wrong. */
    emptyFiltered: function (onClear, body) {
      return '<div class="state"><h2>' + esc(t("states.filtered_title")) + "</h2>" +
        "<p>" + esc(body || t("states.filtered_body")) + "</p>" +
        button("clear", onClear, t("states.filtered_clear"), "btn-outline-secondary") + "</div>";
    },

    /* What failed, whether retrying is worth it, and an id to paste into a ticket. The
       code links to its own page, which says what it means and what to do. */
    error: function (err, retry) {
      var code = err.code
        ? ' <a href="/help/codes/' + encodeURIComponent(err.code) + '">' + esc(err.code) + "</a>" : "";
      var action = err.retryable && retry
        ? button("retry", retry, t("states.retry"), "btn-primary")
        : '<span class="chip mute">' + esc(t("states.no_retry")) + "</span>";
      var what = err.title
        ? '<div class="fw-semibold">' + esc(err.title) + "</div><div>" + esc(err.message) + code + "</div>"
        : '<div class="fw-semibold">' + esc(err.message) + code + "</div>";
      return '<div class="alert alert-danger" role="alert">' + what +
        /* data-volatile: the id is different on every call, so a screenshot masks it. */
        '<div class="small mono text-muted" data-volatile>' + esc(t("states.correlation")) + " " +
        esc(err.correlation || t("states.not_available")) + "</div>" +
        '<div class="mt-2">' + action + "</div></div>";
    },

    /* Some of it answered. Says what is missing rather than under-reporting in
       silence, which reads as a smaller but complete picture. */
    partial: function (missing, body) {
      return '<div class="alert alert-warning py-2" role="status">' +
        '<div class="fw-semibold small">' + esc(t("states.partial_title")) + "</div>" +
        '<div class="small">' + esc(body || t("states.partial_body", {missing: missing})) + "</div></div>";
    },

    /* Disconnected. The data dims and says how old it is; it is never presented
       as live. `ageSeconds` null is "never updated since this page loaded". */
    stale: function (ageSeconds, reconnecting) {
      var age = ageSeconds === null || ageSeconds === undefined
        ? t("states.stale_since_load") : t("states.stale_age", {seconds: ageSeconds});
      return '<div class="alert alert-warning py-2" role="status">' +
        '<div class="fw-semibold small">' + esc(t("states.stale_title")) + "</div>" +
        '<div class="small">' + esc(age) + " " +
        esc(reconnecting ? t("states.reconnecting") : t("states.not_reconnecting")) + "</div></div>";
    },

    /* No permission. The affordance is disabled with the reason beside it, never a
       button that fails on click. The reason is on the page, not only in a title: a
       disabled button takes no focus and shows no tooltip, so a title alone was a
       reason nobody could read (found by the component gallery). The id is derived
       from what it says, so a screen that redraws gets the same markup again. */
    unauthorized: function (action, reason) {
      var why = t("states.unauthorized", {action: action}) + (reason ? ": " + reason : "");
      var id = "unauth-" + hash(action + "\u0000" + (reason || ""));
      return '<button type="button" class="btn btn-sm btn-outline-secondary" disabled aria-describedby="' +
        id + '">' + esc(action) + '</button> <span class="small text-muted" id="' + id + '">' +
        esc(why) + "</span>";
    }
  };

  function hash(text) {
    var h = 5381;
    for (var i = 0; i < text.length; i++) { h = ((h << 5) + h + text.charCodeAt(i)) | 0; }
    return (h >>> 0).toString(36);
  }

  /* How old what is on screen is. Separate from the data because "loading for
     the first time" and "refreshing something already shown" must look
     different: the second keeps the old numbers visible and moves an indicator,
     and the first has no numbers to keep. */
  function Freshness(element) {
    this.element = element;
    this.updatedAt = null;
    this.state = "fresh";
    var self = this;
    setInterval(function () { self.render(); }, 1000);
  }
  Freshness.prototype.refreshing = function () { this.state = "refreshing"; this.render(); };
  Freshness.prototype.updated = function () {
    this.updatedAt = Date.now(); this.state = "fresh"; this.render();
  };
  Freshness.prototype.stale = function () { this.state = "stale"; this.render(); };
  Freshness.prototype.age = function () {
    return this.updatedAt ? Math.round((Date.now() - this.updatedAt) / 1000) : null;
  };
  Freshness.prototype.render = function () {
    if (!this.element) return;
    var age = this.age();
    var label = this.state === "refreshing" ? t("states.refreshing")
      : age === null ? t("states.never_loaded")
      : age < 2 ? t("states.just_now") : t("states.ago", {seconds: age});
    this.element.setAttribute("data-state", this.state);
    this.element.innerHTML = '<span class="dot"></span><span>' + esc(label) + "</span>";
  };

  window.PravahaStates = States;
  window.PravahaFreshness = Freshness;
}());
