SELECT d.shipment_id, d.depot, v.courier, d.dispatch_time, v.delivered_time
FROM dispatch AS d
JOIN delivery AS v
  ON v.shipment_id = d.shipment_id
 AND v.delivered_time BETWEEN d.dispatch_time + INTERVAL '2' HOUR AND d.dispatch_time + INTERVAL '4' HOUR
