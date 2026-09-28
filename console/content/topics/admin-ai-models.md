---
title: Admin · AI models — providers, models and budgets for the assistant
slug: admin-ai-models
category: reference
order: 61
icon: robot
summary: "Configure several model providers and models for the assistant, choose which answers each task and in what fallback order, set budgets, test a model, and switch while the console runs — each change used by the very next request."
badge: ADR-058
audience: Administrators
keywords: [ai models, admin, assistant, model, provider, profile, chain, fallback, default profile, budget, per_user_daily_tokens, per_request_max_tokens, test, usage, recent changes, conflict, api_key_env, api_key_file, assist.json, runtime, switch, ModelRouter, AssistAdmin]
guide: python-sdk
related: [assistant, audit, authentication, configuration]
---

**Admin · AI models** (`/admin/ai-models`) is where an administrator decides which language models
the [assistant](/help/topics/assistant) asks — several providers and several models at once — and
switches between them while the console runs. A change made here is checked in full, saved, and used
by the very next "Describe it" or "Explain" request, with no restart.

Only a person the engine gives the **`admin` role** may use it. The console asks the engine
(`GET /api/v1/auth/me`) on every request to this screen — not the roles it remembered when you
signed in — so taking the role away in the engine takes the screen away at once. Anyone else is
shown a *Not permitted* state, and a form posted anyway changes nothing.

## What is on the page

| Section | What it shows | What you can do |
|---|---|---|
| Models | each model's id, the provider's own model name, its provider and type, endpoint, **key reference** (the environment variable or secret-file name — never the value, and whether it is set in the console's process), enabled or disabled, and which profiles it is in (`#1` answers) | **Test** (a cheap ping: its latency, or the normalised error), enable, disable, edit, remove; add a model |
| Profiles | each profile's chain, in order — the first answers, the next is asked only when one is unavailable or rate-limited — and which is the default | move a model earlier or later, take it out, add one, make a profile the default, remove it; set a chain by typing it |
| Providers | each configured provider: type, endpoint, key reference, timeout, which models use it; and every provider type this console can build (built in, or installed as a package), with its capabilities | add, edit, remove (only when no model uses it) |
| Budgets | tokens per person per day, tokens per request | set either; empty is no limit |
| Usage | tokens (today, and over the days the ledger keeps) and requests, per model and per person | read-only |
| Recent changes | who changed what, the element before and after, and the version | read-only |

The profiles the console's surfaces ask for are **`draft`** ("Describe it") and **`explain`**
("Explain this query", and "Explain" beside a refusal). A task whose own profile is missing uses the
**default** profile.

## Switching models while things run

Every form is a `POST` carrying the session's CSRF token and the **version** of the configuration
the page was drawn from. The console then, in order:

1. rebuilds the configuration with your change and validates it completely — an unknown provider
   type, a duplicate id, a chain naming a disabled or missing model, a key variable not set in the
   console's process, a value shaped like a key — refusing with the reason and changing nothing;
2. saves it to the assistant's JSON file against that version;
3. applies it to the console's one model router — requests already running finish on the old
   configuration, and the next one uses the new;
4. records who, what, before and after, and the version in the console's assist log, shown under
   **Recent changes**.

**Disabling a model a chain still names is refused**: take it out of the chain first, so what
answers is always a decision somebody made.

**Two administrators at once.** If somebody else changed the configuration after your page was drawn,
your change is refused — *"somebody else changed the configuration since this page was drawn"*,
naming who and when — and nothing is overwritten. Reload and make it again.

**A change made elsewhere is followed too.** The file is the SDK's, shared with the command line:

```bash
pravaha assist use explain local-llama,claude --yes
```

The console notices within `assist.watch_seconds` (one second by default) and applies it — or, if it
does not validate in the console's own process (a key variable the console lacks), keeps the one in
force and says so at the top of this page.

## Keys

A key is **named, never given**: choose *environment variable* or *secret file* and type the name —
`ANTHROPIC_API_KEY`, or `/etc/pravaha/openai.key` (a file only its owner may read). The variable must
be set in the environment the console runs in; the page says *set here* or *not set here*. A value
shaped like a key (`sk-…`, `Bearer …`) is refused wherever it is typed, and no key is ever written
to the configuration, the log, or this page.

## Where it is kept

| Setting | Environment variable | Default |
|---|---|---|
| `assist.config` | `PRAVAHA_ASSIST_CONFIG` | `assist.json` in `$PRAVAHA_CONFIG_DIR`, else `~/.config/pravaha` — the file `pravaha assist` uses |
| `assist.usage` | `PRAVAHA_ASSIST_USAGE` | `assist-usage.json` beside it: tokens per person per day, which the daily budget counts |
| `assist.log` | `PRAVAHA_ASSIST_LOG` | `console-assist-log.jsonl` beside it: every change, and every request with who, which model, tokens, a hash of what was asked, the engine's verdict and whether it was registered |
| `assist.watch_seconds` | — | `1` |

With no file at all the console starts normally, the assistant's surfaces show that no model is
configured, and this page offers to add the first provider.

## Pitfalls

- **The key variable must be set where the console runs**, not where you are sitting. A model on a
  provider whose variable the console's process lacks is refused when you add or enable it.
- **Budgets are per console machine.** The ledger is a file beside the configuration; two consoles
  on two machines each count their own.
- **The usage view's requests come from this console's log**; requests made with `pravaha ask` on
  the command line spend from the same ledger when it is the same file, but are not in the log.
