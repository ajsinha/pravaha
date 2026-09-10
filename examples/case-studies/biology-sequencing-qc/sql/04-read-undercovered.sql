SELECT run_id, sample_id, mean_depth, targets_touched
FROM coverage_qc
WHERE mean_depth < ?
