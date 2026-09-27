SELECT d.shipment_id, d.depot, d.dispatch_time
FROM dispatch AS d
LEFT JOIN delivery AS v
  ON v.shipment_id = d.shipment_id
 AND v.delivered_time BETWEEN d.dispatch_time AND d.dispatch_time + INTERVAL '4' HOUR
WHERE v.shipment_id IS NULL
  AND d.service = 'express'
