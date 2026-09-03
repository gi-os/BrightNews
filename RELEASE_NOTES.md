# News 2.11.0

**The wheel no longer hesitates, and no longer jumps back at the end.**

The first notch of a turn was held for 150 ms in 2.10.0 so a stray brush could be told from a
click; that hold is what made every first turn feel late, and it is gone. A notch scrolls the
moment it arrives, and the glide settles a little faster (90% inside five frames instead of
seven). At the end of a list the row-snap used to pull the last screenful back up to a row
boundary — a jump against the direction you were turning, right at the bottom. Snapping now
stays out of the last and first screenful. The other cause of the same jump — the bars
reappearing at the end of a list and the list clamping its own offset — is closed by the
new chrome rule below.

**Rows use the whole width.** The list was padded away from the scroll bar twice: once for the
bar's own column and once more inside the list. The second padding is gone, the hairline under
each row runs edge to edge, and a row's text ends three quarters of a unit from the gutter
instead of a unit and a half. Headlines get the extra width.

**The scroll bar shows up only while something moves.** It fades in on the first pixel of
scroll and out nine tenths of a second after the last, in every list and every article. Its
gutter stays reserved, so nothing shifts sideways when it comes and goes.

**Bars hide when you scroll down and stay hidden.** Every header and footer — home, a feed, a
Kagi category, the article, the linked page, saved, archive, subscriptions, the Kagi picker —
slides away after forty pixels of downward scroll and comes back only after half a second of
continuous upward scrolling, or near the top. The old rule brought them back on twenty pixels
of up-scroll, which is one wheel notch or the slack in a thumb, so they flashed in and out while
reading. A pause of more than a quarter second breaks the upward run.

No schema change. Installs over 2.10.0 and keeps everything.
