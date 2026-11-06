# PLaMo Select — オフライン翻訳 for Android

An Android translation app inspired by the streamlined, card-based interaction
patterns of modern translation tools. It is **independent from Google Translate**:
it uses the locally installed PLaMo model and the official llama.cpp Android
runtime. Neither the Google Translate app nor Gboard needs to be modified.

## Screens and interaction

- **Full-screen app launcher:** clean, accessible Material-inspired translation
  workspace with language choices above distinct source/result cards.
- **Text selection popup:** select text in another app → Android's text-selection
  menu → PLaMo 翻訳. A compact dialog appears over the original app and starts
  translation automatically.
- **Source language:** auto detection, explicit English, or explicit Japanese.
  Set the target language or swap the direction. When both texts exist, swap
  can instantly exchange them without another model inference.
- **Quick actions:** copy the translation, share, hear it using a locally
  installed offline Android TTS voice, or replace the selected source text
  when the originating app supports editable ACTION_PROCESS_TEXT.
- **Typing:** wait 750 ms after editing before sending one model request.
  Previous in-flight responses are superseded so old translations cannot
  replace new input.
- **Clipboard:** use the explicit Paste action (no clipboard surveillance).
- **Offline-first:** every inference call connects to 127.0.0.1 only.
- **Model residency:** an Android foreground service owns the llama.cpp server
  and keeps the loaded model in RAM between popups. Android may still stop the
  process under memory pressure or after a reboot. The notification and the
  settings menu offer an explicit stop-and-release action.

## First run / PLaMo models

1. Download **PLaMo-Select-v0.3.0-release.apk** from [Releases](../../releases)
   and install it.
2. On first launch, choose **Download** to fetch PFN's PLaMo 3 Translate 0.8B
   Q4_K_M GGUF (~570 MB), or select an existing GGUF with the file picker.
3. Choose text from another app, or use the full-screen home screen.

The model is downloaded **only after explicit user consent**, from the official
[pfnet/plamo-3-translate-0.8b-GGUF](https://huggingface.co/pfnet/plamo-3-translate-0.8b-GGUF)
Hugging Face repository. It is not included in the APK, and is subject to the
[PLaMo Community License](https://plamo.preferredai.jp/).
The default 0.8B GGUF is suitable for testing on modern Android phones; actual
performance, memory consumption and compatibility are device dependent.

Under settings, select PLaMo 3 for a GGUF with the newer embedded Jinja chat
template and chat_template_kwargs target_lang settings. PLaMo 2's older
JA:/EN: prompt mode remains available for legacy GGUF files. Importing a
file whose original name contains "plamo-2" selects legacy mode automatically.
For PLaMo 3, the native server is launched with --jinja.

## Android build

The public [GitHub Actions workflow](.github/workflows/android-release.yml)
uses Java 17, Gradle 9.3.1 and Android SDK 36 to compile the release app. It
downloads the official upstream [llama.cpp](https://github.com/ggml-org/llama.cpp)
Android ARM64 runtime at tag b12138 and verifies its SHA-256 before packaging.
The workflow verifies the signed APK with zipalign and apksigner and publishes
it as an Actions artifact. Manually dispatched runs also publish a public
GitHub Release download.

### Signing and upgrades

GitHub secrets PLAMO_KEYSTORE_B64 and PLAMO_STORE_PASSWORD enable persistent
release signing. Without them, CI creates an **ephemeral non-debug release
certificate**. This is a signed release build but cannot guarantee in-place
upgrades from versions signed by different ephemeral keys. Keep a separate
copy of GGUF files before uninstalling an old version, as Android deletes app
private storage upon uninstall.

## Permissions and privacy

- INTERNET: only for the user-approved model download and loopback server.
- FOREGROUND_SERVICE / FOREGROUND_SERVICE_SPECIAL_USE: optional
  user-visible model residency that improves subsequent translation latency.
- No overlay permission, clipboard monitoring, analytics, or cloud translation.
- Android offline text-to-speech is used only when an offline voice exists.
- The PLaMo weights are third-party assets under PFN's own license;
  the llama.cpp native runtime is MIT-licensed (see
  [LICENSE](android-app/LLAMA_CPP_LICENSE.txt)).

The app is independent of Google and does not use Google Translate branding,
APIs, or translation data.
