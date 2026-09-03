# News 2.10.0

**Room to read.**

The first release set every row at under half a grid unit of padding and every edge at one
unit, and on this panel that read as a spreadsheet: proximity was the only thing telling one
row from the next. Rows are taller now (article rows 6 units, feed and category rows 5, settings
rows 1.1 units of padding each side), every list sits on a hairline rule so the space can be
space, and the margins move out to 1.5 units — 1.75 in the article itself. Fewer rows to a
screen, on purpose; the wheel does the scrolling. The thumbnail moves to the right edge, square
and small, so a headline keeps the width instead of being pushed into a narrow column.

**The article reads like a page.** The title is set in Heading with the byline once beneath it
— author and date, nothing else; the feed is in the bar and the site is one tap away. Body copy
gets 1.5 leading against the lists' 1.25, paragraphs 1.4 units apart, and a Kagi story's
sections (HIGHLIGHTS, PERSPECTIVES, SOURCES…) sit under a rule with real air above them instead
of a small grey word floating between paragraphs. The three stacked action rows at the end
(OPEN / MARK READ / ARCHIVE) that looked like more article are one line of buttons under a rule.
Empty states and status lines are set as content, left-aligned at the reading margin in full
type, rather than a centred grey paragraph. The Kagi picker's parent rows (USA ▸ 24) are
visibly parents now, with a rule under each.

**The wheel.**

*Acceleration.* A notch was worth a flat 64 dp. Now a notch that lands within 45 ms of the last
— a flick — covers 2.6×, one within 90 ms 1.7×, within 180 ms 1.2×, and a single deliberate
click exactly 1×. Nudging a paragraph into view is unchanged; getting past forty source links
takes two spins instead of eight.

*Rows land whole.* Lists scroll in whole rows but the wheel scrolled in pixels, so it stopped
with a row cut in half. The glide now settles on the nearest row boundary.

*A lone click is not lost.* The first notch after a pause used to buy nothing until a second
notch arrived — one deliberate click did nothing, which reads as a broken wheel. It is now held
for 150 ms and released on its own if nothing follows; a second notch inside that window still
releases both together, so a stray brush that comes as a pair is treated as before.

*Turn past the end for the next thing.* At the bottom of an article the wheel used to stop.
Three more notches now turn to the next article in the feed, in place — no new screen, BACK still
goes straight to the list — and three at the top go to the previous one. A status line names
what a further turn will open. At the bottom of a Kagi category, the same gesture is the NEXT
button. The glide is also corrected for the frame's real length, so a dropped frame on a large
image no longer halves the smoothing.

No schema change. Installs over 2.9.0 and keeps everything.
