# Privacy

Spunto is an open-source notes app that can run on your own server. This policy
describes the app and its built-in features. Your server operator, sign-in
provider and any optional services have their own policies.

## Your notes and account

The Android app stores notes and drafts on the device. When you connect a server,
it sends changes to that server and keeps a local copy for offline use. The web
app loads notes from the server and stores settings and unfinished note drafts,
including edits to existing and shared notes, in the browser. Signing in uses
session cookies; paired devices use access tokens.
Servers store the notes, attachments, labels, account information and other data
needed to provide the features you use.

Spunto has no built-in advertising, analytics SDK or usage-reporting service.
It does not require an account with the Spunto project to take local Android
notes or run your own server. Server access logs, hosting infrastructure and
optional integrations are controlled by your operator.

## Optional services

- **Sync and collaboration:** connecting a server transfers your library changes
  to that server. Sharing a note or joining a shared space makes the selected
  content available to the people granted access, according to their role.
- **AI:** enabled AI features send the content needed for the requested task to
  the configured provider. That provider may run locally or use a cloud service.
  Notes marked private are excluded from built-in AI processing. Check your
  operator's configuration before enabling AI for sensitive content.
- **Sign-in:** optional SSO redirects you to the configured identity provider.
  That provider handles its own credentials and supplies the identity claims
  needed to sign in. Local password accounts are also supported by multi-user
  servers.
- **Agent access and plugins:** agents act with the permissions granted to their
  token. Access to private notes requires explicit permission. Operator-installed
  plugins and external integrations may process data or contact other services;
  review them before enabling them.
- **Exports and links:** sharing or exporting content sends it to the destination
  you choose. Opening an external link contacts that website or app.

## Android permissions

The camera is used when you choose to scan a device-pairing QR code. Frames are
decoded on the phone and are not saved or uploaded by the scanner. You can paste
a pairing code instead.

Microphone access is requested for voice features. Dictation prefers Android's
on-device recognizer; offline availability depends on the installed service and
languages. When on-device recognition is unavailable, online recognition
requires explicit consent on that phone and may send audio to the installed
speech provider, according to its settings and policy. Dictation itself keeps
no recording. Audio deliberately attached through Voice memos becomes part of
the note and can sync to your server.

Optional app lock uses Android's biometric or device-credential prompt. Spunto
does not receive your fingerprint data or screen-lock credential. Device tokens
are encrypted using Android Keystore. App lock protects access through the UI;
it is not encryption of the notes database. Spunto does not provide end-to-end
encryption. Use HTTPS when accessing a server over a network.

## Retention and control

You can edit, export and delete your notes using the app. Trash retention,
backups, account administration and server storage are controlled by your
operator. Deleted content may remain in backups until those backups expire.
Copies already exported or shared with another person cannot be recalled from
their devices by deleting your own copy.

For questions about a hosted account or its data, contact that server's operator.
For software issues, use the [issue tracker](https://github.com/vitofico/spunto/issues)
without including personal notes or credentials. Report security problems
privately using the [security policy](SECURITY.md).
