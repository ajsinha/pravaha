/*
 * Pravaha console -- first-run onboarding (design 23.6, screen 24).
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * The only screen every user sees. Four steps, each the engine's real public API: pick or
 * declare a stream (GET/POST /api/v1/streams), choose a question from templates written
 * against that stream's own columns and edit it with validation as you type (/validate),
 * register it with keys chosen by name (the Flight register action), and watch the view
 * being maintained. Honest about the two things that stop a first query producing rows:
 * a stream with no source bound, and a stream with no declared event time.
 */
import { html, render, call, debounce, announce, errorView, store } from "pravaha/lib.js";
import { useEffect, useMemo, useRef, useState } from "preact/hooks";

const root = document.getElementById("start-app");
const IDENT = /^[A-Za-z_][A-Za-z0-9_]*$/;

function Steps({ step }) {
  const names = ["Pick a stream", "Choose a question", "Register it", "Watch it change"];
  return html`<ol class="steps" aria-label="Progress">${names.map((n, i) => html`<li
    class=${i < step ? "done" : ""} aria-current=${i === step ? "step" : undefined}>${n}</li>`)}</ol>`;
}

function StreamStep({ streams, setStreams, chosen, setChosen, next }) {
  const [declaring, setDeclaring] = useState(!streams.length);
  const [name, setName] = useState("");
  const [schema, setSchema] = useState("txn_id:INT64,user_id:STRING,amount:INT64,event_time:TIMESTAMP");
  const [eventTime, setEventTime] = useState("event_time");
  const [lateness, setLateness] = useState("PT10S");
  const [state, setState] = useState({ status: "idle" });
  /* Offered from the schema being typed: only TIMESTAMP columns can carry event time. */
  const timestampColumns = schema.split(",").map((p) => p.trim().split(":"))
    .filter((p) => p.length === 2 && /TIMESTAMP/i.test(p[1])).map((p) => p[0].trim());
  async function declare(event) {
    event.preventDefault();
    setState({ status: "loading" });
    try {
      const body = { name, schema };
      if (eventTime && timestampColumns.includes(eventTime)) {
        body.event_time = eventTime;
        if (lateness) body.out_of_orderness = lateness;
      }
      const stream = await call("/catalog/streams", { json: body });
      setStreams(streams.concat(stream)); setChosen(stream); setDeclaring(false);
      setState({ status: "idle" }); announce(`Stream ${stream.name} declared`);
    } catch (err) { setState({ status: "error", err }); }
  }
  return html`<section aria-labelledby="s1"><h2 id="s1">1 · Pick a stream</h2>
    <p class="text-muted small">A stream is a named, typed, unbounded sequence of rows. Pick the one to ask about.</p>
    ${streams.length ? html`<div class="row g-2 mb-3">${streams.map((s) => html`<div class="col-md-4">
      <button type="button" class="choice" aria-pressed=${chosen && chosen.name === s.name ? "true" : "false"}
        onClick=${() => setChosen(s)}>
        <div class="t mono">${s.name}</div>
        <div class="s">${(s.fields || []).map((f) => f.name).join(", ")}</div></button></div>`)}</div>` : null}
    ${declaring ? html`<form class="card mb-3" onSubmit=${declare}><div class="card-body">
      <div class="fw-semibold mb-2">Declare a new stream</div>
      <div class="row g-2">
        <div class="col-md-3"><label class="form-label small text-muted mb-1" for="ds-name">Name</label>
          <input id="ds-name" class="form-control form-control-sm" value=${name} onInput=${(e) => setName(e.target.value.trim())} placeholder="txn" /></div>
        <div class="col-md-9"><label class="form-label small text-muted mb-1" for="ds-schema">Schema — <code>name:TYPE</code>, comma-separated</label>
          <input id="ds-schema" class="form-control form-control-sm mono" value=${schema} onInput=${(e) => setSchema(e.target.value)} /></div>
      </div>
      <div class="row g-2 mt-1">
        <div class="col-md-4"><label class="form-label small text-muted mb-1" for="ds-event-time">Event time</label>
          <select id="ds-event-time" class="form-select form-select-sm" value=${timestampColumns.includes(eventTime) ? eventTime : ""}
            onChange=${(e) => setEventTime(e.target.value)}>
            <option value="">none — no window will ever close</option>
            ${timestampColumns.map((c) => html`<option value=${c}>${c}</option>`)}</select></div>
        <div class="col-md-4"><label class="form-label small text-muted mb-1" for="ds-lateness">Out-of-orderness</label>
          <input id="ds-lateness" class="form-control form-control-sm mono" value=${lateness}
            disabled=${!timestampColumns.includes(eventTime)} onInput=${(e) => setLateness(e.target.value.trim())} placeholder="PT10S" /></div>
      </div>
      <div class="form-text small mt-2">Event time is the column a window is measured on; out-of-orderness is how late a row
        may be (ISO-8601) before a window stops waiting for it. This puts the schema in the catalogue; it does not bind a
        source — rows only arrive once <code>pravaha.sources.${name || "<name>"}</code> is configured.</div>
      ${state.status === "error" ? html`<div class="mt-2">${errorView(state.err)}</div>` : null}
      <button type="submit" class="btn btn-sm btn-primary mt-2" disabled=${!IDENT.test(name) || !schema.trim() || state.status === "loading"}>
        ${state.status === "loading" ? "Declaring…" : "Declare stream"}</button>
    </div></form>` : html`<button type="button" class="btn btn-sm btn-link px-0 mb-3" onClick=${() => setDeclaring(true)}>
      Declare a new stream instead</button>`}
    <div><button type="button" class="btn btn-primary" disabled=${!chosen} onClick=${next}>
      Next: choose a question about ${chosen ? html`<span class="mono">${chosen.name}</span>` : "it"}</button></div>
  </section>`;
}

