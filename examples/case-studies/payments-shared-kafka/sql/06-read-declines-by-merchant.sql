SELECT merchant, COUNT(*) AS declines, SUM(amount_minor) AS declined_minor
FROM declines
GROUP BY merchant
