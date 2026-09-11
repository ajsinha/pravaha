SELECT STREAM
  TUMBLE_END(r.called_at, INTERVAL '30' SECOND) AS window_end,
  r.run_id,
  r.sample_id,
  m.panel,
  COUNT(*)                    AS read_count,
  AVG(r.depth)                AS mean_depth,
  MIN(r.mapping_quality)      AS min_mapq,
  COUNT(DISTINCT r.target_id) AS targets_touched
FROM read_metric AS r
LEFT JOIN sample_manifest FOR SYSTEM_TIME AS OF r.called_at AS m
       ON r.sample_id = m.sample_id
WHERE r.mapping_quality >= 30
GROUP BY TUMBLE(r.called_at, INTERVAL '30' SECOND), r.run_id, r.sample_id, m.panel
