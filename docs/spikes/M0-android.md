# M0 Android spikes

Run on 2026-10-13 against the approved design (02 §16, M0 "Skeleton and spikes"). Two Android questions had to be answered before M5 is planned in detail: does offline search on the phone (Room, an FTS4 table over folded text, 10,000 notes) stay inside the 200 ms p95 budget of 01 §6.3, and does the platform's on-device recognizer give dictation what 02 §11.8 assumes (live partial results, punctuation, one continuous session, language detection, offline Italian and English packs). The spike code is one throwaway app in `spikes/android/` (gitignored); this file keeps the commands, the trimmed outputs, the verdicts and the checklist for the owner's phone.

| Spike | Verdict |
|---|---|
| 1. Room FTS4 with the fold function, 10,000 notes, p95 under 200 ms | **Passes in the earlier headless runs; not a blanket emulator pass**: p95 3.5 to 5.2 ms newest-first and 7.7 to 10.4 ms ranked by bm25, worst query 18 ms. An independent fresh-install rerun during verification gave p95 11.2 ms newest-first and 17.3 ms bm25, with one bm25 outlier at 218 ms, so the tail needs watching on the phone. The later visible-emulator rerun below passes newest-first but fails bm25 (p95 249.5 and 327.6 ms). A run on the owner's phone is in the checklist below, because the emulator runs on Apple silicon and flatters a Pixel 6a class phone |
| 2. Dictation probe | **Built, and half answered in an earlier emulator session.** That session had Google's on-device recognizer with English installed: partial results, punctuation and segmented (continuous) sessions all work, once the session length is passed as an `Int`. The later read-only baseline below has neither language installed and returns `ERROR_LANGUAGE_UNAVAILABLE` for English. Italian, language detection and the microphone in airplane mode still need the owner's phone |

The API and search approach remain feasible, but the visible rerun leaves ranked-search performance and installed speech packs unconfirmed. Four implementation details need writing down. FTS4 needs the `unicode61` tokenizer, because the default one treats the typographic apostrophe as a letter and loses every word after `l’` or `dall’`. Android's SQLite has no ranking function for FTS4, but bm25 computed in Kotlin from `matchinfo` costs a few milliseconds. Google's recognizer silently ignores a segmented session whose length is a `Long`. And its partial results and segments have a shape the `TranscriptBuffer` must be written for.

## Environment

- Host: macOS on Apple silicon. Docker 29.8.2 with buildx 0.37.2. The original build used no host Java, so Gradle ran in `spunto-build:latest`, built from Quire's Dockerfile unchanged (Temurin 21, SDK platform 35, build-tools 34.0.0) under linux/amd64 emulation.
- Toolchain, identical to Quire's: Gradle 8.10.2, AGP 8.6.1, Kotlin 2.0.20, KSP 2.0.20-1.0.25, Compose BOM 2025.07.00, Room 2.6.1, compileSdk 35, minSdk 26, targetSdk 34, JVM target 21.
- Emulator: Android Emulator 36.6.11, the existing `Pixel_10` AVD (Android 17, API 37, Google Play image with 16 KB pages, arm64), started headless with `-read-only` so Quire's test library on it is untouched. SQLite on it is 3.50.6.
- The APK is a debug build (`io.theficos.spunto.spike`, version `0.0.1-spike`), so ART runs it as debuggable; Kotlin-side costs such as the bm25 loop are, if anything, overstated.

## Visible emulator rerun (2026-10-13, 21:53–22:00 local time)

The current verification used the existing APK, SHA-256 `ba931c57f04678133d178ba7be8a30ac5c9b31c8bcf8f8315f25d4c9eeadab22`, unchanged. No emulator process was running when checked; `Pixel_10` was started visibly with `-read-only -no-snapshot-save -no-audio`. This preserves the AVD's existing Quire data. `adb reconnect offline` made its initially offline transport usable; `adb install -r` updated only `io.theficos.spunto.spike`. No apps were removed and no data was wiped. A host JDK is available at `/Applications/Android Studio.app/Contents/jbr/Contents/Home`; this rerun did not rebuild the APK.