function QuestionStep({ chosen, sql, setSql, validation, back, next }) {
  const [templates, setTemplates] = useState([]);
  const [picked, setPicked] = useState(null);
  useEffect(() => {
    call("/catalog/templates?stream=" + encodeURIComponent(chosen.name))
      .then((t) => setTemplates(t.items)).catch(() => setTemplates([]));
  }, [chosen.name]);
  return html`<section aria-labelledby="s2"><h2 id="s2">2 · Choose a question</h2>
    <p class="text-muted small">Each is written against <span class="mono">${chosen.name}</span>'s own columns. Edit it freely —
      it is validated by the engine's planner as you type.</p>
    <div class="row g-2 mb-3">${templates.map((t) => html`<div class="col-md-4">
      <button type="button" class="choice" aria-pressed=${picked === t.id ? "true" : "false"}
        onClick=${() => { setPicked(t.id); setSql(t.sql); }}>
        <div class="t">${t.title}</div><div class="s">${t.summary}</div></button></div>`)}</div>
    <label class="form-label small text-muted mb-1" for="ob-sql">The query</label>
    <textarea id="ob-sql" class="form-control mono" rows="6" spellcheck="false" value=${sql}
      onInput=${(e) => setSql(e.target.value)} placeholder="Pick a question above, or write your own"></textarea>
    <div class="mt-2" aria-live="polite">
      ${validation.status === "valid" ? html`<span class="validity ok"><i class="bi bi-check-circle-fill"></i> Valid — output:
        <span class="mono fw-normal">${validation.output_fields.map((f) => f.name).join(", ")}</span></span>` : null}
      ${validation.status === "invalid" ? validation.diagnostics.map((d) => html`<div class="diag"><a class="mono fw-semibold"
        href=${d.help} target="_blank" rel="noopener">${d.code}</a> ${d.message}</div>`) : null}
      ${validation.status === "unavailable" ? html`<span class="validity idle">Validation unavailable: ${validation.error}</span>` : null}
      ${validation.status === "checking" ? html`<span class="text-muted small">checking…</span>` : null}
    </div>
    <div class="d-flex gap-2 mt-3"><button type="button" class="btn btn-outline-secondary" onClick=${back}>Back</button>
      <button type="button" class="btn btn-primary" disabled=${validation.status !== "valid"} onClick=${next}>Next: register it</button>
      <a class="btn btn-link" href=${"/workbench?sql=" + encodeURIComponent(sql)}>Open in the full workbench</a></div>
  </section>`;
}

function RegisterStep({ chosen, sql, validation, back, done }) {
  const fields = validation.output_fields || [];
  const suggested = useMemo(() => fields.filter((f) => /^(window_start|window_end)$/i.test(f.name)
    || /STRING|VARCHAR/i.test(f.type)).map((f) => f.name).slice(0, 3), [sql]);
  const [name, setName] = useState(`${chosen.name}_${Math.random().toString(36).slice(2, 6)}`);
  const [keys, setKeys] = useState(suggested.length ? suggested : fields.slice(0, 1).map((f) => f.name));
  const [state, setState] = useState({ status: "idle" });
  const toggle = (k) => setKeys((all) => (all.includes(k) ? all.filter((x) => x !== k) : all.concat(k)));
  async function submit(event) {
    event.preventDefault();
    setState({ status: "loading" });
    try {
      const answer = await call("/queries", { json: { name, sql, key_names: keys } });
      store.set("pravaha.onboarding.done", true);
      announce(`${answer.name} registered`);
      done(answer);
    } catch (err) { setState({ status: "error", err }); }
  }
  return html`<section aria-labelledby="s3"><h2 id="s3">3 · Register it</h2>
    <p class="text-muted small">Registering turns the question into a computation the engine keeps running, and its answer
      into a view clients read by name.</p>
    <form onSubmit=${submit} class="row g-3">
      <div class="col-md-5"><label class="form-label small text-muted mb-1" for="ob-name">View name</label>
        <input id="ob-name" class="form-control" value=${name} onInput=${(e) => setName(e.target.value.trim())} />
        ${name && !IDENT.test(name) ? html`<div class="small text-danger">Letters, digits and underscores, starting with a letter.</div>` : null}</div>
      <div class="col-md-7"><fieldset><legend class="form-label small text-muted mb-1 fs-6">Key — what makes a row distinct</legend>
        ${fields.map((f) => html`<div class="form-check form-check-inline"><input class="form-check-input" type="checkbox"
          id=${"ob-key-" + f.name} checked=${keys.includes(f.name)} onChange=${() => toggle(f.name)} />
          <label class="form-check-label small mono" for=${"ob-key-" + f.name}>${f.name}</label></div>`)}
        <div class="form-text small">For a windowed count, the window and the grouping column together.</div></fieldset></div>
      ${state.status === "error" ? html`<div class="col-12">${errorView(state.err)}</div>` : null}
      <div class="col-12 d-flex gap-2"><button type="button" class="btn btn-outline-secondary" onClick=${back}>Back</button>
        <button type="submit" class="btn btn-primary" disabled=${!IDENT.test(name) || !keys.length || state.status === "loading"}>
          ${state.status === "loading" ? "Registering…" : "Register"}</button></div>
    </form></section>`;
}

