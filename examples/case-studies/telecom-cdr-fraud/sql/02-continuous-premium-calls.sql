SELECT cdr_id, caller, callee, callee_country, duration_s, start_time
FROM call_record
WHERE callee_country IN ('SB', 'TV', 'NU') AND duration_s >= 60