**Search.** Two complete runs in one process used the same 10,000-note corpus, 26 queries, one warm-up pass and 20 samples per query/strategy (520 per strategy). All seven folding checks passed in both runs. SQLite was 3.50.6. The first run seeded in 12,359.8 ms, opened in 126.7 ms and reported an 18,756 KiB database plus a 12,003 KiB WAL. The second reused the seeded database.

| Run | Newest p95 / max (ms) | bm25 p95 / max (ms) | Verdict against 200 ms p95 |
|---|---|---|---|
| First, after cold boot | 134.0 / 2251.2 | 249.5 / 2238.0 | Newest passes; bm25 fails |
| Second, same process | 143.4 / 2972.5 | 327.6 / 1807.1 | Newest passes; bm25 fails |

The first query after open took 5.2 ms, then 313.0 ms in the second run. This visible emulator had substantial graphics/system load: a captured boot interval reported surfaceflinger at 75% CPU and the graphics composer at 70%. This is context, not proof that graphics caused the tail. The earlier headless timings remain valid evidence for those runs, but do not establish a general pass or the reference phone's performance. Measure on the owner's phone before choosing the ranked offline implementation; no search change is justified solely by this noisy run.

**Dictation.** The service exists, but this read-only baseline reported `installed on device: []`, with both Italian and English supported and neither installed or pending. The earlier session's installed-language state is absent from this baseline; read-only session changes do not persist. A locally synthesized Samantha clip (three English sentences separated by two seconds of silence, 10.831875 seconds, raw 16 kHz mono PCM) was injected through the existing real-time pipe. No microphone audio was recorded. English continuous recognition reached `onReadyForSpeech` at +833 ms, then `ERROR_LANGUAGE_UNAVAILABLE` at +1345 ms: zero partials, zero segments, empty transcript. Therefore this rerun does not reproduce the earlier positive English recognition result. It did not download packs, test airplane mode or claim an Italian/language-detection pass.

**Lifecycle boundary.** A second injected start followed immediately by Home logged Ready (+79 ms) and the language-unavailable terminal error (+231 ms), with no app-issued stop at the background transition. Returning to the app retained its probe report in the same process. This throwaway probe has no foreground-stop hook or audio-focus management; it is not the M5 `DictationSession`. The absent pack prevents assessment of an ongoing successful speech session across backgrounding. The foreground stop, lock, interruption, process-death recovery and partial preservation tests required by §11.8 still belong to M5. Neither the microphone path nor production lifecycle behavior is validated here.

**Local evidence.** Full logs and synthesized audio are in `/tmp/spunto-android/`: `emulator.log`, `benchmark-first.log`, `benchmark-two-runs.log`, `cpuinfo.txt`, `dictation.log`, `lifecycle.log`, `english.aiff`, `english.wav`, `english.pcm`. Screenshots: `search-complete.png` (completed search screen), `dictation-result.png` (missing-language outcome). These temporary files are local verification artifacts, not committed fixtures. The emulator remains open for the owner; because it is read-only, this session's installed spike and generated corpus do not persist after the emulator closes.

## The spike app

```text
spikes/android/
  Dockerfile, scripts/dgradle      Quire's build image; the script names spunto-build and spunto-* volumes
  gradle/libs.versions.toml        Quire's versions, trimmed to what the spike uses
  app/src/main/java/io/theficos/spunto/spike/
    search/Fold.kt                 fold(), the MATCH expression builder, titleOf()
    search/SearchDatabase.kt       Room entities (notes, notes_fts), DAO, database
    search/Corpus.kt               the seeded 10,000-note generator
    search/SearchBenchmark.kt      seeding, checks, timing, bm25 from matchinfo
    dictation/DictationProbe.kt    the recognizer probe
    MainActivity.kt, SpikeViewModel.kt, Report.kt
  spunto-spike.apk                 the signed debug APK for the owner's phone
```

One screen with two tabs, Search and Dictation, and a **Copy report** button that puts the device line, every benchmark run of this launch and every dictation event on the clipboard. Every report line also goes to logcat under the tag `SpuntoSpike`. Two intent extras exist for driving it from `adb`: `--ez run_benchmark true` runs the benchmark at launch, and `--es audio_clip <path>` makes dictation sessions read a raw 16 kHz mono PCM file instead of the microphone.

