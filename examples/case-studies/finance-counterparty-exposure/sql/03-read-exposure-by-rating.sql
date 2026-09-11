SELECT rating, currency, COUNT(*) AS lines, SUM(outgoing_minor) AS total_minor
FROM counterparty_exposure
GROUP BY rating, currency
