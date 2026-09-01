# News 2.7.0

**The crash-tolerance rule from 2.6.2 now covers every reply from the phone, not just the keyboard.**

2.6.2 fixed the crash where a newer LightOS answered a keyboard question in a shape this tool
could not read. That release fixed the reply that was actually crashing; this one applies the
same rule to every reply the server can send. The token that authorizes the connection, the
server's version, and both halves of a permission check now tolerate a missing field the same
way the keyboard options do — and a permission answer this app has never heard of, from a phone
updated after this build shipped, is read as "unknown" instead of throwing. A token grant that
comes back empty now fails that one call quietly rather than being carried around as a
credential that isn't one.

**Feeds that don't date their articles stop leaping to the top on every refresh.**

Some feeds publish no dates at all. The reader has always invented a stamp for those so they
sort somewhere sensible — but it re-invented it on every refresh, so the whole feed re-stamped
itself to "just now," jumped to the top of the list, and reshuffled. A dateless article now
keeps the stamp it was given when it first arrived. Feeds that do date their articles are
untouched.

**The Subscriptions list measures its rows like everything else.**

2.6.2 replaced the hand-tuned row heights in the article and Gmail label lists with heights
measured from the type itself, because text scales with the screen's height and the grid with
its width. The Subscriptions list was the same shape with the same hand-tuned number and got
missed. It now uses the same measurement, so the article count under a feed's name cannot be
clipped on any screen.

**The version on the Settings screen is finally the real one.**

It said 2.4.1. It was hand-maintained, and hands forget. It is now generated from the same file
the build reads, so it cannot fall behind again.

**Signing in to a site can no longer lose its cookies to a fast Back.**

The sign-in view saved its cookies on the way out, but leaving the screen could cancel the save
mid-write — sign in, press DONE, and the site still turns you away. The save now runs to
completion regardless of how quickly the screen goes.

**A scanned Google credential that brings its own redirect now completes consent.**

Credentials from `scripts/authorize.py` can carry their own redirect address, and the app
stored it — but the consent screen only watched for the built-in ones, so the moment Google
handed the code back went by unrecognized and sign-in hung. The stored address is now watched
alongside the defaults.

**Smaller fixes.**

When Google revokes an authorization (the seven-day limit on test-mode consent screens does
this), Settings now says signed out immediately instead of SIGNED IN over credentials that no
longer work. A newsletter charset declared with trailing parameters ("utf-8; boundary=…") is
read correctly instead of falling back to UTF-8. A feed that starts with a byte-order mark or
stray whitespace parses instead of failing with an XML error. And a feed URL with a password in
it now works: those credentials were silently never sent, so the feed authenticated with
nothing — they are sent as a proper Authorization header now.

Nothing about the database schema changed, so this installs over 2.6.2 and keeps every
subscription, label, read state and saved article.
