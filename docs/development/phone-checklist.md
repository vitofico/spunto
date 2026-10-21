# Owner phone checks

Use the [full-product delivery record](full-product.md), the supplied APK version and its deployment confirmation to select the build being checked. Confirm the actually delivered APK/server versions before starting; local build evidence alone does not establish a new public deployment or release. Use invented notes and spoken test phrases. Record the APK version, phone model, Android version, keyboard, recognizer and any failure before changing settings. These checks supplement the emulator and do not turn manual impressions into measured performance acceptance.

The [2026-10-15 Motorola run](phone-2026-10-15.md) records 47 historical debug APK6 automatic cases, earlier signed APK5-to-APK6 checks and partial signed APK7 evidence: retained synthetic state and app lock across the signed update, locked privacy, cancel-unlock and private PIN unlock followed by exact draft verification. Fingerprint unlock on APK7 is owner-reported; its automated unlocked-view check is unverified. Canceling disable kept the actual switch On; subsequent successful disable authentication was reported by the owner, with automatic Off verification incomplete. Full phone acceptance and the remaining gates below are pending.

After intermittent USB loss, including after a cable change, the owner manually confirmed **All three are Off**. Final narrow cleanup freshly bound exact APK7, verified original global stay-awake 0 and removed both task XML files without reading them, with absence verified. App lock Off and the phone theme override Off remain owner-confirmed only; automatic app-state and post-cleanup restart/note/draft verification were not completed. The earlier APK6 cleanup remains historical evidence. No live speech was started or radios changed; remaining checks are paused.

## Access and offline work

- Install the supplied APK as an update. Existing notes, drafts, device preferences and sign-in must survive.
- Sign in or pair with the server's public HTTPS URL. On mobile data, create a synthetic note, edit it and find it from the web app. The browser must show a valid HTTPS connection.
- In airplane mode, open saved notes, search a keyword, edit an existing note and save a new note. Force-stop and reopen Spunto. Saved text and an unfinished draft must still be present.
- Reconnect. Verify the queued changes appear once on the web, with no lost newer typing. Repeat with a private note and check that it produces no AI proposal.

- From web Settings → Devices, create a fresh five-minute one-use grant and scan or paste its QR JSON; try paste after refusing camera permission. Revoke only this synthetic test phone, verify that local words/drafts/outbox remain, then re-pair to the same HTTPS root and confirm queued work appears once.
- In Plugins, verify declared Access before enabling, compare actual On/Off/Waiting after reload/sync, and confirm environment-locked switches stay locked. For a prepared synthetic Removed operator card, check its truthful origin/Off/lock/counts and web owner-sign-in cleanup link; never use an owner plugin's stored data for destructive testing. Native server-settings/label-rewrite/cleanup actions must use the same-root web owner session, not a device-token URL.

## Optional phone unlock

These are pending physical-device checks, not a phone acceptance result. Use the phone's existing secure screen lock and enrolled biometrics; do not change its PIN or erase app data for testing.

- Confirm **Settings → Devices → Unlock with biometrics or screen lock** starts off. Enable it and cancel the system prompt: it must remain off. Complete system authentication to enable it. Disabling must also require successful system authentication.
- Background Spunto and return. Before unlocking, notes, search results and drafts must be hidden. Fail a fingerprint attempt and cancel the prompt; the app must stay locked. Unlock with an enrolled strong biometric, then repeat with **Use screen lock** and the phone's PIN, pattern or password. If the device has no supported strong biometric, record that limitation and verify the screen-lock path.
- Switch the screen off during use, then unlock the phone and return to Spunto. It must still require its own unlock. Repeat after force-stop/process restart and after Home while a biometric prompt is pending; a late result must not reveal notes without a current successful challenge. Rotate during a prompt and after unlocking; record duplicate prompts or a stuck lock.
- Leave synthetic unsaved text in the main editor and in share capture. Background or switch the screen off, then unlock: the same draft must remain with no duplicate saved note. Check that the locked screen and recent-apps preview do not display that text.
- While locked, send invented text and an image through Android Share and invoke Spunto from selected text in another app. Nothing should be written before unlock. Cancel, reopen and unlock; a selection must create at most one note, and an unfinished share must retain its draft through another lock/unlock cycle.
- With the switch enabled, verify the widget hides note previews even while Spunto is unlocked; quick capture must lead through the guarded screen. Confirm offline notes/drafts survive and background synchronization still works. A revoked synthetic device token must still require sign-in or pairing; fingerprint/PIN unlock alone must not restore server access.

## Speech and retained recordings

- Test ordinary Dictate in airplane mode with the phone's offline English and Italian language packs. Speak an invented phrase, correct a word while dictating, pause, then stop. Only text should be saved; there must be no recording attachment or online fallback. If no offline recognizer is available, record that limitation.
- With Voice memos off, Record must be unavailable. After switching it on and syncing its permission grant, verify the explicit Record action asks for microphone permission. A missing transcription model may show Waiting while still allowing recording-only capture, but only after all declared grants are approved and the updated catalog is cached.
- Record an invented 30-second phrase, stop, then explicitly Save. Verify a duration-labelled attachment, preserved typed text and local playback in airplane mode. Recording-only mode is expected until a compatible same-PCM recognizer is verified; report the displayed mode rather than assuming a live transcript.
- Start another clip and add text before and after any live speech fragment. Edit a live guess directly. New recognition must preserve your corrections. Background the app and interrupt it with another audio app; the microphone indicator must stop and the clip or interruption message must remain visible.
- Stop a clip but leave it unsaved, force-stop, then reopen. Verify the recovery choice and preserved words. Recovery must not start the microphone or automatically attach/upload the clip. Test Discard with another synthetic clip.
- Save a clip offline, reconnect and verify one linked recording with authenticated download on the web and playback on the phone. Switch Voice memos off: existing recordings must remain playable. Server transcription, when configured, must be a diff to accept or reject rather than a silent text replacement.

## Appearance, input and accessibility

- Try Carta, Ardesia, Salvia, Corallo and Elettrica in light and dark modes. Select an operator-installed pack if available, restart offline and verify its cached colours. An invalid pack must show an unavailable reason.
- Change reading face and S/M/L size. Verify note body text changes while controls, code and machine-note typography stay consistent. Reset the device theme and verify that reading preferences remain.
- With the installed keyboard, type, select, replace and paste text around headings, code and labels. Verify selection, composition and autosave preserve the final text. Repeat with Gboard and SwiftKey if both are available.
- At 200% font size and with TalkBack, check capture, search, note actions, plugin permissions, recording controls and playback. Record clipped controls, missing names or focus traps.
- Exercise a real text share, image share, camera capture, launcher shortcut, Quick Settings tile and widget using synthetic content. Confirm cancelling a capture leaves no unintended note.

## Performance evidence

Record perceived slow paths for cold opening to a usable keyboard, warm capture, share capture, Save-to-card and offline keyword search. Numeric acceptance still needs the reference-phone harness: cold-to-typing ≤1 second (p95 ≤1.5 seconds), warm/share ≤300 ms, Save commit and card acknowledgement ≤50 ms, local search p95 ≤200 ms and first 10,000-note Wi-Fi sync ≤30 seconds. Battery and speech-quality targets also need physical-device measurements; emulator timings and manual stopwatch checks do not certify them.