The APK declares only `RECORD_AUDIO` and `ACCESS_NETWORK_STATE`. It has no `INTERNET` permission, so nothing it does can leave the phone; language packs are downloaded by the system's speech service, not by the app.

```sh
cd spikes/android
scripts/dgradle --no-daemon :app:assembleDebug       # 5 min cold, about 1 min after that
cp app/build/outputs/apk/debug/app-debug.apk spunto-spike.apk
# apksigner verify --print-certs (run inside spunto-build): v2 scheme, "CN=Android Debug",
# the debug key kept in the spunto-android-cache volume
shasum -a 256 spunto-spike.apk
# ba931c57f04678133d178ba7be8a30ac5c9b31c8bcf8f8315f25d4c9eeadab22  (9.5 MB)
```

---

## 1. Search benchmark

**Setup.** What M5 would build, reduced to search:

- `notes(id INTEGER PRIMARY KEY, title, body, updated_at)` with an index on `updated_at`, kept because the real timeline has it and it could tempt the planner.
- `notes_fts`, a Room `@Fts4(tokenizer = unicode61)` entity whose `rowid` is the note's id and whose columns hold `fold(title)` and `fold(body)`. Both rows are written in one transaction.
- `fold` is 02 §4.3 in Kotlin. Kotlin has no casefold, so upper-then-lower case stands in for it; that is what turns `ß` into `ss` like Python's `str.casefold`:

  ```kotlin
  fun fold(text: String): String =
      Normalizer.normalize(text, Normalizer.Form.NFKD)
          .replace(Regex("\\p{Mn}+"), "")
          .uppercase(Locale.ROOT)
          .lowercase(Locale.ROOT)
  ```

- The query side folds what was typed, splits it on anything that is not a letter or digit, quotes every word or quoted phrase (so typed text can never be FTS syntax) and makes the last word a prefix: `riunione lunedì` becomes `"riunione" "lunedi*"`, and `l’idea` becomes the phrase `"l idea*"`.
- The corpus is generated from a fixed seed, so the phone and the emulator search identical notes: 10,000 notes, 60% Italian and 40% English, from one-line ideas to long journal entries, checklists, links, inline labels, accents and typographic apostrophes, 512 characters on average (the size 02 §4.2 budgets for). The first six notes are fixed probes for the folding checks, and four rare words appear in 7 to 10 notes each.

**Two ways to answer.** Android's SQLite has FTS4 but no `bm25()` for it, so the benchmark times both candidates for the offline result list (top 50):

- *newest*: one statement, `SELECT id, title, body FROM notes WHERE id IN (SELECT rowid FROM notes_fts WHERE notes_fts MATCH ?) ORDER BY updated_at DESC LIMIT 50`.
- *bm25*: `SELECT rowid, matchinfo(notes_fts, 'pcnalx') FROM notes_fts WHERE notes_fts MATCH ?`, Okapi bm25 computed in Kotlin with the server's column weights (title 2, body 1, 02 §4.4), the top 50 kept, then their rows fetched by id.

Each strategy is timed around the DAO call including reading the cursor: one untimed pass over all 26 queries to warm the JIT, then 20 timed runs per query. The first query of the process is timed separately, before any warm-up.

**Commands.**

```sh
~/Library/Android/sdk/emulator/emulator -avd Pixel_10 -no-window -read-only -no-boot-anim -no-snapshot-save -no-audio
adb install -r spikes/android/spunto-spike.apk
adb shell am start -n io.theficos.spunto.spike/.MainActivity --ez run_benchmark true
adb logcat -s SpuntoSpike:I       # until "== Search benchmark finished =="
```

**Output** (a settled emulator, database already seeded; the other two runs agree within a few milliseconds):

