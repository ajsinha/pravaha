SELECT category, SUM(lines) AS lines, SUM(revenue_minor) AS revenue_minor
FROM category_revenue
GROUP BY category
