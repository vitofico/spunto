# First-run guide

The owner requested a guided tutorial on first opening Spunto. The guide is a
core client feature, with four short steps: capture a thought, connect it with
labels, find it again, and use the server with Android. It uses the existing
themes and clearly marked examples without creating notes or drafts.

## Entry and completion

- Android shows the guide on an ordinary main-screen launch, after any app lock.
  Incoming shares, dictation, quick capture and links to individual notes keep
  their normal paths. They leave the guide available for a later ordinary launch.
- Web shows the guide after authentication on All notes. Setup, sign-in, SSO
  callbacks and links to individual notes take priority.
- Completion and Skip record a small version-one marker locally, per Android
  installation or browser. The marker does not follow an account or space.
  An existing installation without the marker sees the guide once on its next
  eligible entry. Changing accounts, pairing a server or updating the app does
  not reset a completed guide.
- Back and Next move between steps. Skip closes the guide immediately. The final
  action opens All notes; Android may also offer an explicit server connection.
- Settings offers Show guide. Skipping a replay returns to Settings. Replaying
  does not erase completion or change any notes, drafts or account settings.

## Interaction and privacy

The guide appears before the editor mounts, so it does not steal typing or leave
the keyboard over its actions. Its content scrolls on compact screens and with
enlarged text; navigation actions remain reachable. Focus, Escape and Android
Back have explicit behavior. It requests no permissions, submits no notes,
performs no model calls and records no analytics.

The copy distinguishes Android's offline replica from the web client's server
connection. AI search is described as optional and dependent on server setup.
No account or server connection is required to start writing locally on Android.

The plugin restrictions in the original design remain in force: plugins gain no
onboarding slot or ability to open a tour. Historical design documents are
unchanged.

## Validation

First-run tests cover Skip, completion, persistence, replay and deferred entry.
Other client fixtures explicitly represent returning users. Client checks also
cover compact layouts, enlarged text, focus and existing capture/navigation.
Startup and profile fixtures prepare a returning user through the guide before
measurement begins, keeping tutorial work outside existing startup metrics.