```text
device Google sdk_gphone16k_arm64 (emu64a16k), Android 17, API 37, arm64-v8a
database open: 12.4 ms
sqlite 3.50.6
database file: 18756 KiB (wal 0 KiB)
plan newest: SEARCH notes USING INTEGER PRIMARY KEY (rowid=?) | LIST SUBQUERY 1 | SCAN notes_fts VIRTUAL TABLE INDEX 4: | CREATE BLOOM FILTER | USE TEMP B-TREE FOR ORDER BY
plan bm25: SCAN notes_fts VIRTUAL TABLE INDEX 4:
check pass: "perche" finds "Perché no? Un giro in bici fino a Ostia domenica"
check pass: "citta" finds "CITTÀ INVISIBILI di Calvino: rileggere il capito"
check pass: "augustarello" finds "Un’ottima trattoria: dall’Augustarello a Testacc"
check pass: "strasse" finds "Straße der Pariser Kommune a Berlino: foto del m"
check pass: "finestra" finds "Sistemare la ﬁnestra del bagno (la legatura ﬁ vi"
check pass: "CAFFÈ" finds "Café de Flore? No, caffè al bar sotto casa con G"
check pass: "cafe" finds "Café de Flore? No, caffè al bar sotto casa con G"
contrast: with tokenize=simple, "augustarello" matches "dall’Augustarello" in 0 of 1 rows
first query after open: 16.5 ms
20 runs per query and strategy; times in ms; 'matches' is every row MATCH returns
query                  matches | newest p50 / p95  | bm25 p50 / p95
-- one common word
idea                      4506 |     2.4 /     3.1 |     5.2 /     7.0
progetto                  2335 |     1.7 /     2.2 |     2.6 /     3.0
meeting                   1909 |     1.6 /     1.9 |     2.3 /     2.8
server                    2393 |     1.7 /     2.0 |     2.6 /     3.0
-- one rare word
nasoni                       9 |     0.1 /     0.1 |     0.1 /     0.1
obtainium                    8 |     0.0 /     0.1 |     0.1 /     0.1
zettelkasten                 7 |     0.0 /     0.1 |     0.1 /     0.1
ornitorinco                 10 |     0.1 /     0.1 |     0.1 /     0.1
-- two words
idea app                  1897 |     1.9 /     2.1 |     2.7 /     3.4
riunione lunedi            631 |     1.1 /     1.5 |     1.1 /     1.3
deploy staging            1452 |     1.6 /     2.0 |     2.1 /     2.5
book chapter              1130 |     1.2 /     1.5 |     1.6 /     2.8
-- accents folded
perche                    1727 |     1.4 /     1.6 |     1.9 /     2.3
citta                     1088 |     1.2 /     1.7 |     1.4 /     2.4
CAFFÈ                     1847 |     1.6 /     1.8 |     2.1 /     2.5
piu                       2529 |     1.8 /     2.1 |     2.7 /     3.1
-- quoted phrase
"lista della spesa"        157 |     0.2 /     0.4 |     0.4 /     0.5
"pull request"            1141 |     1.3 /     1.7 |     1.3 /     1.6
"server di staging"       1466 |     1.7 /     2.3 |     1.9 /     2.2
-- typing, last word prefix
ri                        6535 |     4.5 /     5.9 |     9.9 /    11.7
riu                       2436 |     2.5 /     3.3 |     3.4 /     4.4
riun                      2436 |     2.4 /     3.2 |     3.2 /     5.1
riuni                     2436 |     2.9 /     4.0 |     4.0 /     5.5
de                        6576 |     4.7 /     5.5 |    10.0 /    12.5
dep                       1716 |     2.1 /     3.2 |     2.4 /     3.9
depl                      1716 |     2.0 /     2.9 |     2.5 /     3.2
ALL QUERIES                    |     1.7 /     4.5 |     2.3 /     9.5
verdict newest: p95 4.5 ms, max 6.2 ms -> PASS against 200 ms
verdict bm25: p95 9.5 ms, max 13.5 ms -> PASS against 200 ms
```

| Run | Conditions | newest p50 / p95 / max | bm25 p50 / p95 / max |
|---|---|---|---|
| 4, 5, 6 | Settled emulator, three launches | 1.7 to 2.4 / 4.5 to 5.2 / 13 ms | 2.3 to 3.0 / 9.5 to 10.4 / 18 ms |
| Fresh install | Seeded in the same launch, then one more run from the button | 1.6 / 3.5 to 3.7 / 8 ms | 2.0 / 7.7 to 7.8 / 16 ms |
| 1 | A minute after a cold boot, load average 15, and before the warm-up pass existed | 3.7 / 78.6 / 586 ms | 6.0 / 138.4 / 1,167 ms |

