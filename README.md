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
- **Speed-oriented v0.5:** native llama.cpp /completion SSE streaming shows
  output as soon as tokens arrive, instead of waiting for the entire result.
  The default new model download is PFN's smartphone-oriented 0.8B Q4_0
  GGUF. Existing models are never replaced or downloaded automatically.
- **Performance tuning:** settings allow switching between 2 and 4 CPU threads,
  with the server restarting once. The default is 4; Pixel/Tensor performance
  must be benchmarked on the phone rather than guessed.
  Context length is reduced from 4096 to 3072 to reduce memory pressure.
- **GPU caveat:** llama.cpp Vulkan on Mali G710 (the Pixel 7 Pro GPU family)
  has upstream crash reports as of late 2026; this release retains a stable
  CPU backend instead of forcing an untested Vulkan GPU path.
- **Model residency:** an Android foreground service owns the llama.cpp server
  and keeps the loaded model in RAM between popups. Android may still stop the
  process under memory pressure or after a reboot. The notification and the
  settings menu offer an explicit stop-and-release action.

## First run / PLaMo models

1. Download **PLaMo-Select-v0.7.0-release.apk** from [Releases](../../releases)
   and install it.
2. On first launch, choose **Download** to fetch PFN's PLaMo 3 Translate 0.8B
   Q4_0 GGUF (~548 MB, official mobile-oriented quantization), or select an
   existing GGUF with the file picker.
3. Choose text from another app, or use the full-screen home screen.

The model is downloaded **only after explicit user consent**, from the official
[pfnet/plamo-3-translate-0.8b-GGUF](https://huggingface.co/pfnet/plamo-3-translate-0.8b-GGUF)
Hugging Face repository. It is not included in the APK, and is subject to the
[PLaMo Community License](https://plamo.preferredai.jp/).
The default 0.8B Q4_0 GGUF is optimized in some mobile llama.cpp kernels;
actual speed relative to Q4_K_M on Tensor G2 is **unmeasured**, and other
Android devices may behave differently. Progressively revealed output improves
perceived latency; it does not by itself increase tokens per second.
Elapsed time is shown in the translation result UI.

For PLaMo 3 the app uses the PFN-recommended PFML3 prompt and llama.cpp
/completion API. This reliably encodes the target language inside the prompt,
independently of GGUF chat template support. PLaMo 2's older
JA:/EN: prompt mode remains available for legacy GGUF files. Importing a
file whose original name contains "plamo-2" selects legacy mode automatically.
The selection popup is **result first**; translation output scrolls within
a bounded result card, and long original text lives in a short internally
scrollable input below it. A 466-character selection no longer obscures the
translation or copy/share actions.

PLaMo 3's model also supports an embedded Jinja chat template, but the direct
/completion path is used to avoid subtle template / options compatibility
issues. The native server remains configured with --jinja.

## Reproducible inference smoke test

The public on-demand
[translation-smoke workflow](.github/workflows/translation-smoke.yml)
downloads the official Q4_0 GGUF into an ephemeral GitHub runner and checks
English→Japanese, Japanese→English, auto-source detection and SSE streaming.
The native test validates protocol compatibility but is **not a Pixel 7 Pro
benchmark**: Android timing and thermal conditions differ.

## Long-article robustness and settings

v0.6 replaces the short, broken settings dialog with a complete full-screen
settings page. It provides model file management, CPU threads, context budget,
maximum generation tokens, per-segment character limit, auto-translate,
popup placement, result font size and optional source preview.

Unlike the previous "up to 1050 characters per request" implementation,
**all text is split by sentence, paragraph, and an enforced maximum length**.
PLaMo 3 Translate can terminate a PFML3 translation after one sentence even
when it receives multiple sentences as one request. The UI therefore translates
each segment in sequence and concatenates all results. An incomplete SSE stream
or token-limit stop is reported as an error, not a successful full translation.

The public smoke test compiles the production Java splitting class without an
Android emulator, then passes the extracted segments of a fictional multiline
article through the real PLaMo 3 GGUF using streaming. It verifies numerical
facts from the headline and body survive the translation.

## Smooth floating-result scrolling and device diagnosis (v0.7)

The floating blue translation result card is a `PriorityScrollView`. It
prioritizes the user's vertical swipe over the outer window's scroll when
there is more translated content in that direction. As soon as the inner
result reaches the edge, the outer window can scroll again. Platform Android
`requestDisallowInterceptTouchEvent` coordinates gesture ownership.

The floating preview does not enable text selection inside the nested scroller,
because a selectable `TextView` can intercept drags for selecting text.
Use the one-tap **Copy** action to copy the actual complete result, or
**全文を表示** to open a dedicated full-screen reader where scrolling and text
selection work independently of the popup. The status label displays the
complete translated character count, not just visible preview content.

Settings now include **デバイス診断・自動最適化**, which locally reports Android's
CPU thread count, memory, OpenGL ES version, and Vulkan capability. When the
user explicitly starts the benchmark, the service runs the *installed GGUF*
with 2 and 4 CPU threads, performs a warmup and two timed translations per
configuration, then applies the faster result. If results differ by less
than 10%, it prefers two threads to reduce unnecessary power draw. This is
only a short benchmark, not a validated prediction of performance under
extended thermal load. Models remain local and the normal memory-resident
server is restored after the diagnostic.

**Important:** Vulkan device support only identifies hardware/API capability.
The currently bundled official upstream Android llama.cpp binary is built
for **CPU inference**. No GPU benchmark, Vulkan layer offloading, or GPU
acceleration is enabled in v0.7; those require a separate GPU-enabled runtime
and device-specific compatibility tests.

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
