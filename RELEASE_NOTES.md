# News 2.8.0

**A feed that answers from a different address on every fetch no longer reappears unread.**

An article's id used to be hashed from the address the fetch actually landed on, after
redirects — and the app rewrote the stored feed address to that landing spot on every refresh.
For most feeds the two never differ. For a feed behind a rotating mirror, a tracking
parameter, or an http-to-https bounce, they differ every time, so every article got a brand-new
id on every refresh: the whole feed came back unread, and everything you had read, starred or
archived was orphaned under ids nothing would ever generate again. An article's identity now
belongs to the feed you subscribed to — the feed's own database id — not to whichever mirror
happened to answer.

**Existing articles are re-keyed once, in place.**

On the first launch after this update, every RSS article's id is recomputed under the new
scheme in a single transaction, guarded by a flag so it never runs twice. Read, starred and
archived travel with each row exactly as they were — the rewrite touches only the id. Where the
old scheme had stored the same article more than once under different addresses, the copies
collapse to one, and the copy you acted on (starred, then archived, then read) is the one that
survives. Gmail newsletters are untouched: their identity is the message id and never depended
on a URL.

**The address you subscribed with is now the one the app keeps.**

Refreshes still update a feed's title, description, caching headers and fetch time, but they no
longer overwrite the stored address with wherever the last fetch resolved. Every fetch starts
from the address you gave it; conditional requests go by ETag and Last-Modified as before.

Nothing about the database schema changed, so this installs over 2.7.0 and keeps every
subscription, label, read state and saved article.