**What the numbers say.**

- *Folding works on both sides.* All seven checks pass: accents and case in the note or in the query, `ß` against `ss`, the `ﬁ` ligature a PDF leaves behind, and a word glued to a typographic apostrophe.
- *The tokenizer has to be `unicode61`.* With FTS4's default `simple` tokenizer, every character above U+007F counts as part of a word, so `dall’Augustarello` is indexed as one token and a search for `augustarello` finds nothing. Folding cannot fix this, because NFKD leaves `’`, `«` and `»` alone. Italian text pasted from the web or typed on iOS is full of `’`.
- *Ranking is affordable.* bm25 in Kotlin costs about twice the single statement, and its worst case (a two-letter prefix matching 65% of the notes) is 11 to 15 ms at p95. Two-letter prefixes are the heaviest queries for both strategies, and still far inside the budget.
- *The planner behaves.* With the `IN (SELECT rowid ...)` form, SQLite drives from the FTS index and sorts the matches; the `updated_at` index is not misused.
- *Cold costs.* Seeding (fold, title, both inserts) took 2.0 s on a settled emulator and 11.3 s right after boot. The database is 18.3 MiB for 10,000 notes. The first query of a process took 3 to 17 ms on a settled emulator, 80 ms just after the post-boot load dropped, and 408 ms in run 1, while that load was still competing for the disk and CPU.
- *Under load the tail grows by two orders of magnitude.* In run 1 the overall p95 still passed (79 and 138 ms), but bm25 on the typing queries reached 200.3 ms at p95. The emulator's CPU is much faster than a Pixel 6a class phone's, so the phone run decides; if it comes close to the budget, newest-first is the fallback and the result list can show the keyword hits before the ranking is done.

**Verdict:** Room FTS4 with the fold function meets the 200 ms target on 10,000 notes with about a 20x margin on the emulator, both newest-first and bm25-ranked. Confirm on the phone with the checklist.

---

## 2. Dictation probe

**What it does.** The Dictation tab drives `SpeechRecognizer.createOnDeviceSpeechRecognizer` the way 02 §11.8 plans to:

- Language chips `it-IT`, `en-US` and `Auto`. Auto (API 34+) starts in Italian with `EXTRA_ENABLE_LANGUAGE_DETECTION`, `EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES` and `EXTRA_ENABLE_LANGUAGE_SWITCH` (balanced) limited to Italian and English.
- A "Continuous" switch. On (API 33+), the intent carries `EXTRA_SEGMENTED_SESSION = EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS` with five minutes, so the session lasts until Stop and returns `onSegmentResults` at each pause. Off, it is one ordinary session.
- Every session also carries `EXTRA_PARTIAL_RESULTS`, `EXTRA_PREFER_OFFLINE` and `EXTRA_ENABLE_FORMATTING = quality` (punctuation).
- **Check** logs the device, airplane mode and internet, the permission, `isRecognitionAvailable`, every installed `RecognitionService`, the default recognizer setting, `isOnDeviceRecognitionAvailable`, and `checkRecognitionSupport` (installed, pending, supported on device, online) for both languages. **Download** calls `triggerModelDownload` with a progress listener.
- Each callback is logged with the wall clock and the milliseconds since Start: ready, beginning and end of speech, every partial, every segment, results with the number of alternatives and the confidence, language detection, events and errors by name. Rms and buffer callbacks arrive every few milliseconds, so they are counted and summarised per session, with the delay to the first partial. The transcript shows settled text in ink and the in-flight partial in grey.

**On the emulator.** The probe expected to find nothing, and found Google's recognizer:

```text
isRecognitionAvailable (any recognizer): true
recognition services: com.google.android.as/com.google.android.apps.miphone.aiai.app.AiAiSpeechRecognitionService, com.google.android.tts/com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService
default recognizer setting: com.google.android.tts/com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService
isOnDeviceRecognitionAvailable: true
it-IT on device: installed no, pending no, supported yes; online no
en-US on device: installed yes, pending no, supported no; online no
installed on device: [en-US]
pending download: []
supported on device: 30 languages; online: 0
```

