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
 *   #highway-flow  Continuous SQL as a highway: orders and payments in two lanes, joined on user_id at a
 *                  junction, held at a one-minute window's toll gate, then delivered into a view that fans
 *                  out to a subscriber, a BI chart and a sink; once a loop a late pair corrects a total.
 *
 * Every colour is a design token used as var(--…) on the drawn shapes, so a figure follows the theme
 * the moment it changes, and every word comes from the string catalog, through the hero's data-flow
 * attribute. Every number is an illustration from a seeded generator, the same on every load.
 *
 * `prefers-reduced-motion` gets one still frame per figure that still says the sentence -- the question
 * typed, the stone in the river, an answer with a −1 and a +1 beside it, a sealed window reopened -- and
 * no loop. That is the frame the visual baselines photograph. The loop stops whenever the page is hidden
 * or no figure is in view, and the Pause button under the highway stops it for good (drishti's landing
 * has the same control, its Replay).
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

  /* ------------------------------------------------------------------ the highway: join, window, deliver
     Two lanes, orders and payments, carry packets keyed by user (the same key, the same tint: the
     data series). At the junction an order and a payment with the same key fuse into one joined
     packet, with a brief glow, and it drives on to the toll gate of a one-minute tumbling window,
     where it waits. When the window closes the gate lifts, the window's pairs drive into the view,
     and its rows change (each flashes) -- and every change fans out to a subscriber, a BI chart and
     a Kafka sink. Once a loop a pair arrives after its window closed: late, it goes straight
     through, and the view corrects that user's total as a −1 of the old value and a +1 of the new.
     Drawn as a pure function of the time in the loop, so the still frame is simply one moment. */
  function highwayFigure(svg) {
    svg.innerHTML = "";
    var LOOP = 17, SPEED = 110;
    var LANES = [70, 150], JX = 260, JY = 110, GATE = 440, CARD = 470;
    var KEYS = L.users.slice(0, 4);
    var amounts = [0, 0, 0, 0];
    // Each pair: key, amount, when its order and its payment set off, and the window it belongs to.
    var PAIRS = [
      { k: 0, a: 120, o: -1.5, p: -1.0, w: 0 }, { k: 1, a: 80, o: -0.6, p: 0.3, w: 0 },
      { k: 2, a: 45, o: 0.9, p: 1.2, w: 0 }, { k: 0, a: 60, o: 2.0, p: 2.6, w: 0 },
      { k: 3, a: 200, o: 4.2, p: 4.5, w: 1 }, { k: 1, a: 30, o: 5.4, p: 5.9, w: 1 },
      { k: 2, a: 75, o: 6.6, p: 6.8, w: 1 }, { k: 0, a: 25, o: 8.6, p: 9.0, w: 0, late: true }
    ];
    var CLOSE = [7.0, 11.2], LABELS = ["09:00", "09:01"], FLY = 0.6;
    var LANE_LEN = 230, LEG = Math.sqrt(60 * 60 + 40 * 40);
    var arrive = (LANE_LEN + LEG) / SPEED;

    // The road: two lanes converging on the junction, and the output lane on to the view.
    function road(d) {
      el(svg, "path", { d: d, style: "fill:none;stroke:var(--rule);stroke-width:26;stroke-linejoin:round" });
      el(svg, "path", { d: d, style: "fill:none;stroke:var(--stripe);stroke-width:24;stroke-linejoin:round" });
    }
    for (var l = 0; l < LANES.length; l++) {
      road("M-14 " + LANES[l] + " H200 L" + JX + " " + JY);
      el(svg, "text", { x: 6, y: LANES[l] - 18, style: style("var(--muted)", 10) }, l ? L.lane_payments : L.lane_orders);
    }
    road("M" + JX + " " + JY + " H" + (CARD - 6));
    var dashes = [el(svg, "line", { x1: 0, y1: LANES[0], x2: 200, y2: LANES[0], style: "stroke:var(--edge);stroke-dasharray:8 10" }),
                  el(svg, "line", { x1: 0, y1: LANES[1], x2: 200, y2: LANES[1], style: "stroke:var(--edge);stroke-dasharray:8 10" })];
    var out = el(svg, "line", { x1: JX + 40, y1: JY, x2: CARD - 8, y2: JY, style: "stroke:var(--edge);stroke-dasharray:8 10" });
    el(svg, "rect", { x: JX - 40, y: JY - 34, width: 80, height: 68, rx: 12, style: "fill:var(--surface);stroke:var(--flow);stroke-width:1.5" });
    el(svg, "text", { x: JX, y: JY - 42, "text-anchor": "middle", style: style("var(--flow)", 10, "var(--code)", 700) }, L.join);
    el(svg, "text", { x: JX, y: JY + 9, "text-anchor": "middle", style: style("var(--flow)", 26, "var(--sans)", 700) }, "\u22c8");
    el(svg, "text", { x: GATE + 8, y: JY - 34, "text-anchor": "end", style: style("var(--ink)", 10, "var(--code)", 700) }, L.tumble);
    el(svg, "rect", { x: GATE - 3, y: JY - 26, width: 6, height: 52, rx: 2, style: "fill:var(--ink)" });
    var arm = el(svg, "line", { x1: GATE, y1: JY - 18, x2: GATE, y2: JY + 18, style: "stroke:var(--flow);stroke-width:4;stroke-linecap:round" });
    var gateNote = el(svg, "text", { x: GATE + 8, y: JY + 42, "text-anchor": "end", style: style("var(--flow)", 10), opacity: 0 }, "");
    var glow = el(svg, "circle", { cx: JX, cy: JY, r: 10, style: "fill:none;stroke:var(--flow);stroke-width:3", opacity: 0 });

    // The view: key → total, one row per user; a correction line under it.
    el(svg, "rect", { x: CARD, y: 22, width: 160, height: 182, rx: 8, style: "fill:var(--surface);stroke:var(--edge)" });
    el(svg, "text", { x: CARD + 12, y: 42, style: style("var(--muted)", 9.5) }, L.view);
    var rows = [];
    for (var r = 0; r < KEYS.length; r++) {
      var ry = 66 + r * 26;
      var flash = el(svg, "rect", { x: CARD + 4, y: ry - 16, width: 152, height: 22, rx: 4, style: "fill:var(--flow)", opacity: 0 });
      el(svg, "rect", { x: CARD + 12, y: ry - 10, width: 4, height: 12, rx: 1, style: "fill:var(--series-" + (r + 1) + ")" });
      el(svg, "text", { x: CARD + 22, y: ry, style: style("var(--ink)", 12) }, KEYS[r]);
      rows.push({ flash: flash, v: el(svg, "text", { x: CARD + 148, y: ry, "text-anchor": "end", style: style("var(--ink)", 12) }, "—") });
    }
    var fix = el(svg, "text", { x: CARD + 12, y: 190, style: style("var(--retract)", 10.5, "var(--code)", 700), opacity: 0 }, "");

    // Delivery: three destinations under the view, a pulse to each after every change.
    var DEST = [[CARD + 20, L.sdk], [CARD + 80, L.bi], [CARD + 140, L.sink]];
    var pulses = [];
    for (var d = 0; d < DEST.length; d++) {
      el(svg, "line", { x1: CARD + 80, y1: 204, x2: DEST[d][0], y2: 252, style: "stroke:var(--edge)" });
      el(svg, "circle", { cx: DEST[d][0], cy: 262, r: 11, style: "fill:var(--surface);stroke:var(--flow);stroke-width:1.5" });
      el(svg, "text", { x: DEST[d][0], y: 290, "text-anchor": "middle", style: style("var(--muted)", 9) }, DEST[d][1]);
      pulses.push(el(svg, "circle", { r: 3.5, style: "fill:var(--flow)", opacity: 0 }));
    }
    el(svg, "path", { d: "M" + (CARD + 14) + " 262 h12 M" + (CARD + 20) + " 256 v12", style: "stroke:var(--flow);stroke-width:2" });
    el(svg, "path", { d: "M" + (CARD + 74) + " 267 v-4 M" + (CARD + 80) + " 267 v-9 M" + (CARD + 86) + " 267 v-6", style: "stroke:var(--flow);stroke-width:2.5" });
    el(svg, "path", { d: "M" + (CARD + 134) + " 258 h12 M" + (CARD + 134) + " 262 h12 M" + (CARD + 134) + " 266 h12", style: "stroke:var(--flow);stroke-width:1.5" });
    el(svg, "text", { x: 6, y: 236, style: style("var(--ink)", 18, "var(--serif)", 400) }, L.ask);
    el(svg, "text", { x: 6, y: 262, style: style("var(--ink)", 18, "var(--serif)", 400) }, L.answer);

    function tile(w, k, joined) {
      var g = el(svg, "g", { opacity: 0 });
      el(g, "rect", { x: -w / 2, y: -9, width: w, height: 18, rx: 5,
        style: "fill:var(--surface);stroke:var(--series-" + (k + 1) + ");stroke-width:" + (joined ? 3 : 2) });
      el(g, "text", { x: 0, y: 4, "text-anchor": "middle", style: style("var(--ink)", 10, "var(--code)", 700) }, KEYS[k]);
      return g;
    }
    for (var i = 0; i < PAIRS.length; i++) {
      var q = PAIRS[i];
      q.order = tile(26, q.k, false); q.pay = tile(26, q.k, false); q.joined = tile(32, q.k, true);
      q.join = Math.max(q.o, q.p) + arrive;
      q.atGate = q.join + (GATE - JX) / SPEED;
    }
    // The queue at each window's gate, in the order the pairs reach it.
    [0, 1].forEach(function (w) {
      PAIRS.filter(function (q) { return q.w === w && !q.late; })
        .sort(function (a, b) { return a.atGate - b.atGate; })
        .forEach(function (q, n) { q.slot = GATE - 18 - n * 34; q.leave = CLOSE[w]; });
    });
    var late = PAIRS[PAIRS.length - 1];
    late.slot = GATE; late.leave = late.atGate;

    function put(g, x, y, on) {
      g.setAttribute("opacity", on ? 1 : 0);
      if (on) { g.setAttribute("transform", "translate(" + x.toFixed(1) + " " + y.toFixed(1) + ")"); }
    }
    function lanePos(start, lane, tau) {
      var dist = SPEED * (tau - start);
      if (dist < LANE_LEN) { return [-30 + dist, LANES[lane]]; }
      var f = Math.min(0.8, (dist - LANE_LEN) / LEG);   // a packet whose partner is not here yet waits at the mouth
      return [200 + (JX - 200) * f, LANES[lane] + (JY - LANES[lane]) * f];
    }
    // One pair at one moment of the loop: where its packets are, if anywhere.
    function pair(q, tau) {
      var landed = q.leave + FLY;
      if (tau < Math.min(q.o, q.p) || tau >= landed) { return false; }
      var a = tau >= q.o && tau < q.join ? lanePos(q.o, 0, tau) : null;
      var b = tau >= q.p && tau < q.join ? lanePos(q.p, 1, tau) : null;
      put(q.order, a ? a[0] : 0, a ? a[1] : 0, !!a);
      put(q.pay, b ? b[0] : 0, b ? b[1] : 0, !!b);
      if (tau >= q.join) {
        var x = Math.min(q.slot, JX + SPEED * (tau - q.join)), y = JY;
        if (tau >= q.leave) {
          var f = (tau - q.leave) / FLY;
          x = q.slot + (CARD + 30 - q.slot) * f; y = JY + (66 + q.k * 26 - 4 - JY) * f;
        }
        put(q.joined, x, y, true);
      } else { put(q.joined, 0, 0, false); }
      return true;
    }

    var last = -1;
    function draw(c) {
      var p;
      for (p = 0; p < PAIRS.length; p++) {
        var q = PAIRS[p];
        // This loop's instance, or the next loop's arriving early on the lanes.
        if (!pair(q, c) && !pair(q, c - LOOP)) { put(q.order, 0, 0, false); put(q.pay, 0, 0, false); put(q.joined, 0, 0, false); }
      }
      // The glow of the most recent fusion.
      var since = 9;
      for (p = 0; p < PAIRS.length; p++) {
        [c, c - LOOP].forEach(function (tau) { var s = tau - PAIRS[p].join; if (s >= 0 && s < since) { since = s; } });
      }
      glow.setAttribute("opacity", since < 0.5 ? (0.8 * (1 - since / 0.5)).toFixed(2) : 0);
      glow.setAttribute("r", (10 + 50 * Math.min(since, 0.5)).toFixed(1));
      // The gate: lifted for a moment as each window closes, and for the late pair passing through.
      var lifted = -1, note = "";
      for (var w = 0; w < CLOSE.length; w++) {
        if (c >= CLOSE[w] && c < CLOSE[w] + 1.6) { note = LABELS[w] + " " + L.closed; }
        if (c >= CLOSE[w] && c < CLOSE[w] + FLY) { lifted = 1; }
      }
      if (c >= late.atGate - 0.15 && c < late.atGate + FLY) { lifted = 1; note = L.late; }
      arm.setAttribute("transform", lifted > 0 ? "rotate(-80 " + GATE + " " + (JY - 18) + ")" : "");
      gateNote.textContent = note; gateNote.setAttribute("opacity", note ? 1 : 0);
      // The view: totals from every pair that has landed in this loop.
      var totals = [null, null, null, null], changed = -9, correction = null, before = 0;
      for (p = 0; p < PAIRS.length; p++) {
        var at = PAIRS[p].leave + FLY;
        if (c >= at) {
          var k = PAIRS[p].k;
          if (PAIRS[p].late) { before = totals[k] || 0; correction = at; }
          totals[k] = (totals[k] || 0) + PAIRS[p].a;
          if (at > changed) { changed = at; }
        }
      }
      for (var r2 = 0; r2 < rows.length; r2++) {
        rows[r2].v.textContent = totals[r2] === null ? "—" : fmt(totals[r2]);
        var lastFor = -9;
        for (p = 0; p < PAIRS.length; p++) { if (PAIRS[p].k === r2 && c >= PAIRS[p].leave + FLY) { lastFor = Math.max(lastFor, PAIRS[p].leave + FLY); } }
        rows[r2].flash.setAttribute("opacity", c - lastFor < 1 ? (0.22 * (1 - (c - lastFor))).toFixed(2) : 0);
      }
      if (correction !== null) {
        fix.textContent = L.late + " " + KEYS[late.k] + ": " + L.minus + " " + fmt(before) + "  " + L.plus + " " + fmt(before + late.a);
      }
      fix.setAttribute("opacity", correction !== null ? 1 : 0);
      // Every change goes on to the subscriber, the chart and the sink.
      var age = c - changed;
      for (var d2 = 0; d2 < pulses.length; d2++) {
        var on = age >= 0 && age < 0.8, f2 = Math.min(1, age / 0.8);
        pulses[d2].setAttribute("opacity", on ? 1 : 0);
        if (on) { pulses[d2].setAttribute("cx", (CARD + 80 + (DEST[d2][0] - CARD - 80) * f2).toFixed(1)); pulses[d2].setAttribute("cy", (204 + 48 * f2).toFixed(1)); }
      }
      // The road moves under the traffic.
      var offset = (-(c * SPEED) % 18).toFixed(1);
      dashes[0].setAttribute("stroke-dashoffset", offset); dashes[1].setAttribute("stroke-dashoffset", offset);
      out.setAttribute("stroke-dashoffset", offset);
      last = c;
    }
    var t = 0;
    return {
      step: function (dt) { t += dt; draw(t % LOOP); },
      // The still: the late pair's correction in the view, the next loop's first packets on the lanes.
      still: function () { t = 16.2; draw(t); },
      at: function () { return last; }
    };
  }

  /* ------------------------------------------------------------------ one loop for all three */
  var figures = [heroFigure(hero)];
  var windows = document.getElementById("windows-flow");
  if (windows) { figures.push(windowsFigure(windows)); }
  var highway = document.getElementById("highway-flow");
  if (highway) { figures.push(highwayFigure(highway)); }

  var frame = null, frames = 0, stills = 0, last = 0, inView = true, paused = false;

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
    if (frame !== null || paused || reduce.matches || document.hidden || !inView) { return; }
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
    [hero, windows, highway].forEach(function (s) { if (s) { observer.observe(s); } });
  }

  /* Pause and play (WCAG 2.2.2: anything that moves for more than five seconds can be stopped). */
  var toggle = document.getElementById("figures-toggle");
  function label() {
    if (!toggle) { return; }
    toggle.hidden = reduce.matches;
    toggle.setAttribute("aria-pressed", paused ? "true" : "false");
    toggle.setAttribute("aria-label", paused ? L.play : L.pause);
    toggle.querySelector("i").className = "bi " + (paused ? "bi-play-fill" : "bi-pause-fill");
    toggle.querySelector("span").textContent = paused ? L.play_short : L.pause_short;
  }
  if (toggle) {
    toggle.addEventListener("click", function () {
      paused = !paused;
      if (paused) { stop(); } else { start(); }
      label();
    });
    reduce.addEventListener("change", label);
    label();
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
