SELECT panel, COUNT(*) AS samples, SUM(read_count) AS read_count, MIN(min_mapq) AS worst_mapq
FROM coverage_qc
GROUP BY panel
