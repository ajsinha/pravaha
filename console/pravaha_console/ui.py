"""The rendered shell and the screens inside it.

Server-rendered HTML first, made live by ``static/app.js`` (ADR-023's shell; ADR-033's
service layer underneath). Two consequences that are the reason for it:

**The page works before its JavaScript does.** Every screen renders something useful from
the server, and the browser module upgrades it. A console that is blank until a bundle
loads is blank exactly when somebody is debugging why things are not loading.

**The theme is in the markup, not applied after paint.** ``data-theme`` is on the root
element in the first byte the browser sees, so a dark-mode reader never gets a white flash.
"""
from __future__ import annotations

import html

from fastapi.responses import HTMLResponse

from pravaha_console.services import Health

#: Where each screen's contextual help points. Design 23.20 asks that a new user reach a
#: running query in under five minutes, and the documentation is no use to them if finding
#: it is a separate act of navigation from the thing they are stuck on.
HELP = {
    "/": [("quickstart", "Quick start"), ("concepts", "Concepts"), ("user-guide", "User guide")],
    "/queries": [("user-guide", "Registering and sharing"), ("concepts", "Why two names can be one computation")],
    "/workbench": [("sql-support", "What SQL is supported"), ("quickstart", "Quick start"), ("troubleshooting", "When it refuses")],
    "/help": [],
}


def help_cards(current: str) -> str:
    """The help relevant to this screen, on this screen."""
    cards = HELP.get(current, [])
    if not cards:
        return ""
    links = "".join(
        f'<a class="button" href="/help/{name}">{esc(title)} — read more</a>' for name, title in cards
    )
    return f'''<div class="card helpcards" style="margin-top:24px"><div class="card-body">
      <strong>Help</strong>
      <p class="subtitle" style="margin:4px 0 12px">The documentation that ships with this
      engine, on the screen it is about.</p>
      <div class="row">{links}<a class="button" href="/help">All guides — read more</a></div>
    </div></div>'''


NAV = (
    ("/", "Overview"),
    ("/queries", "Queries"),
    ("/workbench", "Workbench"),
    ("/help", "Help"),
)


def esc(value: object) -> str:
    return html.escape(str(value if value is not None else ""))


