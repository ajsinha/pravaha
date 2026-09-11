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
  var tail = new window.PravahaLiveTail(view, target, {
    onState: function (state, detail) {
      tailState.setAttribute("data-state",
        state === "live" ? "fresh" : state === "lagging" ? "refreshing" : "stale");
      tailState.innerHTML = '<span class="dot"></span><span>' +
        api.escapeHtml(detail || state) + "</span>";
      /* Dimmed when the stream is gone, so nobody reads a frozen tail as a quiet
         one. On a stream those look identical and only one is a problem. */
      target.classList.toggle("stale", state === "stale");
    }
  });
  tail.start();
  window.addEventListener("beforeunload", function () { tail.stop(); });

  document.querySelectorAll('form[action^="/queries/"]').forEach(function (form) {
    form.addEventListener("submit", async function (event) {
      var action = form.getAttribute("action").split("/").pop();
      if (action === "drop") { return; }   // let the confirm and the POST happen
      event.preventDefault();
      try {
        await api.call("/queries/" + encodeURIComponent(view) + "/" + action, {method: "POST"});
        window.location.reload();
      } catch (err) {
        document.querySelector("main").insertAdjacentHTML("afterbegin", States.error(err, null));
      }
    });
  });
}());
