/* Pravaha console -- F1, and the copy button on every captioned example.
   Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
   Proprietary and confidential. See LICENSE at the repository root.

   Drishti's keys (drishti-console static/js/app.js): F1 opens the help for the screen you are on,
   /help/context/<screen> -- the screen is the body's data-screen, the same key "About this page"
   is drawn for -- which answers with the first help topic config/help.yaml's contextual: names for
   it. ? opens About this page (theme.js). A file, not an inline script: the console's CSP has none.

   And drishti's copy (static/js/help.js): a fenced example in any rendered document is a figure with
   a Copy button (core/content/renderer.py); this copies its code as text. */
(function () {
  "use strict";
  function t(key) {
    try {
      var messages = JSON.parse(document.getElementById("i18n-messages").textContent || "{}");
      return messages[key] || key;
    } catch (e) { return key; }
  }

  document.addEventListener("keydown", function (event) {
    if (event.key !== "F1" || event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) { return; }
    event.preventDefault();
    var screen = document.body.getAttribute("data-screen") || "help";
    window.location.href = "/help/context/" + encodeURIComponent(screen);
  });

  document.addEventListener("click", function (event) {
    var button = event.target.closest ? event.target.closest("[data-copy]") : null;
    if (!button) { return; }
    var figure = button.closest("figure");
    var code = figure ? figure.querySelector("code") : null;
    if (!code) { return; }
    var label = button.querySelector("[data-copy-label]") || button;
    var done = function (text) {
      label.textContent = text;
      window.setTimeout(function () { label.textContent = t("help.copy.copy"); }, 1500);
    };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(code.innerText).then(function () { done(t("help.copy.copied")); },
        function () { done(t("help.copy.select")); });
    } else { done(t("help.copy.select")); }
  });
}());
