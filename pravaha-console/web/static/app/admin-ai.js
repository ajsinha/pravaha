/*
 * Pravaha console -- Admin -> AI: Test connection (drishti's admin-ai-test.js).
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * Beside each of the engine's providers: POSTs the model to the console's /api/v1/admin/ai/test, which
 * asks the engine for one real call (an administrator only, capped and rate-limited there). The answer
 * -- ok or not, the model, how long it took, the engine's words -- is only ever put in the page as text.
 */
import { t, call } from "pravaha/lib.js";

document.addEventListener("click", async (event) => {
  const button = event.target.closest ? event.target.closest("[data-ai-test]") : null;
  if (!button) return;
  const model = button.dataset.aiTest;
  const out = [...document.querySelectorAll("[data-ai-test-result]")].find((n) => n.dataset.aiTestResult === model);
  button.disabled = true;
  if (out) { out.textContent = t("admin_ai.testing"); out.removeAttribute("data-ok"); }
  let answer;
  try {
    answer = await call("/admin/ai/test", { json: { model } });
  } catch (err) {
    answer = Object.assign({ ok: false, error: err.message }, err.payload || {});
  }
  if (out) {
    out.textContent = answer.ok
      ? t("admin_ai.test_ok", { model: answer.model || model, ms: answer.millis ?? "?" })
      : t("admin_ai.test_failed", { detail: [answer.code, answer.error].filter(Boolean).join(" ") });
    out.setAttribute("data-ok", answer.ok ? "yes" : "no");
  }
  button.disabled = false;
});
