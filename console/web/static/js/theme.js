/*
 * Pravaha console — light, dark, terminal, or whatever the machine says.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * Four states rather than two. "System" is the default and is a POSITION, not
 * the absence of a choice: somebody whose machine switches at dusk should not
 * have to correct this console twice a day, and a two-way toggle cannot express
 * that.
 *
 * The stylesheet does the work. `data-theme` on <html> selects a block of custom
 * properties and every colour on every screen resolves through one of them, so
 * this file sets one attribute and stores one string.
 *
 * Applied in <head>, before the first paint — see the inline snippet in
 * base.html. Waiting for DOMContentLoaded would show a white page to somebody
 * who chose dark, which is the flash this whole indirection exists to avoid.
 *
 * DENSITY works the same way and is separate from the browser's zoom, because
 * zoom scales the LAYOUT — a table at 150% is a table nobody can see a row of —
 * where this scales only the row height, so an operator watching forty queries
 * gets forty rows without everything else shrinking.
 */
(function () {
  "use strict";

  var KEY = "pravaha.theme";
  /* The single source of truth for which themes exist. `system` is not a
     palette — it is the absence of a choice, so it removes the attribute and
     lets the media query decide between light and dark.

     `terminal` is a named palette and therefore always explicit: the machine
     has no opinion about whether you want an amber screen. */
  var CHOICES = {light: 1, dark: 1, terminal: 1, system: 1};
  var ORDER = ["system", "light", "dark", "terminal"];
  var DENSITY_KEY = "pravaha.density";
  var DENSITIES = ["comfortable", "compact"];

  function stored() {
    try {
      var value = window.localStorage.getItem(KEY);
      return CHOICES[value] ? value : "system";
    } catch (e) { return "system"; }
  }

  function apply(choice) {
    if (choice === "system") {
      document.documentElement.removeAttribute("data-theme");
    } else {
      document.documentElement.setAttribute("data-theme", choice);
    }
    try { window.localStorage.setItem(KEY, choice); } catch (e) {}
    announce(choice === "system" ? "theme follows the system" : choice + " theme");
  }

  function applyDensity(choice) {
    if (choice === "comfortable") {
      document.documentElement.removeAttribute("data-density");
    } else {
      document.documentElement.setAttribute("data-density", choice);
    }
    try { window.localStorage.setItem(DENSITY_KEY, choice); } catch (e) {}
    announce(choice + " density");
  }

  /* Spoken to a screen reader without stealing focus. A control that changes
     the whole page and says nothing leaves somebody who cannot see it guessing
     whether the button did anything. */
  function announce(message) {
    var region = document.getElementById("live-region");
    if (!region) {
      region = document.createElement("div");
      region.id = "live-region";
      region.className = "visually-hidden";
      region.setAttribute("aria-live", "polite");
      document.body.appendChild(region);
    }
    region.textContent = message;
  }

  function cycle(list, current) {
    return list[(list.indexOf(current) + 1) % list.length];
  }

  document.addEventListener("DOMContentLoaded", function () {
    var theme = document.getElementById("theme-toggle");
    if (theme) {
      theme.addEventListener("click", function () { apply(cycle(ORDER, stored())); });
    }
    var density = document.getElementById("density-toggle");
    if (density) {
      density.addEventListener("click", function () {
        var current = document.documentElement.getAttribute("data-density") || "comfortable";
        applyDensity(cycle(DENSITIES, current));
      });
    }

    document.addEventListener("keydown", function (event) {
      if (/^(INPUT|TEXTAREA|SELECT)$/.test(event.target.tagName)) return;
      if (event.metaKey || event.ctrlKey || event.altKey) return;
      if (event.key === "t") { apply(cycle(ORDER, stored())); }
      else if (event.key === "d") {
        applyDensity(cycle(DENSITIES,
          document.documentElement.getAttribute("data-density") || "comfortable"));
      } else if (event.key === "/") {
        var search = document.querySelector('input[type="search"]');
        if (search) { event.preventDefault(); search.focus(); }
      } else if (event.key === "?") { window.location.href = "/help"; }
    });
  });

  window.PravahaTheme = {apply: apply, stored: stored, announce: announce};
}());
