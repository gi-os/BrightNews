# News 2.6.1

**Clear read articles no longer empties the archive.**

The settings row promised to keep your subscriptions and your saved articles, and it kept both.
What it did not say is that it also deleted every archived article — the ones v2.6.0 had just
given you a screen to find again — along with any newsletter you had read offline that Gmail had
not been told about yet. Two clauses were missing from one line of SQL. Every other delete in the
reader already carried them.

Two changes fix it. The delete now exempts archived articles and unpushed reads, the way it
already exempted saved ones. And **Mark all read** no longer flips archived articles to read,
which is what put them in the delete's path in the first place: an archived article is in no list
and in no unread count, so marking it read changed nothing you could see and everything about
what happened next. Reading state on archived articles is unchanged otherwise — Gmail's own read
state still reconciles onto them.

The confirmation screens say what actually happens now, rather than listing two of the four things
that survive.

**Newsletters can no longer embed a frame.** The renderer stripped scripts, stylesheets and
open-rate beacons from every issue, but it only removed iframes when images were turned off — and
images are on by default. A sender could load a document of their choosing into the reader and get
back exactly the open confirmation the beacon strip exists to deny. Frames, objects and embeds now
go with the scripts, in both modes.

**Signing out of Gmail signs you out of Google.** Consent runs in a WebView this app owns, so
Google's session cookies were outliving the tokens: sign out, sign back in, and the account
chooser was skipped and the previous account was reconnected without ever asking. Sign-out and
"forget client" now clear those cookies too.

Nothing about the database schema changed, so this installs over 2.6.0 and keeps every
subscription, label, read state and saved article. Anything already lost to the old delete is
gone; anything still in the archive is now safe from it.
