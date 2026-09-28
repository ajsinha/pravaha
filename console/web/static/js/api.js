/*
 * Pravaha console — talking to the service layer.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * One place where a failed request becomes something a reader can act on, so
 * that every screen reports a refusal the same way. The cause is the engine's
 * message, the fix is usually implied by its PRV code, and the correlation id is
 * generated here so what appears on screen is the same string that is in the
 * console's log — which is the whole point of having one.
 */
(function () {
  "use strict";

  var API = "/api/v1";

  function correlationId() {
    if (window.crypto && window.crypto.randomUUID) {
      return window.crypto.randomUUID().slice(0, 8);
    }
    return Math.random().toString(16).slice(2, 10);
  }

  /* The session's CSRF token (base.html's meta tag), sent on every call: the console refuses a
     POST, PUT, PATCH or DELETE without it, and sending it on a GET costs nothing. */
  function csrfToken() {
    var meta = document.querySelector('meta[name="csrf-token"]');
    return meta ? meta.getAttribute("content") || "" : "";
  }

  async function call(path, options) {
    options = options || {};
    var correlation = correlationId();
    var headers = Object.assign(
      {"Content-Type": "application/json", "X-Correlation-Id": correlation,
       "X-CSRF-Token": csrfToken()},
      options.headers || {});
    var response = await fetch(API + path, Object.assign({}, options, {headers: headers}));
    var payload = await response.json().catch(function () { return {}; });
    if (!response.ok) {
      var error = new Error(payload.error || t("api.failed", {status: response.status}));
      error.status = response.status;
      error.code = payload.code;
      error.correlation = correlation;
      /* The body too: a screen that draws the server's own account of a refusal (the assistant's
         fragments) needs more than the one sentence. */
      error.payload = payload;
      /* 5xx is worth retrying; a 400 means the request itself was rejected and
         retrying it unchanged will fail again, so offering a retry would be a
         lie about what the button does. */
      error.retryable = response.status >= 500;
      throw error;
    }
    return payload;
  }

  /* The `js.*` keys of the UI string catalog, as lib.js gives them to the islands: embedded by
     the shell as JSON, read on first use (these scripts load before that element is parsed, and
     call this only later). A key the catalog lacks comes back as itself, which a test prevents. */
  var messages = null;
  function t(key, params) {
    if (messages === null) {
      try { messages = JSON.parse(document.getElementById("i18n-messages").textContent || "{}"); }
      catch (e) { messages = {}; }
    }
    var template = messages[key];
    if (template === undefined) { return key; }
    params = params || {};
    return template.replace(/\{(\w+)\}/g, function (whole, name) {
      return Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : whole;
    });
  }

  function escapeHtml(value) {
    return String(value === null || value === undefined ? "" : value)
      .replace(/[&<>"']/g, function (c) {
        return {"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"}[c];
      });
  }

  /* Every filter in the URL (a 23.20 requirement), which is what makes the back
     button work and a narrowed-down view pasteable into a ticket. */
  var Url = {
    read: function () {
      var out = {};
      new URLSearchParams(window.location.search).forEach(function (v, k) { out[k] = v; });
      return out;
    },
    write: function (params, push) {
      var search = new URLSearchParams();
      Object.keys(params).forEach(function (k) {
        if (params[k] !== "" && params[k] !== null && params[k] !== undefined) {
          search.set(k, params[k]);
        }
      });
      var url = window.location.pathname + (search.toString() ? "?" + search : "");
      if (push) { history.pushState(null, "", url); } else { history.replaceState(null, "", url); }
    }
  };

  window.PravahaApi = {call: call, escapeHtml: escapeHtml, Url: Url, base: API, t: t, csrfToken: csrfToken};
}());
