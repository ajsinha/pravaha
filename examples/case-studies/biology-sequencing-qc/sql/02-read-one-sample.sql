SELECT sample_id, panel, read_count, mean_depth, min_mapq, targets_touched
FROM coverage_qc
WHERE sample_id = ?
