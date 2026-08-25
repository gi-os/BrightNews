# News 2.6.0

Archiving an article no longer loses it.

The archive flag has been in the database since the fork, and nothing ever deleted an archived
article — but no screen queried a hidden row, so there was no way back to one. Archive by mistake
and the article was gone as far as the app was concerned, which is the same thing as gone.

There is now an **Archive** screen. It lists every archived article, newest first, from both
sections, and it opens them in the same reader as anything else. Reach it from the **Archive**
button in the Subscriptions bar or the Mailbox bar, next to Saved and Refresh.

Getting an article back happens in two places. Open one from the archive and the row at the end of
the article reads **RESTORE** instead of **ARCHIVE** — the same row that hid it puts it back, and
the reader stays put so the row flipping back is the confirmation. In the newsletter reader the
bar's archive icon does the same thing. For a sweep rather than a single mis-tap, **RESTORE ALL**
in the archive's bottom bar empties the archive back into the lists.

One quieter fix behind it. Archived newsletters were still subject to the per-label trim that keeps
the newest issues and to the cleanup that drops issues Gmail no longer lists, so an archived issue
could disappear on a later sync even with a screen to view it from. Archived items are now exempt
from both, the way saved items already were. The trim keeps the phone from filling up with a year
of dailies; things you deliberately kept are not what it should be spending.

Nothing about the database schema changed, so this installs over 2.5.0 and keeps every
subscription, label, read state and saved article. Anything you archived before this release is
already in the archive waiting — the rows were never gone, only unreachable.
