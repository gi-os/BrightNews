# News 3.8.0

**A failure reports itself now.** Until this release the only failures that reached the
tracker were the ones somebody was annoyed enough to shake the phone about, and a feed that
quietly stopped refreshing was never one of them: the row said "1 feed could not refresh" for
a moment and nothing was written anywhere.

Every catch block in the app already ended in one function, `friendlyMessage`, which turns an
exception into a sentence. That function now also files the failure with light-common, which
raises the SEND ERROR? chip on its own. Each site says what it was doing — add a feed, load the
article, load Kagi's category list, subscribe to a Gmail label, reach Gmail — and the report
carries the exception's class and message. A refresh with failures files one report for the
run rather than one per feed, because twenty feeds failing on a dead connection are one fact.
The same failure asks once an hour, not on every tap.

No schema change. Installs over 3.7.0 and keeps everything.
