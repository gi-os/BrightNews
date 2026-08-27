# News 2.6.2

**Adding a feed no longer crashes the app.**

On a phone running a recent LightOS, opening any screen with a text field in it — add a feed by
URL, search, pick a Gmail label — took the whole tool down before it drew. Nothing was wrong with
the keyboard. Every one of those screens first asks LightOS what the keyboard should look like,
and the answer stopped being readable: the newer service leaves a field out of its reply instead
of sending it as empty, and to the code reading that reply a field that can be empty is still a
field that must be present. The read threw, the throw came back on a background thread nobody was
watching, and Android killed the process.

Three changes, in the order they matter. The reply now tolerates a missing field, an unfamiliar
field, and both at once, so a tool built today keeps working on a phone updated next year. A reply
that still cannot be read is reported as an error rather than thrown, which is what every caller
already expected. And the keyboard lookup specifically can no longer take a screen down: if the
options do not arrive, the screen keeps the standard ones and stays up.

This is not specific to this app. Any Light SDK tool that carries its own copy of the SDK and
draws a text field has the same crash, and the same three lines fix it.

**A two-line headline stops cutting off the feed name.**

Article rows are a fixed height because the list scrolls in whole rows, and that height was
written down as a number rather than measured. It was about seven display points short of what a
two-line title plus the line underneath actually needs, so any article whose headline wrapped
clipped the line naming the feed and the time — visible on almost every screen of Hacker News.
The row height is now derived from the type sizes themselves, which also fixes it on screens the
number was never tuned for. The Gmail label list had the same bug, worse, and got the same fix.

Rows are slightly taller, so a screen holds about six instead of six and a half. Nothing is
clipped.

Nothing about the database schema changed, so this installs over 2.6.1 and keeps every
subscription, label, read state and saved article.
