# News 3.3.1

**Fixes a crash on launch with the timeline open.**

A feed that stamps an article a little in the future — a timezone slip on the publisher's side
is enough — put that article in THIS MORNING while later items sat in THIS AFTERNOON, so the
timeline carried two THIS MORNING headers. The list keys its rows on the header label, and a
repeated key throws the moment the list is laid out. With the timeline as the remembered tab,
that was every launch.

Two fixes, either of which alone would do. A stamp from the future is now read as "now" — it is a
clock being wrong, not news from tomorrow — so the buckets stay in order. And the timeline is
built by grouping stories under each label rather than cutting wherever the label changes, so a
label can only ever appear once whatever the stamps say.

No schema change. Installs over 3.3.0 and keeps everything.