The on-device service is Android System Intelligence (`com.google.android.as`), separate from the default recognizer, Speech Services by Google. Note that `supportedOnDeviceLanguages` lists what *could* be downloaded and leaves out what is already installed, so English reads "supported no".

What happened next, in order:

1. **With the emulator's audio on, starting a session froze the whole emulator** (`detected a hanging thread 'QEMU2 main loop'`, then a crash). Every later run used `-no-audio`, which feeds silence.
2. **Silence, English:** `onReadyForSpeech` after 185 to 233 ms, then `ERROR_NO_MATCH` five seconds later.
3. **Italian and Auto:** `ERROR_LANGUAGE_UNAVAILABLE` 81 ms after Start, followed 60 to 80 ms *later* by a stray `onReadyForSpeech`. Auto fails the same way because it starts from Italian.
4. **Downloading Italian** failed: `ERROR_CLIENT` three seconds after the first request, right after boot; a second request got no callback at all for nine minutes, then `ERROR_CLIENT`. Italian never appeared under pending. The service logged that it found the pack (`LP match found ... Italiano (Italia), version: 3056`), so this looks like the emulator's missing Google account or download policy rather than an API problem.
5. **Real speech, through a clip.** To get past silence, the probe can feed a clip through `EXTRA_AUDIO_SOURCE` (the route 02 §11.8 plans for its release test). The clip was made on the Mac with `say -v Samantha`, converted with `afconvert -d LEI16@16000 -c 1`, stripped of its WAV header and copied into the app's files with `adb exec-in run-as io.theficos.spunto.spike`. Three sentences with two seconds of silence between them:
   - *Handing over the file itself* made the service read 11 seconds of audio in half a second: six partials, then `ERROR_NO_MATCH` and no text. The clip has to arrive at the speed of speech, so the probe writes it into a pipe 100 ms at a time.
   - *Segmented session with the length as a `Long`*: partials for the first sentence, then `ERROR_CLIENT`. The service's own log explains it:

     ```text
     W Bundle  : Key android.speech.extras.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS expected Integer but value was a java.lang.Long.  The default value 0 was returned.
     E RecognitionServiceInten: EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS is not set with positive value; ignoring EXTRA_SEGMENTED_SESSION
     ```

   - *Segmented session with an `Int`* works (trimmed to every few partials):

     ```text
     +174ms   onReadyForSpeech
     +316ms   onBeginningOfSpeech
     +804ms   onPartialResults "Call."
     +1508ms  onPartialResults "Call Marco about the?"
     +2621ms  onPartialResults "Call Marco about the deploy before Friday."
     +3142ms  onEndOfSpeech
     +4964ms  onBeginningOfSpeech
     +7331ms  onPartialResults "Call Marco about the deploy before Friday. Why not try offline search first?"
     +7660ms  onSegmentResults "Call Marco about the deploy before Friday." (2 alternatives, confidence 0.0)
     +7661ms  onPartialResults " Why not try offline search first?"
     +11135ms onPartialResults " Why not try offline search first? Buy milk, eggs, and coffee?"
     +11143ms onSegmentResults " Why not try offline search first?" (2 alternatives, confidence 0.0)
     +11144ms onSegmentResults " Buy milk, eggs, and coffee." (2 alternatives, confidence 0.0)
     +11144ms onEndOfSegmentedSession
     session end: 34 partials (first 802ms after start, 486ms after speech began), 3 segments, 0 buffers, rms 80 callbacks, -1.9 to 9.9 dB
     ```

     Reproduced on a second launch with the same timings. The service logs `Offline recognizer - start listening` and `Initialize Soda [locale: en-US], [applicationDomain: AMBIENT_ONESHOT]`, so this is the on-device engine.
   - *An ordinary session* (Continuous off) did not end at the first pause: it ran to the end of the clip, with partials covering each sentence in turn, and its `onResults` came back **empty** ("0 alternatives").
