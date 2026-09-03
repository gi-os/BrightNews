# News 2.9.0

**Kagi News is a third section.**

Kagi publishes its whole edition as public JSON — no key, no account: about 190 categories,
each a dozen stories a day, each story drawn from forty-odd articles and written up with a
summary, highlights, perspectives, a quote, a timeline and the source list. A category is now a
feed here, the way a Gmail label already was, so reading, saving, archiving and search all work
on a Kagi story without knowing where it came from.

The Kagi tab on home is the list of categories you follow, with unread counts, because a
category is meant to be read on its own — twelve stories, done — not shuffled into one long
list with the others. Inside a category the stories sit in Kagi's own ranking; **NEXT** in the
bottom bar turns the page to the following category so a morning's reading is one tap per
section. A story's sources are tappable and open in the reader. Footnote markers
(`[site.com#3]`) are stripped from the prose since the sources are listed underneath.

**Picking from 190 categories.** The picker shelves them: Kagi's general categories first
(World, USA, Business, Technology, Science, Sports, Gaming, Bay Area, On This Day), then places
folded under their parent — USA holds two dozen cities and states, Germany eight regions, and
the bare countries fold under one row — then the topics alphabetically. Nothing is more than
two taps deep. A category already followed says so and does not add twice.

**The same story is not stored twice.** A big story lands in World, USA and Middle East on the
same day. A story already held by another Kagi category from the same edition window is
skipped; the category refreshed first keeps it. Editions older than a week are trimmed unless
saved or archived. Refreshes are conditional (ETag / Last-Modified), so a day with no new
edition costs one small request per category.

**RSS articles are whole now, not a paragraph and a link.**

Most feeds send a summary. The reader used to show that, with an OPEN row that fetched the
page on demand. Now the page is fetched the first time an article is opened — the whole
story replaces the summary in place, with a status line while it loads — and the newest dozen
unread articles are fetched ahead of time on every refresh, so most articles open whole and
offline. The result is stored on the row, so it is never fetched twice, and it survives a
feed refresh. A paywall, a bot check or a page with no body copy falls back to the feed's
own text and is not retried on later refreshes; OPEN still tries again on demand. Newsletters
and Kagi stories are already whole and are left alone. **FULL ARTICLES** in Settings turns
the fetching off.

Schema moves from version 4 to 5 — one new column, empty until an article is opened. This
installs over 2.8.0 and keeps every subscription, label, read state and saved article.
