# News 3.7.0

**The list button now goes to the same place from both tabs.**

Before this release the middle button in the home bar routed on whichever tab you were on:
from the Daily Briefing it opened Kagi's categories, from the Timeline it opened your RSS
subscriptions. Same glyph, same slot, two destinations. A reader who learned it on the
briefing could not find the feeds they had just subscribed to, because every press landed in
Kagi. Reported on Discord, and fair.

The button now opens one **Sources** screen from either tab. Three rows, always in this order,
each with a count so an empty section says so before you open it:

- **KAGI NEWS** — the categories behind the Daily Briefing
- **RSS FEEDS** — the subscriptions behind the Timeline
- **MAILBOX** — the Gmail labels that put newsletters in the Timeline

Saved, Archive and Settings sit in that screen's bar, as they did on both of the screens it
now stands in front of. Settings also lists KAGI NEWS above SUBSCRIPTIONS, so the three
sources appear in the same order everywhere.

RSS-only mode (Settings → HOME) is unchanged: the list button goes straight to the
subscriptions, since Kagi is off there.

No schema change. Installs over 3.6.1 and keeps everything.
