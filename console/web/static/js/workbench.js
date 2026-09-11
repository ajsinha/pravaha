/*
 * Pravaha console — the workbench.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * The form posts and the server renders the result, which is what happens with
 * this file absent. Two conveniences are added here and nothing else: Ctrl+Enter
 * runs, and the SQL typed above is carried into the register form so nobody has
 * to paste a query into a second box to keep it.
 */
(function () {
  "use strict";
  var sql = document.getElementById("sql");
  var registerSql = document.getElementById("register-sql");
  var form = document.getElementById("run-form");
  if (!sql || !form) { return; }

  sql.addEventListener("input", function () {
    if (registerSql) { registerSql.value = sql.value; }
  });

  sql.addEventListener("keydown", function (event) {
    if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      form.submit();
    }
  });
}());
