/*
 * Pravaha console — the eight states of design 23.12.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * Most internal tools are polished on the happy path and raw everywhere else.
 * These are the other seven, written once so a screen cannot quietly implement
 * six of them.
 */
(function () {
  "use strict";

  var esc = window.PravahaApi.escapeHtml;
  var t = window.PravahaApi.t;

  var States = {
    /* Loading, first time: a skeleton the shape of the answer, never a spinner
       on a blank page. */
    loadingFirst: function (columns, rows) {
      var cells = "";
      for (var c = 0; c < (columns || 4); c++) { cells += '<td><div class="skeleton"></div></td>'; }
      var out = "";
      for (var r = 0; r < (rows || 5); r++) { out += "<tr>" + cells + "</tr>"; }
      return out;
    },

    /* Never had any. Explains what this is and offers the action that makes the
       first one. */
    emptyNever: function (what, action) {
      return '<div class="state"><h2>' + esc(t("states.never_title", {what: what})) + "</h2>" +
        "<p>" + esc(t("states.never_body")) + "</p>" + (action || "") + "</div>";
    },

    /* Filtered to nothing, which is a different thing and offers a different way
       out. Conflating the two tells somebody there is no data when there is
       plenty and their filter is wrong. */
    emptyFiltered: function (onClear) {
      return '<div class="state"><h2>' + esc(t("states.filtered_title")) + "</h2>" +
        "<p>" + esc(t("states.filtered_body")) + "</p>" +
        '<button type="button" class="btn btn-sm btn-outline-secondary" onclick="' +
        onClear + '">' + esc(t("states.filtered_clear")) + "</button></div>";
    },

    /* What failed, whether retrying is worth it, and an id to paste into a ticket. */
    error: function (err, retry) {
      var code = err.code
        ? ' <a href="/help/troubleshooting">' + esc(err.code) + "</a>" : "";
      var button = err.retryable && retry
        ? '<button type="button" class="btn btn-sm btn-primary" onclick="' + retry + '">' +
          esc(t("states.retry")) + "</button>"
        : '<span class="chip mute">' + esc(t("states.no_retry")) + "</span>";
      return '<div class="alert alert-danger" role="alert">' +
        '<div class="fw-semibold">' + esc(err.message) + code + "</div>" +
        '<div class="small mono text-muted">' + esc(t("states.correlation")) + " " +
        esc(err.correlation || t("states.not_available")) + "</div>" +
        '<div class="mt-2">' + button + "</div></div>";
    },

    /* Some of it answered. Says what is missing rather than under-reporting in
       silence, which reads as a smaller but complete picture. */
    partial: function (missing) {
      return '<div class="alert alert-warning py-2" role="status">' +
        '<div class="fw-semibold small">' + esc(t("states.partial_title")) + "</div>" +
        '<div class="small">' + esc(t("states.partial_body", {missing: missing})) + "</div></div>";
    },

    /* Disconnected. The data dims and says how old it is; it is never presented
       as live. */
    stale: function (ageSeconds, reconnecting) {
      return '<div class="alert alert-warning py-2" role="status">' +
        '<div class="fw-semibold small">' + esc(t("states.stale_title")) + "</div>" +
        '<div class="small">' + esc(t("states.stale_age", {seconds: ageSeconds})) + " " +
        esc(reconnecting ? t("states.reconnecting") : t("states.not_reconnecting")) + "</div></div>";
    },

    /* No permission. The affordance is disabled with the reason beside it, never a
       button that fails on click. The reason is on the page, not only in a title: a
       disabled button takes no focus and shows no tooltip, so a title alone was a
       reason nobody could read (found by the component gallery). */
    unauthorized: function (action, reason) {
      var id = "unauth-" + Math.random().toString(36).slice(2, 8);
      var why = t("states.unauthorized", {action: action}) + (reason ? ": " + reason : "");
      return '<button type="button" class="btn btn-sm btn-outline-secondary" disabled aria-describedby="' +
        id + '">' + esc(action) + '</button> <span class="small text-muted" id="' + id + '">' +
        esc(why) + "</span>";
    }
  };

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
    this.element.innerHTML = '<span class="dot"></span><span>' + label + "</span>";
  };

  window.PravahaStates = States;
  window.PravahaFreshness = Freshness;
}());
