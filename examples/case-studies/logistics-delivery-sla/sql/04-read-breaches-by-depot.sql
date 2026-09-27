SELECT depot, COUNT(*) AS late_deliveries
FROM late_deliveries
GROUP BY depot
