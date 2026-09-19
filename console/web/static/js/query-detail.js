/*
 * Pravaha console — one query.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * The controls are real forms and work without this file. This intercepts them
 * so the page does not reload, and starts the live tail.
 */
(function () {
  "use strict";
  var api = window.PravahaApi, States = window.PravahaStates;
  var target = document.getElementById("tail");
  if (!target) { return; }
  var view = target.getAttribute("data-view");

  var tailState = document.getElementById("tail-state");
  var banner = document.getElementById("tail-banner");
  var lastLive = null, stale = false;
  function renderBanner() {
    if (!banner) { return; }
    banner.innerHTML = stale
      ? States.stale(lastLive ? Math.round((Date.now() - lastLive) / 1000) : null, true) : "";
  }
  setInterval(function () { if (stale) { renderBanner(); } }, 1000);
  var tail = new window.PravahaLiveTail(view, target, {
    onState: function (state, detail) {
      tailState.setAttribute("data-state",
        state === "live" ? "fresh" : state === "lagging" ? "refreshing" : "stale");
      tailState.innerHTML = '<span class="dot"></span><span>' +
        api.escapeHtml(detail || api.t("tail.state." + state)) + "</span>";
      if (state === "live") { lastLive = Date.now(); }
      /* Dimmed when the stream is gone, so nobody reads a frozen tail as a quiet
         one. On a stream those look identical and only one is a problem. The banner
         says how long ago it was live, and that EventSource is retrying. */
      target.classList.toggle("stale", state === "stale");
      stale = state === "stale";
      renderBanner();
    }
  });
  tail.start();
  window.addEventListener("beforeunload", function () { tail.stop(); });

  document.querySelectorAll('form[action^="/queries/"]').forEach(function (form) {
    form.addEventListener("submit", async function (event) {
      var action = form.getAttribute("action").split("/").pop();
      if (action === "drop") { return; }   // the typed-confirmation modal owns this one
      event.preventDefault();
      try {
        await api.call("/queries/" + encodeURIComponent(view) + "/" + action, {method: "POST"});
        window.location.reload();
      } catch (err) {
        /* Retry is offered when it can help (the engine did not answer), and re-submits. */
        var host = document.getElementById("controls-error");
        host.innerHTML = States.error(err, true);
        var retry = host.querySelector("[data-state-action=retry]");
        if (retry) { retry.addEventListener("click", function () { form.requestSubmit(); }); }
      }
    });
  });

  /* Drop's typed-name confirmation (§23.16): a plain OK/Cancel is exactly the kind of
     dialog a hurried click clears without reading. This only runs once JS has proven it
     can, so the plain form above -- which needs no script at all -- stays the one that
     works when this one cannot; see the template comment for why both exist. */
  var dropPlain = document.getElementById("dropPlain");
  var dropTrigger = document.getElementById("dropModalTrigger");
  var dropInput = document.getElementById("dropConfirmInput");
  var dropSubmit = document.getElementById("dropConfirmSubmit");
  if (dropPlain && dropTrigger && dropInput && dropSubmit) {
    dropPlain.closest("form").classList.add("d-none");
    dropTrigger.classList.remove("d-none");
    var expected = dropInput.getAttribute("data-expected");
    dropInput.addEventListener("input", function () {
      dropSubmit.disabled = dropInput.value !== expected;
    });
    var modalEl = document.getElementById("dropConfirmModal");
    if (modalEl) {
      modalEl.addEventListener("hidden.bs.modal", function () {
        // A dialog reopened later must ask again, not remember a name typed last time.
        dropInput.value = "";
        dropSubmit.disabled = true;
      });
      modalEl.addEventListener("shown.bs.modal", function () { dropInput.focus(); });
    }
  }
}());
