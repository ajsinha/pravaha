SELECT risk_band, COUNT(*) AS cards, SUM(total_minor) AS exposure_minor
FROM card_velocity
GROUP BY risk_band
