/*
 * Pravaha console — the landing page's three figures: Ask once. Answer always.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * Three <svg> figures and plain script: no library, nothing fetched.
 *
 *   #hero-flow     The hero. A continuous query types itself once, and a stone carrying it drops into a
 *                  river of rows ("Ask once."). Where the river pools, the answer card never stops
 *                  changing ("Answer always."): totals move as rows pass, and a late row corrects one --
 *                  its old value leaves as a −1 and the new one arrives as a +1.
 *   #windows-flow  One-minute windows fill; a watermark sweeps across and seals each one; a late event
 *                  reopens a sealed window, as −1 old total and +1 new.
 *   #diyas-flow    Events as lamps carried downstream; the board on the bank updates as each passes.
 *
 * Every colour is a design token used as var(--…) on the drawn shapes, so a figure follows the theme
 * the moment it changes, and every word comes from the string catalog, through the hero's data-flow
 * attribute. Every number is an illustration from a seeded generator, the same on every load.
 *
 * `prefers-reduced-motion` gets one still frame per figure that still says the sentence -- the question
 * typed, the stone in the river, an answer with a −1 and a +1 beside it, a sealed window reopened -- and
 * no loop. That is the frame the visual baselines photograph. The loop stops whenever the page is hidden
 * or no figure is in view.
 *
 * The figures are aria-hidden; the captions and "How it works" say everything they say, in words.
 */