function WatchStep({ registered }) {
  const live = "/views/" + encodeURIComponent(registered.name) + "/live";
  return html`<section aria-labelledby="s4"><h2 id="s4">4 · Watch it change</h2>
    <div class="alert alert-success" role="status"><strong class="mono">${registered.name}</strong> is ${registered.state.toLowerCase()}
      (fingerprint <span class="mono">${registered.fingerprint}</span>).</div>
    <p>Open the live view: it shows the view's current rows and every change as the engine commits it, with its weight.
      If nothing arrives, the usual reasons are that no source is bound to the stream, or — for a windowed question — that
      the stream has no declared event time, so no window ever closes.</p>
    <div class="d-flex gap-2 flex-wrap"><a class="btn btn-primary" href=${live}>Watch it change</a>
      <a class="btn btn-outline-secondary" href=${"/views/" + encodeURIComponent(registered.name)}>Get client code</a>
      <a class="btn btn-outline-secondary" href="/operations">See it on the operations dashboard</a>
      <a class="btn btn-link" href="/home">Done</a></div></section>`;
}

function Onboarding({ initialStreams, initialChosen, engineUp }) {
  const [step, setStep] = useState(0);
  const [streams, setStreams] = useState(initialStreams);
  const [chosen, setChosen] = useState(initialStreams.find((s) => s.name === initialChosen) || initialStreams[0] || null);
  const [sql, setSql] = useState("");
  const [validation, setValidation] = useState({ status: "idle", output_fields: [], diagnostics: [] });
  const [registered, setRegistered] = useState(null);
  const validate = useRef(debounce(async (text) => {
    if (!text.trim()) { setValidation({ status: "idle", output_fields: [], diagnostics: [] }); return; }
    setValidation((v) => ({ ...v, status: "checking" }));
    try {
      const answer = await call("/sql/validate", { json: { sql: text } });
      setValidation({ status: answer.valid ? "valid" : "invalid", ...answer });
    } catch (err) { setValidation({ status: "unavailable", error: err.message, output_fields: [], diagnostics: [] }); }
  }, 300)).current;
  useEffect(() => { validate(sql); }, [sql]);
  const go = (n) => { setStep(n); setTimeout(() => { const h = document.querySelector("#start-app h2"); if (h) { h.setAttribute("tabindex", "-1"); h.focus(); } }, 0); };

  return html`<div>
    <${Steps} step=${step} />
    ${!engineUp ? html`<div class="alert alert-warning" role="status"><div class="fw-semibold">The engine is not answering yet</div>
      Start <code>pravaha-server</code> and reload. Every step below needs it.</div>` : null}
    ${step === 0 ? html`<${StreamStep} streams=${streams} setStreams=${setStreams} chosen=${chosen} setChosen=${setChosen} next=${() => go(1)} />` : null}
    ${step === 1 ? html`<${QuestionStep} chosen=${chosen} sql=${sql} setSql=${setSql} validation=${validation} back=${() => go(0)} next=${() => go(2)} />` : null}
    ${step === 2 ? html`<${RegisterStep} chosen=${chosen} sql=${sql} validation=${validation} back=${() => go(1)} done=${(r) => { setRegistered(r); go(3); }} />` : null}
    ${step === 3 && registered ? html`<${WatchStep} registered=${registered} />` : null}
  </div>`;
}

if (root) {
  const streams = JSON.parse(root.dataset.streams || "[]");
  const chosen = root.dataset.chosen;
  const engineUp = root.dataset.engineUp === "true";
  root.replaceChildren();
  render(html`<${Onboarding} initialStreams=${streams} initialChosen=${chosen} engineUp=${engineUp} />`, root);
}
