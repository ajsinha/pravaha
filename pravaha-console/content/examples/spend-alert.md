# Tell me when a user spends over 500 in a minute: an alert on a view

An alert compares a **view's own columns** with literals, so the arithmetic — the minute's total —
is a continuous query first, and the alert follows its answer. `ops_log` is a notifier channel bound
on the node:

```yaml
pravaha:
  notifiers:
    ops_log:
      plugin: log
```

**What the data does.** When the minute `[09:00, 09:01)` closes, `minute_spend` gains
`(u7, 09:01) spend = 570`: the key fires once, and `ops_log` is told. `u8`'s 90 does not.

The Alerts screen's **Describe an alert** writes statements like this one from your words; nothing is
created until you confirm. [Alerts](/help/topics/alerts) has the options and the lifecycle.
