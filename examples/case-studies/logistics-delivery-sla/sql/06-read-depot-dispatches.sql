SELECT depot, SUM(dispatched) AS dispatched
FROM depot_dispatches
GROUP BY depot
