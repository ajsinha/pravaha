-- Tutorial 5: the second version of big_payments -- the threshold raised from 1000 to 5000.
SELECT txn_id, user_id, amount FROM txn WHERE amount > 5000
