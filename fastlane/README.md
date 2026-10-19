# Android store metadata

`metadata/android/` follows F-Droid's supported [Fastlane source-repository layout](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/). It contains English and Italian descriptions and version-code-specific changelogs, including `14.txt` for public release `2026.10.19.3`. Italian text describes the app; it does not imply that the Android interface is translated. The descriptions link the public privacy policy. F-Droid submission and external review remain pending; this metadata is not an accepted listing.

The phone screenshots are unmodified PNG captures of an internal Android debug build on an emulator, in the Ardesia dark theme. Five fictional offline notes imagine Darth Vader using Spunto for work, family and everyday life. The isolated `io.theficos.spunto.debug` replica is signed out and contains only those notes; no owner notes, account, server or credentials are used. `en-US` and `it-IT` share identical English-interface screenshots. The 512-pixel icon is rendered by Android from the app's adaptive launcher drawable.

The canonical captures are `images/phoneScreenshots/1.png` (timeline with settings), `2.png` (`#imperial` search), `3.png` (Death Star note detail) and `4.png` (expanded Labels and views). The repository README links directly to the `en-US` files. These illustrate the core note screens with fictional sample content; they do not depict every feature in the current release.

To refresh them:

1. Use a dedicated disposable Android emulator named `SpuntoVaderScreenshots` and explicitly select its serial. Install the debug app and its matching AndroidTest APK. The fixture requires an emulator (`ro.kernel.qemu=1`), a signed-out replica, and empty notes and outbox before seeding. Never clear or uninstall a retained app to make this fixture pass.
2. Run only the screenshot test with its explicit opt-in argument. For example, replace the serial below with the chosen emulator's serial:

   ```sh
   screenshot_serial=emulator-5588
   adb -s "$screenshot_serial" shell am instrument -w \
     -e class io.theficos.spunto.DistributionScreenshotsTest \
     -e spunto.distribution.screenshots SYNTHETIC-DEBUG-ONLY \
     io.theficos.spunto.debug.test/androidx.test.runner.AndroidJUnitRunner
   ```

3. Export `cache/distribution-screenshots/{1.png,2.png,3.png,4.png,icon.png}` with `run-as`; for example, `adb -s "$screenshot_serial" exec-out run-as io.theficos.spunto.debug cat cache/distribution-screenshots/1.png > 1.png`. The fixture waits for each screen, dismisses the keyboard, opens the note through its Open action and expands the labels sheet before scrolling. It checks the foreground package before capturing. These images need no manual recapture.
4. Inspect all four screenshots and the icon, then copy the same original PNG bytes into both locales: screenshots to `images/phoneScreenshots/` and the icon to `images/icon.png`.

This prepares source metadata only. The accepted PNG bytes remain unchanged and bound to their original internal debug build; they do not establish a signed release's artifact identity or physical-phone acceptance. F-Droid’s build recipe, submission and acceptance are separate steps. Nothing here claims the app is available in F-Droid.
