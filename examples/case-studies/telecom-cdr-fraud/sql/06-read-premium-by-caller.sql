SELECT caller, callee_country, COUNT(*) AS calls, SUM(duration_s) AS seconds
FROM premium_calls
GROUP BY caller, callee_country