def shell(body: str, *, title: str, current: str, health: Health | None = None) -> HTMLResponse:
    """Wraps a screen in the chrome, the design system and the browser module."""
    # Built without a backslash inside the f-string: that is a syntax error before Python
    # 3.12, and this package supports 3.11. It ran here only because the development
    # interpreter is newer than the one the project promises to work on.
    current_attr = ' aria-current="page"'
    nav = "".join(
        f'<a href="{path}"{current_attr if path == current else ""}>{esc(label)}</a>'
        for path, label in NAV
    )
    if health is None:
        status = ""
    elif health.reachable:
        status = '<span class="pill good"><span class="dot"></span>engine up</span>'
    else:
        # Named on every page, not just the one that failed. An operator who navigates to
        # another screen must not lose the one fact that explains why it is empty.
        status = '<span class="pill bad"><span class="dot"></span>engine unreachable</span>'

    return HTMLResponse(f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{esc(title)} — Pravaha</title>
<link rel="stylesheet" href="/static/app.css">
<script>
  /* Before first paint: a dark-mode reader should never see a white flash, and a flash is
     what applying the theme from a module after load would give them. */
  (function () {{
    var t = localStorage.getItem('pravaha.theme');
    var d = localStorage.getItem('pravaha.density');
    if (t) document.documentElement.dataset.theme = t;
    if (d) document.documentElement.dataset.density = d;
  }})();
</script>
</head>
<body>
<a class="skip-link" href="#main">Skip to content</a>
<header class="app">
  <span class="brand"><span class="mark" aria-hidden="true"></span>Pravaha <small>console</small></span>
  <nav class="app" aria-label="Sections">{nav}</nav>
  <span class="spacer"></span>
  {status}
  <button type="button" id="theme" title="Toggle light and dark (t)" aria-label="Toggle theme">◐</button>
  <button type="button" id="density" title="Toggle density (d)" aria-label="Toggle density">≡</button>
</header>
<main id="main">{body}</main>
<footer class="app">
  Pravaha console — a functional admin surface. Copyright © 2026 Ashutosh Sinha.
</footer>
<script type="module">
  import {{ toggleTheme, toggleDensity, installShortcuts }} from '/static/app.js';
  document.getElementById('theme').addEventListener('click', toggleTheme);
  document.getElementById('density').addEventListener('click', toggleDensity);
  installShortcuts({{
    t: toggleTheme,
    d: toggleDensity,
    '/': () => document.querySelector('input[type=search]')?.focus(),
    g: () => (location.href = '/queries'),
    '?': () => (location.href = '/help'),
  }});
</script>
</body>
</html>""")



def _query_rows(queries: list, *, columns: int = 6) -> str:
    """Table rows for a list of queries, rendered by the server.

    The client re-renders these as things change, but the first paint comes from here. A
    console that is blank until a module loads is blank exactly when somebody is looking at
    it because something is not loading.
    """
    if not queries:
        return (
            f'<tr><td colspan="{columns}"><div class="state"><h3>No queries yet</h3>'
            "<p>A continuous query is registered once and maintained for as long as it is "
            "registered. Nothing has been registered on this engine.</p>"
            '<a class="button" href="/workbench">Register one</a></div></td></tr>'
        )
    out = []
    for q in queries:
        tone = "good" if q.state == "RUNNING" else "bad" if q.state == "FAILED" else "mute"
        shared = '<span class="pill warn" title="Another name shares this computation">shared</span>' if q.shared else ""
        cells = [
            f'<td><a href="/queries/{esc(q.name)}">{esc(q.name)}</a></td>',
            f'<td><span class="pill {tone}">{esc(q.state)}</span></td>',
            f'<td class="num">{q.rows_in:,}</td>',
            f"<td>{shared}</td>",
        ]
        if columns >= 6:
            sql = q.sql[:64] + ("\u2026" if len(q.sql) > 64 else "")
            cells.append(f"<td><code>{esc(sql)}</code></td>")
            cells.append(f'<td><a href="/queries/{esc(q.name)}">Open</a></td>')
        out.append("<tr>" + "".join(cells) + "</tr>")
    return "".join(out)


def overview(health: Health, queries: list | None = None) -> str:
    """The first screen: is it up, what is registered, and what is it doing."""
    if not health.reachable:
        return f"""
        <h1>Overview</h1>
        <p class="subtitle">One engine, at <code>{esc(health.url)}</code>.</p>
        <div class="banner error" role="alert"><span aria-hidden="true">!</span><div class="body">
          <div class="title">The engine is not answering</div>
          <div>{esc(health.error or "no reason given")}</div>
          <div style="margin-top:8px">
            The console is running and this page is current — it is the engine at
            <code>{esc(health.url)}</code> that cannot be reached. Check that it is started and
            that its Flight port matches.
            <button type="button" class="primary" onclick="location.reload()">Try again</button>
          </div>
        </div></div>
        <div class="grid cols-4" id="stats"></div>
        """
    # Rendered by the server, then kept current by the module. The page is useful before
    # its JavaScript runs, which matters most when somebody is looking at this console
    # precisely because something is not loading.
    rows = _query_rows(queries or [], columns=4)
    return f"""
    <h1>Overview</h1>
    <p class="subtitle">One engine, at <code>{esc(health.url)}</code>.
      <span class="freshness" id="freshness"></span></p>
    <div class="grid cols-4" id="stats">
      <div class="card stat"><div class="label">Registered</div><div class="value"><div class="skeleton" style="height:26px"></div></div></div>
      <div class="card stat"><div class="label">Running</div><div class="value"><div class="skeleton" style="height:26px"></div></div></div>
      <div class="card stat"><div class="label">Shared</div><div class="value"><div class="skeleton" style="height:26px"></div></div></div>
      <div class="card stat"><div class="label">Live feeds</div><div class="value"><div class="skeleton" style="height:26px"></div></div></div>
    </div>
    <h2>Recently registered</h2>
    <div class="card"><table id="recent">
      <thead><tr><th>Name</th><th>State</th><th class="num">Rows in</th><th>Shared</th></tr></thead>
      <tbody>{rows}</tbody>
    </table></div>
    <script type="module">
      import {{ call, States, Freshness, escapeHtml }} from '/static/app.js';
      const fresh = new Freshness(document.getElementById('freshness'));
      async function load() {{
        fresh.refreshing();
        try {{
          const [stats, queries] = await Promise.all([call('/stats'), call('/queries?limit=8&sort=-rows_in')]);
          document.getElementById('stats').innerHTML = [
            ['Registered', stats.queries, 'continuous queries'],
            ['Running', stats.states.RUNNING || 0, 'maintaining a view'],
            ['Shared', stats.shared, 'names on a shared computation'],
            ['Live feeds', stats.upstream_subscriptions, 'engine subscriptions, however many tabs'],
          ].map(([label, value, note]) =>
            `<div class="card stat"><div class="label">${{label}}</div>
             <div class="value">${{value}}</div><div class="note">${{note}}</div></div>`).join('');
          const body = queries.items.length
            ? queries.items.map(q => `<tr>
                <td><a href="/queries/${{encodeURIComponent(q.name)}}">${{escapeHtml(q.name)}}</a></td>
                <td><span class="pill ${{q.state === 'RUNNING' ? 'good' : 'mute'}}">${{escapeHtml(q.state)}}</span></td>
                <td class="num">${{q.rows_in.toLocaleString()}}</td>
                <td>${{q.shared ? '<span class="pill warn">shared</span>' : ''}}</td></tr>`).join('')
            : `<tr><td colspan="4">${{States.emptyNever('queries',
                '<a class="button" href="/workbench">Register one</a>')}}</td></tr>`;
          document.querySelector('#recent tbody').innerHTML = body;
          document.querySelector('#recent tbody').removeAttribute('aria-busy');
          fresh.updated();
        }} catch (err) {{
          fresh.stale();
          document.getElementById('stats').insertAdjacentHTML('beforebegin', States.error(err, 'location.reload()'));
        }}
      }}
      load();
      setInterval(load, 5000);
    </script>
    """


def queries_screen(queries: list | None = None) -> str:
    """The list: filtered, sorted, paged, and every one of those in the URL."""
    return _QUERIES_SCREEN.replace("__ROWS__", _query_rows(queries or [], columns=6))


_QUERIES_SCREEN = """
    <h1>Queries</h1>
    <p class="subtitle">Registered continuous queries. Every filter is in the URL, so this
      view can be shared as it is. <span class="freshness" id="freshness"></span></p>

    <div class="toolbar">
      <input type="search" id="search" placeholder="Filter by name or SQL  ( / )" aria-label="Filter queries">
      <select id="state" aria-label="Filter by state">
        <option value="">Any state</option>
        <option value="RUNNING">Running</option>
        <option value="PAUSED">Paused</option>
        <option value="FAILED">Failed</option>
      </select>
      <select id="sort" aria-label="Sort">
        <option value="name">Name</option>
        <option value="-rows_in">Busiest first</option>
        <option value="state">State</option>
      </select>
      <span class="spacer"></span>
      <a class="button" href="/workbench">Register a query</a>
    </div>

    <div id="banner"></div>
    <div class="card"><table id="table">
      <thead><tr><th>Name</th><th>State</th><th class="num">Rows in</th><th>Shared</th><th>SQL</th><th></th></tr></thead>
      <tbody>__ROWS__</tbody>
    </table></div>
    <div class="toolbar" style="margin-top:16px">
      <button type="button" id="prev">Previous</button>
      <span id="count" class="freshness"></span>
      <button type="button" id="next">Next</button>
    </div>

    <script type="module">
      import { call, States, Freshness, Url, escapeHtml } from '/static/app.js';
      const fresh = new Freshness(document.getElementById('freshness'));
      const tbody = document.querySelector('#table tbody');
      let first = false;  // the server already rendered the first page

      const state = { search: '', state: '', sort: 'name', offset: 0, limit: 25, ...Url.read() };
      state.offset = Number(state.offset) || 0;
      state.limit = Number(state.limit) || 25;
      document.getElementById('search').value = state.search;
      document.getElementById('state').value = state.state;
      document.getElementById('sort').value = state.sort;

      window.clearFilter = () => {
        state.search = ''; state.state = ''; state.offset = 0;
        document.getElementById('search').value = '';
        document.getElementById('state').value = '';
        load();
      };
      window.reload = () => load();

      async function load() {
        // First load gets a skeleton; a refresh keeps the rows on screen and moves the
        // freshness marker instead of blanking and refilling.
        if (first) tbody.innerHTML = States.loadingFirst(6, 6).replace(/<\\/?tbody[^>]*>/g, '');
        fresh.refreshing();
        Url.write(state);
        const query = new URLSearchParams({
          search: state.search, state: state.state, sort: state.sort,
          offset: state.offset, limit: state.limit,
        });
        try {
          const page = await call('/queries?' + query);
          document.getElementById('banner').innerHTML = '';
          if (!page.items.length) {
            tbody.innerHTML = `<tr><td colspan="6">` +
              (state.search || state.state
                ? States.emptyFiltered('clearFilter()')
                : States.emptyNever('queries', '<a class="button" href="/workbench">Register one</a>')) +
              `</td></tr>`;
          } else {
            tbody.innerHTML = page.items.map(q => `<tr>
              <td><a href="/queries/${encodeURIComponent(q.name)}">${escapeHtml(q.name)}</a></td>
              <td><span class="pill ${q.state === 'RUNNING' ? 'good' : q.state === 'FAILED' ? 'bad' : 'mute'}">${escapeHtml(q.state)}</span></td>
              <td class="num">${q.rows_in.toLocaleString()}</td>
              <td>${q.shared ? '<span class="pill warn" title="Another name shares this computation">shared</span>' : ''}</td>
              <td><code>${escapeHtml(q.sql.slice(0, 64))}${q.sql.length > 64 ? '…' : ''}</code></td>
              <td><a href="/queries/${encodeURIComponent(q.name)}">Open</a></td></tr>`).join('');
          }
          tbody.removeAttribute('aria-busy');
          document.getElementById('count').textContent =
            `Showing ${page.items.length} of ${page.total}`;
          document.getElementById('prev').disabled = state.offset === 0;
          document.getElementById('next').disabled = state.offset + state.limit >= page.total;
          fresh.updated();
          first = false;
        } catch (err) {
          fresh.stale();
          document.getElementById('banner').innerHTML = States.error(err, 'reload()');
          tbody.innerHTML = '';
          tbody.removeAttribute('aria-busy');
        }
      }

      let timer;
      document.getElementById('search').addEventListener('input', (e) => {
        clearTimeout(timer);
        state.search = e.target.value; state.offset = 0;
        timer = setTimeout(load, 200);
      });
      document.getElementById('state').addEventListener('change', (e) => { state.state = e.target.value; state.offset = 0; load(); });
      document.getElementById('sort').addEventListener('change', (e) => { state.sort = e.target.value; load(); });
      document.getElementById('prev').addEventListener('click', () => { state.offset = Math.max(0, state.offset - state.limit); load(); });
      document.getElementById('next').addEventListener('click', () => { state.offset += state.limit; load(); });

      load();
      setInterval(() => { if (!document.hidden) load(); }, 5000);
    </script>
    """


def query_detail(name: str, query=None, siblings=None, error: str | None = None) -> str:
    """One query: what it is, what it is doing, and a live tail of what it produces."""
    safe = esc(name)
    if query is None:
        # Rendered by the server so a missing name is a real answer rather than a shell that
        # only says so once a module has loaded and asked.
        detail = f'''<div class="banner error" role="alert"><div class="body">
          <div class="title">There is no query named "{safe}"</div>
          <div>{esc(error or "It may have been dropped, or the name may be misspelt.")}
          <a href="/queries">All queries</a></div></div></div>'''
        return f"<h1>{safe}</h1>" + detail
    tone = "good" if query.state == "RUNNING" else "bad" if query.state == "FAILED" else "mute"
    initial_sql = esc(query.sql)
    initial_meta = (
        f'''<span class="pill {tone}">{esc(query.state)}</span>
        <span class="pill mute">{query.rows_in:,} rows in</span>
        <span class="pill mute" title="The canonical plan this name resolves to">{esc(query.fingerprint[:12])}</span>'''
    )
    sibling_names = siblings or []
    initial_siblings = (
        '''<div class="banner info"><div class="body"><div class="title">Shared computation</div><div>'''
        + f"{len(sibling_names)} other name(s) resolve to the same plan: "
        + ", ".join(f'<a href="/queries/{esc(n)}">{esc(n)}</a>' for n in sibling_names)
        + ". Dropping this name will not stop the computation.</div></div></div>"
    ) if sibling_names else ""
    return f"""
    <h1>{safe}</h1>
    <p class="subtitle"><a href="/queries">← All queries</a></p>
    <div id="banner"></div>

    <div class="grid cols-2">
      <div class="card">
        <div class="card-head">Definition</div>
        <div class="card-body"><pre id="sql">{initial_sql}</pre>
          <div id="meta" style="margin-top:12px">{initial_meta}</div></div>
      </div>
      <div class="card">
        <div class="card-head">Controls</div>
        <div class="card-body">
          <div class="row">
            <!-- Real forms, so the controls work with scripting off; the module intercepts
                 them to avoid a full page load when it is on. -->
            <form method="post" action="/queries/{safe}/pause" style="margin:0"><button type="submit" id="pause">Pause</button></form>
            <form method="post" action="/queries/{safe}/resume" style="margin:0"><button type="submit" id="resume">Resume</button></form>
            <form method="post" action="/queries/{safe}/drop" style="margin:0"><button type="submit" id="drop" class="danger">Drop</button></form>
          </div>
          <p class="subtitle" style="margin-top:12px">
            Pausing keeps the state and stops the work; the view keeps answering at the frontier
            it reached. Dropping a name releases the computation only when it is the
            <strong>last</strong> name on it.
          </p>
          <div id="siblings">{initial_siblings}</div>
        </div>
      </div>
    </div>

    <h2>Live tail <span class="freshness" id="tail-state"></span></h2>
    <div class="tail" id="tail" role="log" aria-live="polite" aria-label="Live changes"></div>

    <script type="module">
      import {{ call, States, LiveTail, escapeHtml }} from '/static/app.js';
      const name = {name!r};
      window.reload = () => location.reload();

      async function load() {{
        try {{
          const q = await call('/queries/' + encodeURIComponent(name));
          document.getElementById('sql').textContent = q.sql;
          document.getElementById('meta').innerHTML = `
            <span class="pill ${{q.state === 'RUNNING' ? 'good' : q.state === 'FAILED' ? 'bad' : 'mute'}}">${{escapeHtml(q.state)}}</span>
            <span class="pill mute">${{q.rows_in.toLocaleString()}} rows in</span>
            <span class="pill mute" title="The canonical plan this name resolves to">${{escapeHtml(q.fingerprint.slice(0, 12))}}</span>`;
          document.getElementById('siblings').innerHTML = q.siblings.length
            ? `<div class="banner info"><div class="body"><div class="title">Shared computation</div>
               <div>${{q.siblings.length}} other name(s) resolve to the same plan:
               ${{q.siblings.map(s => `<a href="/queries/${{encodeURIComponent(s)}}">${{escapeHtml(s)}}</a>`).join(', ')}}.
               Dropping this name will not stop the computation.</div></div></div>`
            : '';
          document.getElementById('pause').disabled = q.state !== 'RUNNING';
          document.getElementById('resume').disabled = q.state === 'RUNNING';
        }} catch (err) {{
          document.getElementById('banner').innerHTML = States.error(err, 'reload()');
        }}
      }}

      for (const action of ['pause', 'resume', 'drop']) {{
        document.getElementById(action).addEventListener('click', async (event) => {{
          event.preventDefault();
          if (action === 'drop' && !confirm(`Drop the name "${{name}}"? If it is the last name on this computation, the state goes with it.`)) return;
          try {{
            await call(`/queries/${{encodeURIComponent(name)}}/${{action}}`, {{ method: 'POST' }});
            if (action === 'drop') location.href = '/queries'; else load();
          }} catch (err) {{
            document.getElementById('banner').innerHTML = States.error(err, 'reload()');
          }}
        }});
      }}

      const tailState = document.getElementById('tail-state');
      const tail = new LiveTail(name, document.getElementById('tail'), {{
        onState(state, detail) {{
          tailState.dataset.state = state === 'live' ? 'fresh' : state === 'lagging' ? 'refreshing' : 'stale';
          tailState.innerHTML = `<span class="dot"></span><span>${{detail || state}}</span>`;
          document.getElementById('tail').classList.toggle('stale', state === 'stale');
        }},
      }});
      load();
      tail.start();
      addEventListener('beforeunload', () => tail.stop());
    </script>
    """


def workbench() -> str:
    """Ask a question, see the answer, and register it if it is worth keeping."""
    return """
    <h1>Workbench</h1>
    <p class="subtitle">Ask a question once, or register it to be maintained for as long as it
      is registered.</p>
    <div id="banner"></div>

    <div class="card">
      <div class="card-head">Query</div>
      <div class="card-body">
        <div class="field">
          <label for="sql">SQL <span style="font-weight:400">— Ctrl+Enter runs it</span></label>
          <textarea id="sql" spellcheck="false"
            placeholder="SELECT user_id, SUM(amount) FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE)) GROUP BY window_start, window_end, user_id"></textarea>
        </div>
        <div class="field">
          <label for="params">Parameters <span style="font-weight:400">— comma-separated, one per
            <code>?</code> in the query</span></label>
          <input id="params" name="params" placeholder="e.g. 40, ACME">
        </div>
        <div class="row">
          <button type="button" id="run" class="primary">Run once</button>
          <div style="flex:1"><label for="name">Register as</label>
            <input id="name" placeholder="a name clients will use in FROM"></div>
          <div style="width:140px"><label for="keys">Key columns</label>
            <input id="keys" value="0" aria-describedby="keys-help"></div>
          <button type="button" id="register">Register</button>
        </div>
        <p class="subtitle" id="keys-help" style="margin:8px 0 0">
          Key columns are output ordinals, comma-separated. A view with no key is a log, and a
          point read against it has nothing to look up.
        </p>
      </div>
    </div>

    <h2>Result <span class="freshness" id="freshness"></span></h2>
    <div class="card"><div id="result" class="card-body">
      <div class="state"><h3>Nothing run yet</h3>
      <p>Write a query above and run it. A one-off query is answered from the views the engine
      already maintains; nothing is registered until you ask for it.</p></div>
    </div></div>

    <script type="module">
      import { call, States, Freshness, escapeHtml } from '/static/app.js';
      const fresh = new Freshness(document.getElementById('freshness'));
      const result = document.getElementById('result');
      window.reload = () => run();

      async function run() {
        const sql = document.getElementById('sql').value;
        if (!sql.trim()) return;
        fresh.refreshing();
        result.innerHTML = '<table>' + States.loadingFirst(4, 4) + '</table>';
        try {
          const raw = document.getElementById('params').value.trim();
          const parameters = raw ? raw.split(',').map(v => {
            const t = v.trim();
            // Numbers stay numbers: a parameter is a value, and quoting 40 would compare a
            // string to an integer and silently match nothing (ADR-032).
            return t !== '' && !isNaN(Number(t)) ? Number(t) : t;
          }) : null;
          const data = await call('/query', { method: 'POST', body: JSON.stringify({ sql, parameters }) });
          document.getElementById('banner').innerHTML = '';
          if (!data.rows.length) {
            result.innerHTML = `<div class="state"><h3>No rows</h3>
              <p>The query ran in ${data.took_ms}ms and matched nothing. On a stream this often
              means the window has not closed yet: a window closes when data says it is over,
              not when the clock does.</p></div>`;
          } else {
            const head = data.columns.map(c => `<th>${escapeHtml(c)}</th>`).join('');
            const body = data.rows.map(r => `<tr>${r.map(v =>
              `<td class="${typeof v === 'number' ? 'num' : ''}">${escapeHtml(v)}</td>`).join('')}</tr>`).join('');
            result.innerHTML =
              (data.truncated ? States.partial(`${data.returned} rows shown; the result was larger`) : '') +
              `<table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>
               <p class="subtitle" style="margin-top:12px">${data.returned} rows in ${data.took_ms}ms</p>`;
          }
          fresh.updated();
        } catch (err) {
          fresh.stale();
          result.innerHTML = States.error(err, 'reload()');
        }
      }

      document.getElementById('run').addEventListener('click', run);
      document.getElementById('sql').addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); run(); }
      });

      document.getElementById('register').addEventListener('click', async () => {
        const payload = {
          name: document.getElementById('name').value,
          sql: document.getElementById('sql').value,
          keys: document.getElementById('keys').value,
        };
        try {
          const q = await call('/queries', { method: 'POST', body: JSON.stringify(payload) });
          location.href = '/queries/' + encodeURIComponent(q.name);
        } catch (err) {
          document.getElementById('banner').innerHTML = States.error(err, null);
        }
      });
    </script>
    """
