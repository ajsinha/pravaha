SELECT window_end, device, sessions, basket_minor
FROM checkout_funnel
WHERE step = ?
