-- Project Pravaha -- Ask once. Answer always.
-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
-- Proprietary and confidential; see the LICENSE file in the root of this repository.
--
-- The seed profile's first continuous query: every order on the Kafka topic `orders`, as a view
-- keyed by order_id. A view can then be asked anything -- a GROUP BY over it is a finite read.
SELECT order_id, customer, amount, event_time
FROM orders
