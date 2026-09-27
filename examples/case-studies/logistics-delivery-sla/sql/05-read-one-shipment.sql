SELECT shipment_id, depot, courier, dispatch_time, delivered_time
FROM late_deliveries
WHERE shipment_id = ?