(function () {
  "use strict";

  var NS = "http://www.w3.org/2000/svg";
  var hero = document.getElementById("hero-flow");
  if (!hero) { return; }
  var L = JSON.parse(hero.getAttribute("data-flow"));
  // The catalog holds lists as comma-separated strings.
  L.users = String(L.users).split(",");
  L.cities = String(L.cities).split(",");
  var reduce = window.matchMedia("(prefers-reduced-motion: reduce)");

  /* A seeded generator: the same illustration on every load, which the baselines rely on. */
  var seed = 7;
  function rand() { seed = (seed * 16807) % 2147483647; return (seed - 1) / 2147483646; }

  function el(parent, tag, attrs, text) {
    var e = document.createElementNS(NS, tag);
    for (var k in attrs) { if (Object.prototype.hasOwnProperty.call(attrs, k)) { e.setAttribute(k, attrs[k]); } }
    if (text !== undefined) { e.textContent = text; }
    parent.appendChild(e);
    return e;
  }
  function style(fill, size, family, weight) {
    return "fill:" + fill + ";font:" + (weight || 500) + " " + size + "px " + (family || "var(--code)");
  }
  function fmt(n) { return String(Math.round(n)).replace(/\B(?=(\d{3})+(?!\d))/g, ","); }
  function wave(x, t) { return Math.sin((x + t * 30) / 48) * 6; }

  /* ------------------------------------------------------------------ the hero: river and live answer */
  function heroFigure(svg) {
    svg.innerHTML = "";
    var riverY = 262;
    el(svg, "path", { d: "M0 " + riverY + " C170 212 330 264 470 " + riverY + " S560 232 640 244 L640 330 L0 330Z",
      style: "fill:var(--flow);opacity:.08" });
    el(svg, "path", { d: "M0 " + (riverY - 6) + " C170 206 330 258 470 " + (riverY - 6) + " S560 226 640 238",
      style: "fill:none;stroke:var(--flow);stroke-width:1.2;opacity:.35" });

    /* The question, typed once. */
    el(svg, "rect", { x: 16, y: 22, width: 386, height: 52, rx: 8, style: "fill:var(--surface);stroke:var(--edge)" });
    var typed = el(svg, "text", { x: 28, y: 53, style: style("var(--ink)", 10.5) }, "");
    var caret = el(svg, "rect", { width: 6, height: 13, y: 43, style: "fill:var(--flow)" });
    var ask = el(svg, "text", { x: 22, y: 104, style: style("var(--ink)", 20, "var(--serif)", 400) }, L.ask);

    /* The stone that carries it into the river. */
    var stone = el(svg, "g", {});
    el(stone, "rect", { x: -64, y: -13, width: 128, height: 26, rx: 13, style: "fill:var(--flow)" });
    el(stone, "text", { x: 0, y: 4, "text-anchor": "middle", style: style("var(--on-flow)", 10) }, L.stone);
    var ripple = el(svg, "ellipse", { cx: 220, cy: riverY, rx: 0, ry: 0, style: "fill:none;stroke:var(--flow);stroke-width:1.4" });

    /* The answer card where the river pools. */
    var cx = 418, cy = 16;
    el(svg, "rect", { x: cx, y: cy, width: 206, height: 178, rx: 8, style: "fill:var(--surface);stroke:var(--edge)" });
    el(svg, "text", { x: cx + 14, y: cy + 22, style: style("var(--muted)", 9.5) }, L.answer_title);
    var count = el(svg, "text", { x: cx + 14, y: cy + 166, style: style("var(--muted)", 9.5) }, "");
    var totals = [], cells = [];
    for (var i = 0; i < L.users.length; i++) {
      totals.push(200 + Math.floor(rand() * 9000));
      el(svg, "text", { x: cx + 14, y: cy + 54 + i * 30, style: style("var(--ink)", 13) }, L.users[i]);
      cells.push(el(svg, "text", { x: cx + 140, y: cy + 54 + i * 30, "text-anchor": "end", style: style("var(--ink)", 13) }, ""));
    }
    var chip = el(svg, "g", { opacity: 0 });
    var chipMinus = el(chip, "text", { x: 0, y: 0, style: style("var(--retract)", 10) }, "");
    var chipPlus = el(chip, "text", { x: 0, y: 13, style: style("var(--flow)", 10) }, "");
    var answer = el(svg, "text", { x: cx + 2, y: cy + 208, style: style("var(--ink)", 20, "var(--serif)", 400) }, L.answer);

    var rows = [];
    for (var r = 0; r < 20; r++) {
      rows.push({ x: rand() * 400, y: riverY + 8 + rand() * 40, v: 38 + rand() * 30,
        c: el(svg, "circle", { r: 3.2, style: "fill:var(--flow);opacity:.8" }) });
    }
    var t = 0, events = 0, lastFix = -9, fixAt = 4;
    var dropAt = L.question.length / 34;

    function show() { for (var j = 0; j < cells.length; j++) { cells[j].textContent = fmt(totals[j]); } }
    function correct(at) {
      var j = Math.floor(rand() * totals.length);
      var old = totals[j];
      totals[j] = Math.max(0, old - 40 - Math.floor(rand() * 200));
      chipMinus.textContent = L.minus + " " + fmt(old);
      chipPlus.textContent = L.plus + " " + fmt(totals[j]);
      chip.setAttribute("transform", "translate(" + (cx + 146) + " " + (cy + 49 + j * 30) + ")");
      lastFix = at;
    }
    function draw() {
      var n = Math.min(L.question.length, Math.floor(t * 34));
      typed.textContent = L.question.slice(0, n);
      caret.setAttribute("x", 28 + n * 6.3);
      caret.setAttribute("opacity", n < L.question.length && Math.floor(t * 3) % 2 === 0 ? 1 : 0);
      var fall = Math.max(0, Math.min(1, (t - dropAt) / 0.9));
      stone.setAttribute("transform", "translate(220 " + (74 + fall * (riverY - 74)) + ")");
      stone.setAttribute("opacity", t < dropAt ? 0 : 1);
      ask.setAttribute("opacity", Math.min(1, t / 1.2));
      answer.setAttribute("opacity", Math.max(0, Math.min(1, (t - dropAt - 1) / 1)));
      var since = t - (dropAt + 0.9);
      var rs = since > 0 && since < 1.4 ? since : 0;
      ripple.setAttribute("rx", rs * 80);
      ripple.setAttribute("ry", rs * 16);
      ripple.setAttribute("opacity", rs ? 0.8 - rs / 1.8 : 0);
      for (var k = 0; k < rows.length; k++) {
        rows[k].c.setAttribute("cx", rows[k].x);
        rows[k].c.setAttribute("cy", rows[k].y + wave(rows[k].x, t));
      }
      chip.setAttribute("opacity", t - lastFix < 1.8 ? 1 : 0);
      count.textContent = events + " " + L.events;
      show();
    }
    return {
      step: function (dt) {
        t += dt;
        var flowing = t > dropAt + 0.9;
        for (var k = 0; k < rows.length; k++) {
          rows[k].x += rows[k].v * dt;
          if (rows[k].x > 420) {
            rows[k].x = -8;
            if (flowing) { events++; var j = Math.floor(rand() * totals.length); totals[j] += 5 + Math.floor(rand() * 60); }
          }
        }
        if (flowing && t > fixAt) { fixAt = t + 4 + rand() * 2; correct(t); }
        draw();
      },
      still: function () {
        t = dropAt + 3; events = 128; correct(t); draw();
      }
    };
  }

  /* ------------------------------------------------------------------ windows filling */
  function windowsFigure(svg) {
    svg.innerHTML = "";
    var W = [], labels = ["09:00", "09:01", "09:02"];
    for (var i = 0; i < 3; i++) {
      var x = 70 + i * 180;
      var w = { x: x, n: 0, sealed: false, reopened: false };
      w.box = el(svg, "rect", { x: x, y: 70, width: 150, height: 150, rx: 6, style: "fill:var(--surface);stroke:var(--edge)" });
      w.sum = el(svg, "text", { x: x + 75, y: 104, "text-anchor": "middle", style: style("var(--ink)", 20) }, "0");
      w.state = el(svg, "text", { x: x + 75, y: 206, "text-anchor": "middle", style: style("var(--muted)", 10) }, L.open);
      el(svg, "text", { x: x + 75, y: 240, "text-anchor": "middle", style: style("var(--muted)", 10) }, labels[i] + " " + L.window);
      W.push(w);
    }
    var mark = el(svg, "line", { y1: 54, y2: 226, style: "stroke:var(--retract);stroke-width:2;stroke-dasharray:4 4" });
    var markLabel = el(svg, "text", { y: 48, style: style("var(--retract)", 10) }, L.watermark);
    var chip = el(svg, "text", { y: 140, "text-anchor": "middle", style: style("var(--retract)", 10), opacity: 0 }, "");
    var drops = [], t = 0, spawn = 0, flash = -9;

    function reset() {
      for (var j = 0; j < W.length; j++) {
        W[j].n = 0; W[j].sealed = false; W[j].reopened = false;
        W[j].sum.textContent = "0"; W[j].state.textContent = L.open;
        W[j].box.setAttribute("style", "fill:var(--surface);stroke:var(--edge)");
      }
    }
    function land(w, late) {
      var add = 5 + Math.floor(rand() * 45);
      if (late && w.sealed) {
        chip.textContent = L.minus + " " + w.n + "  " + L.plus + " " + (w.n + add);
        chip.setAttribute("x", w.x + 75);
        w.state.textContent = L.reopened;
        flash = t;
      }
      w.n += add;
      w.sum.textContent = w.n;
    }
    function markAt(x) {
      mark.setAttribute("x1", x); mark.setAttribute("x2", x); markLabel.setAttribute("x", x + 4);
      for (var j = 0; j < W.length; j++) {
        if (!W[j].sealed && x > W[j].x + 150) {
          W[j].sealed = true; W[j].state.textContent = L.sealed;
          W[j].box.setAttribute("style", "fill:var(--surface);stroke:var(--flow)");
        }
      }
    }
    return {
      step: function (dt) {
        t += dt;
        var cycle = t % 12;
        if (cycle < dt * 1.5) { reset(); }
        var x = 50 + cycle * 48;
        markAt(x);
        spawn -= dt;
        if (spawn < 0) {
          spawn = 0.35;
          var late = cycle > 8 && rand() < 0.25;
          var target = W[2];
          for (var j = 0; j < W.length; j++) { if (!W[j].sealed && W[j].x + 150 > x) { target = W[j]; break; } }
          if (late) { target = W[0]; }
          drops.push({ w: target, late: late, x: target.x + 16 + rand() * 118, y: 20,
            c: el(svg, "circle", { r: 4, style: "fill:" + (late ? "var(--retract)" : "var(--flow)") }) });
        }
        for (var k = drops.length - 1; k >= 0; k--) {
          var d = drops[k];
          d.y += 150 * dt;
          d.c.setAttribute("cx", d.x); d.c.setAttribute("cy", d.y);
          if (d.y > 196) { svg.removeChild(d.c); drops.splice(k, 1); land(d.w, d.late); }
        }
        chip.setAttribute("opacity", t - flash < 1.8 ? 1 : 0);
      },
      still: function () {
        reset();
        for (var j = 0; j < 14; j++) { land(W[j % 3], false); }
        markAt(430);
        t = 1; land(W[0], true); chip.setAttribute("opacity", 1);
      }
    };
  }

  /* ------------------------------------------------------------------ diyas on the river */
  function diyasFigure(svg) {
    svg.innerHTML = "";
    el(svg, "rect", { x: 0, y: 0, width: 640, height: 220, style: "fill:var(--surface)" });
    el(svg, "rect", { x: 0, y: 120, width: 640, height: 100, style: "fill:var(--flow);opacity:.1" });
    var board = { x: 470 };
    el(svg, "rect", { x: 470, y: 22, width: 150, height: 120, rx: 8, style: "fill:var(--surface);stroke:var(--edge)" });
    el(svg, "text", { x: 484, y: 44, style: style("var(--muted)", 9.5) }, L.diyas_board);
    var rows = [];
    for (var i = 0; i < L.cities.length; i++) {
      el(svg, "text", { x: 484, y: 72 + i * 24, style: style("var(--ink)", 12) }, L.cities[i]);
      rows.push({ n: 0, v: el(svg, "text", { x: 606, y: 72 + i * 24, "text-anchor": "end", style: style("var(--ink)", 12) }, "0") });
    }
    el(svg, "text", { x: 22, y: 48, style: style("var(--ink)", 18, "var(--serif)", 400) }, L.ask);
    el(svg, "text", { x: 22, y: 72, style: style("var(--ink)", 18, "var(--serif)", 400) }, L.answer);
    var lamps = [];
    for (var j = 0; j < 7; j++) {
      var g = el(svg, "g", {});
      el(g, "ellipse", { cx: 0, cy: 6, rx: 15, ry: 3, style: "fill:var(--flow-l);opacity:.35" });
      el(g, "path", { d: "M-11 0 Q0 10 11 0 Z", style: "fill:var(--flow)" });
      var flame = el(g, "path", { d: "M0 -2 Q-4 -9 0 -16 Q4 -9 0 -2Z", style: "fill:var(--flow-l)" });
      lamps.push({ g: g, flame: flame, x: -40 - j * 92, y: 150 + (j % 3) * 22, city: j % 3 });
    }
    var t = 0;
    function place() {
      for (var k = 0; k < lamps.length; k++) {
        var l = lamps[k];
        l.g.setAttribute("transform", "translate(" + l.x + " " + (l.y + Math.sin(t * 1.5 + l.x / 40) * 2.5) + ")");
        l.flame.setAttribute("transform", "scale(1 " + (1 + Math.sin(t * 9 + l.y) * 0.12) + ")");
      }
    }
    return {
      step: function (dt) {
        t += dt;
        for (var k = 0; k < lamps.length; k++) {
          var l = lamps[k], before = l.x;
          l.x += 36 * dt;
          if (before < board.x && l.x >= board.x) { rows[l.city].n++; rows[l.city].v.textContent = rows[l.city].n; }
          if (l.x > 680) { l.x = -40; }
        }
        place();
      },
      still: function () {
        for (var k = 0; k < lamps.length; k++) { lamps[k].x = 40 + k * 60; }
        for (var r = 0; r < rows.length; r++) { rows[r].n = 12 + r * 7; rows[r].v.textContent = rows[r].n; }
        t = 1; place();
      }
    };
  }

  /* ------------------------------------------------------------------ one loop for all three */
  var figures = [heroFigure(hero)];
  var windows = document.getElementById("windows-flow");
  if (windows) { figures.push(windowsFigure(windows)); }
  var diyas = document.getElementById("diyas-flow");
  if (diyas) { figures.push(diyasFigure(diyas)); }

  var frame = null, frames = 0, stills = 0, last = 0, inView = true;

  function drawStill() {
    for (var i = 0; i < figures.length; i++) { figures[i].still(); }
    stills++;
  }
  function tick(now) {
    var dt = Math.min(0.05, (now - last) / 1000);
    last = now;
    for (var i = 0; i < figures.length; i++) { figures[i].step(dt); }
    frames++;
    frame = window.requestAnimationFrame(tick);
  }
  function start() {
    if (frame !== null || reduce.matches || document.hidden || !inView) { return; }
    last = performance.now();
    frame = window.requestAnimationFrame(tick);
  }
  function stop() {
    if (frame !== null) { window.cancelAnimationFrame(frame); frame = null; }
  }

  document.addEventListener("visibilitychange", function () { if (document.hidden) { stop(); } else { start(); } });
  reduce.addEventListener("change", function () { if (reduce.matches) { stop(); drawStill(); } else { start(); } });
  if ("IntersectionObserver" in window) {
    var visible = new Set();
    var observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) { if (e.isIntersecting) { visible.add(e.target); } else { visible.delete(e.target); } });
      inView = visible.size > 0;
      if (inView) { start(); } else { stop(); }
    });
    [hero, windows, diyas].forEach(function (s) { if (s) { observer.observe(s); } });
  }

  /* For the browser tests: what was drawn, and whether a loop is running. */
  window.PravahaLanding = {
    stills: function () { return stills; },
    frames: function () { return frames; },
    running: function () { return frame !== null; }
  };

  if (reduce.matches) {
    drawStill();
  } else {
    // From the beginning, so the question types itself; the first frame is drawn now, not a frame later.
    for (var f = 0; f < figures.length; f++) { figures[f].step(0); }
    start();
  }
})();
