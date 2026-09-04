# News 3.4.0

**If News closes itself, the next launch says why.** A crash is written to a file as the process
dies; the next launch opens on the trace, sends it to gi-os/light-reports the way the other
Bright* apps do, and keeps it on screen until dismissed so it can be read off the phone even
with no network. This is what was missing while the launch crash was being chased blind.

**No header.** Home has no bar at the top any more: the date is the briefing's title and the
first bucket the timeline's. Everything that lived up there is in the bottom bar — the two tabs,
sources (Kagi categories on the briefing, Subscriptions on the timeline), search, and settings
behind a gear. Refresh is a gesture: pull down past the top, or turn the wheel up past it.

**Today's dozen, and only today's.** When a new Kagi edition lands, the previous one moves to
the archive — still there for search, saved items and the Archive screen, never on the briefing.
A category shows at most twelve.

**Categories in the order you read them.** The briefing sorts categories by how many of their
stories you have opened, decided once a day in the morning so the page does not reshuffle under
you between breakfast and lunch. A newly followed category joins at the end until tomorrow.

**Back where you were.** Coming back from an article lands on the same spot in the briefing or
the timeline; the position used to reset to the top.

No schema change. Installs over 3.3.1 and keeps everything.
