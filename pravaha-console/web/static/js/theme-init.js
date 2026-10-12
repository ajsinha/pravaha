/*
 * Pravaha console — applies the saved theme and density before the first paint.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * Ask once. Answer always.
 *
 * drishti's theme-init.js, ported: a plain synchronous script in <head>, loaded from this server
 * (no inline script, so the strict CSP needs no exception), that puts the stored choice on <html>
 * before the stylesheets paint, so somebody who chose a dark theme never sees a crimson flash.
 *
 * The seven themes are drishti's (theme.js holds the list for the menu). The server renders
 * data-theme="crimson", Pravaha's default; nothing stored keeps it.
 *
 * MIGRATION. The console's earlier four themes were stored under "pravaha.theme" as light, dark,
 * blue and green -- and its "light" was the crimson look, where drishti's "light" id is Parchment.
 * So the choice now lives under a new key, "pravaha.theme.v2", and an old value is read once,
 * translated (light → crimson, dark → crimson-dark, blue → blue, green → green), written under the
 * new key, and the old key removed. An old "light" is therefore never read as Parchment.
 */
(function () {
  "use strict";
  var KEY = "pravaha.theme.v2";
  var OLD_KEY = "pravaha.theme";
  var THEMES = ["terminal", "light", "blue", "wallstreet", "green", "crimson", "crimson-dark"];
  var MIGRATE = {"light": "crimson", "dark": "crimson-dark", "blue": "blue", "green": "green"};
  var DARK = ["terminal", "wallstreet", "crimson-dark"];
  var root = document.documentElement;
  try {
    var theme = window.localStorage.getItem(KEY);
    if (THEMES.indexOf(theme) < 0) {
      theme = null;
      var old = window.localStorage.getItem(OLD_KEY);
      if (old !== null) {
        theme = Object.prototype.hasOwnProperty.call(MIGRATE, old) ? MIGRATE[old] : null;
        if (theme) window.localStorage.setItem(KEY, theme);
        window.localStorage.removeItem(OLD_KEY);
      }
    }
    if (theme) root.setAttribute("data-theme", theme);
    var density = window.localStorage.getItem("pravaha.density");
    if (density && density !== "comfortable") root.setAttribute("data-density", density);
  } catch (e) { /* storage blocked: keep the server's default */ }
  root.setAttribute("data-bs-theme", DARK.indexOf(root.getAttribute("data-theme")) >= 0 ? "dark" : "light");
}());