6. **Once, the first session after a reinstall never started:** no callback at all for 25 seconds, and the system logged `RemoteSpeechRecognitionService: #stopListening called with no preceding #startListening`. The next Start in the same process worked, and a fresh launch afterwards worked first time.

**What the shape of the results means for `TranscriptBuffer` and `DictationSession`.**

- A partial covers everything since the last segment, not since the last pause, and a segment arrives late: the first sentence was committed only after the second had been spoken. Replacing the whole in-flight span on each partial, and committing on each segment, is exactly right.
- Segment text after the first starts with a space (" Why not try..."), so the buffer must trim and then add exactly one space; the probe's naive join produced double spaces.
- Partials carry a guessed trailing `.` or `?` that changes as the sentence grows ("about the?", "dep."); the segment's punctuation is the settled one. Partial punctuation can be shown, but never committed.
- Confidence is `0.0` on every result, with two alternatives. Nothing can be gated on confidence.
- The first partial came 486 to 615 ms after speech began, and partials then arrived every 100 to 200 ms: fast enough for words to appear while the owner speaks.
- A terminal error can be followed by a stray callback, and a start can be lost. The session must ignore callbacks after a terminal state, and treat "no `onReadyForSpeech` within a couple of seconds" as a failed start: recreate the recognizer and retry once.

**Not yet answered, and why:** Italian quality, language detection (`onLanguageDetection`), the microphone path (the clip path may differ from it) and airplane mode all need the owner's phone. The emulator has no Italian pack and no usable microphone.

**Verdict:** the API surface 02 §11.8 relies on exists and behaves on Google's on-device service, with the `Int` fix. The decisive check, Italian and English by voice in airplane mode, is the owner's checklist below.

---

## Owner checklist: the Android 16 phone

