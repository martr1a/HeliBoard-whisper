# HeliBoard Whisper

A fork of [HeliBoard](https://github.com/HeliBorg/HeliBoard) where the toolbar mic key records
and transcribes through a self-hosted, OpenAI-compatible Whisper server, instead of switching to
another voice keyboard. Switching keyboards loses the text field in some apps
([HeliBoard #1772](https://github.com/HeliBorg/HeliBoard/issues/1772)); this build never switches.

## Use

- Tap mic: recording starts (mic turns red). Tap again, or stop talking for 3 s: the audio is
  sent (mic turns orange) and the text is typed into the field.
- Long-press mic: switch to the next voice language (default list `auto, et, en`). The selected
  language is written on the mic key; `auto` (Whisper detects the language) shows no label.
- Every recording is saved as a file before upload and deleted only when the text has arrived
  (3 tries). If the server does not answer, the mic turns purple: tap it to send again.
  If the keyboard was closed meanwhile, the text goes to the clipboard.
- Audio is sent as AAC/m4a (~0.25 MB per minute), WAV if encoding fails or compression is off.
- Optional "Tekst osade kaupa" (live): each piece is sent at a short pause while you talk.
- First tap asks for the microphone permission.
- Settings: **Whisper**, the first entry in the keyboard settings — pause before sending (default 3 s), language list,
  server address. Changes apply to the next recording, no rebuild needed.

## Build

The `whisper` build type is the release build plus Whisper; it installs as
`helium314.keyboard.whisper` next to the normal HeliBoard.

```bash
export JAVA_HOME=/bulk/android/jdk ANDROID_HOME=/bulk/android/sdk
export WHISPER_KEYSTORE_PASSWORD=$(cat ~/.config/heliboard-whisper/password)
# optional: WHISPER_URL=https://host/v1/audio/transcriptions (default whisper.example.org)
./gradlew assembleWhisper
# -> app/build/outputs/apk/whisper/HeliBoard_<version>-whisper.apk
```

Keystore: `~/.config/heliboard-whisper/release.jks` (alias `heliboard-whisper`), password in the
same folder. Keep both: an APK signed with another key cannot update the installed one.

## Code

All Whisper code is in `app/src/main/java/helium314/keyboard/latin/whisper/` and
`app/src/whisper/`. Upstream files touched (keep this list short, for rebases):

- `LatinIME.java` — mic key goes to `WhisperVoice` when `BuildConfig.WHISPER_URL` is set;
  recording is cancelled when the keyboard closes
- `ToolbarUtils.kt` — long-press of the mic key
- `AndroidManifest.xml` — permission activity
- `app/build.gradle.kts` — `whisper` build type, signing, BuildConfig fields

Other build types have an empty `WHISPER_URL` and behave like upstream HeliBoard.

## Updating to a new HeliBoard release

```bash
git fetch upstream --tags
git rebase --onto vX.Y <old-base-tag> whisper
```
