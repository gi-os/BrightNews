# News 2.5.0

The app is smaller and starts faster, and nothing about using it has changed. Release builds now
go through R8 in full mode: on top of the shrinking it already did, it merges classes, drops
arguments nothing reads, and assumes a class it never sees allocated is never instantiated. On a
phone that takes as long as this one does to bring a cold app to the first frame, that is the
part of the build worth tuning.

The risk with full mode is not subtle bugs, it is a class that goes missing because it was only
ever loaded by name. Three of those exist here and are now pinned explicitly: Room's generated
database and DAO implementations, which `Room.databaseBuilder` finds with `Class.forName`; the
ML Kit and CameraX stack behind the scan-to-subscribe screen, which was already pinned and stays
pinned; and jsoup's compile-only nullability annotations, which R8 treats as a fatal missing
reference rather than a warning. The RSS and Atom parser deliberately gets no rule — its SAX
handlers are allocated by our own code, and the parser factory resolves to a class inside the
Android runtime, not inside this APK. If something does turn out to have been shrunk away, it
will fail loudly on the screen that needs it rather than quietly corrupting anything, and your
subscriptions, read state and saved articles are untouched by any of this.

Two things planned for this release were dropped, and it is worth being straight about why.

News is a Light SDK tool, not a plain APK, and the SDK's build plugin decides what a tool may
depend on. `com.gios:light-common` is not on that list, so the shared wheel-handling code stays
where it is: the local copy under `hw/` is unchanged and still the one in the build. It would
not have been a clean swap anyway — the library's wheel bus expects an activity to feed it key
events, and a tool owns no activity. The wheel here is fed by the SDK's own
`LightHardwareKeys` hook, which the shared library knows nothing about.

The LightSync backup provider is not here either, and cannot be. LightSync finds an app through
an exported `ContentProvider`, and a tool has no way to declare one: the manifest is generated
from `lighttool.toml`, which has no provider field, and a hand-written manifest is rejected by
the build. The sandbox also blocks the imports the provider would need. So News is still not
backed up by LightSync. Until that changes, a phone wipe loses the subscription list, and the
only way to get it back is to add the feeds again.