About ten minutes, in a quiet room, without closing the app in between (the report lives only as long as the app's process). Use the quick settings shade for airplane mode, so the app stays open.

1. **Install.** With USB debugging: `adb install -r spikes/android/spunto-spike.apk`. Without: send the file to the phone, open it, and allow the file app to install unknown apps when asked. Play Protect may warn about an app from an unknown developer: choose "More details", then "Install anyway". The app is called "Spunto spike" and has no internet permission.
2. **Search, twice.** Open the app, Search tab, **Run benchmark**. The first run builds the 10,000 notes (up to a minute) and then times 26 queries; wait for "Done". Tap **Run benchmark** once more for the warm figures. Both runs stay in the report.
3. **Go offline.** Airplane mode on, and make sure Wi-Fi and Bluetooth are off too (airplane mode can leave Wi-Fi on).
4. **Check.** Dictation tab, **Check**. Look for `airplane mode on, internet no`, `isOnDeviceRecognitionAvailable: true` and `installed yes` for both `it-IT` and `en-US`. If one says `installed no`, write that down; then, with airplane mode off, select that language, tap **Download**, wait for `success`, turn airplane mode back on and tap **Check** again.
5. **Italian, continuous.** Select `it-IT`, leave Continuous on, tap **Start** and allow the microphone ("While using the app"). Speak at a normal pace, pausing two or three seconds at each slash: "Idea per il weekend: mappa dei nasoni lungo il Tevere. / Perché non provarla domenica mattina? / Poi comprare latte, pane e caffè." Stay silent for about ten seconds, then say "Ultima frase, poi mi fermo." and tap **Stop**. Words should appear in grey while you speak.
6. **English, continuous.** Select `en-US`, **Start**: "Call Marco about the deploy before Friday. / Why not try offline search first? / Buy milk, eggs and coffee." Ten seconds of silence, "Last sentence, then I stop.", **Stop**.
7. **Mixed, in Italian.** `it-IT`, **Start**: "Fare il deploy del branch sul server di staging, poi aprire una pull request." **Stop**.
8. **Auto, if the chip is enabled.** `Auto`, **Start**: "Domani mattina chiamo Giulia per il progetto. / Then I need to review the search screen. / E poi comprare il pane." **Stop**.
9. **Italian, single session.** `it-IT`, switch Continuous off, **Start**, say one sentence ("Ricordarmi di pagare la bolletta della luce."), then wait in silence until it stops by itself. Do not tap Stop.
10. **Copy back.** Tap **Copy report** (top right) and paste the whole text back here. It holds the device line, both benchmark runs and every dictation event with timestamps. Also say, in a word, how the Italian and English text read compared with what you said.

---

## Proposed spec changes

None of these change a decision; they make 02 precise where the spikes found the edges. `docs/0*.md` are untouched.

1. **02 §4.3 and §11.2, the FTS4 table:** name the tokenizer: "FTS4 with `tokenize=unicode61` over folded text". The default `simple` tokenizer treats `’`, `«`, `»` and every other character above U+007F as part of a word, so `dall’Augustarello` cannot be found by `augustarello`. `unicode61` is built into FTS4 unless SQLite is compiled with `SQLITE_DISABLE_FTS3_UNICODE`, and it works on this API 37 image; confirm it once on an API 26 emulator when M5 starts.
2. **02 §4.3, the fold function:** say how each language implements casefold and what counts as a combining mark, and pin both in `spec/fixtures` with `ß`, the `ﬁ` ligature, upper-case accented letters (`CITTÀ`) and a typographic apostrophe. Kotlin has no casefold (upper then lower case matches Python for `ß`); Python's `unicodedata.combining` (non-zero combining class) and Kotlin's `\p{Mn}` agree on Latin text but not on every script.
3. **02 §4.4 and §11.2, offline query and ranking:** state that Android cannot use FTS5's `bm25()`, and that offline ranking is bm25 computed in Kotlin from `matchinfo(notes_fts, 'pcnalx')` with the server's column weights (the worst query under 16 ms at p95 with 10,000 notes on the emulator), written as `id IN (SELECT rowid FROM notes_fts WHERE notes_fts MATCH ?)`. The query parser splits words exactly where the tokenizer does (`l’idea` is the phrase `l idea`), quotes every word and phrase, and makes the last word a prefix.
4. **02 §11.8, `PlatformSpeechEngine`:** "`EXTRA_SEGMENTED_SESSION` set to `EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS`, whose value is an `Int` (Google's service reads a `Long` as 0 and silently drops the segmented session)". Map each `onSegmentResults` to `Final`, and `onEndOfSegmentedSession` to `End`.
5. **02 §11.8, `TranscriptBuffer`:** a partial is everything since the last segment and replaces the whole in-flight span; segments can arrive one sentence late and may start with a space, so trim and insert exactly one space; a partial's trailing punctuation is provisional and only a segment's is committed; confidence is always 0.0, so nothing is gated on it. In a single session the final `onResults` can be empty, so "Save waits up to 1.5 s for the last final result" must fall back to committing the visible partial. Add these cases to the buffer's fixtures.
6. **02 §11.8, `DictationSession`:** ignore every callback after a terminal state (a stray `onReadyForSpeech` follows `ERROR_LANGUAGE_UNAVAILABLE`); treat "no `onReadyForSpeech` within 2 seconds of Start" as a failed start, recreate the recognizer and retry once.
7. **02 §11.8, languages and downloads:** decide availability from `installedOnDeviceLanguages` first: `supportedOnDeviceLanguages` leaves out installed languages, so "supported" alone reads as "missing" for a language that is ready. `triggerModelDownload` can fail with `ERROR_CLIENT` or stay silent for minutes, so the download offer needs a timeout and a fresh `checkRecognitionSupport` instead of trusting the listener; add a copy line for a failed download to the error table.
8. **02 §11.8, Testing:** clips sent through `EXTRA_AUDIO_SOURCE` must be fed through a pipe at the speed of speech; a file descriptor is read at once and returns no text. The local Pixel emulator (Google Play image, API 37) has the on-device recognizer with English installed, so the English half of the clip test can run on it before a release; CI stays on the fake engine as planned. Run emulators headless with `-no-audio` on macOS: with audio on, starting a recognition session froze this one.
9. **02 §11.1 or §6 (first sync), for the record:** folding and indexing 10,000 notes took 2.0 s on a settled emulator and 11.3 s under post-boot load, and the local database is 18.3 MiB. Both fit the 30-second first sync of 01 §6; re-measure on the reference phone in M5.
