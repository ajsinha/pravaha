# Cards used more than three times in a minute: HAVING on a window

`HAVING` filters the groups a window produces, so only the users over the threshold are in the view.
A fraud screen subscribes to `fast_spenders` and is told each user once per minute that trips it; an
[alert](/help/examples#spend-alert) can follow the view and notify a channel instead.

**What the data does.** `u9` pays four times between 09:00:02 and 09:00:31, so when the minute
`[09:00, 09:01)` closes the view gains `(u9, 09:01) payments = 4`. `u1` paid once in that minute and
is not in the view.
