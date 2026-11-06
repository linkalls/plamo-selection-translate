# PLaMo Select Translation (Android)

An offline Japanese / English text-selection translator for Android.
Use it with **Gboard unchanged**: select text in an app, open the selection's
three-dot menu and choose **PLaMo Select翻訳**.

## Features

- Android ACTION_PROCESS_TEXT integration for highlighted text.
- Japanese / English translation, automatic direction detection and manual override.
- Chunking for long text, offline llama.cpp inference, copy and replace.
- The PLaMo GGUF model is **not** shipped in the APK. Import your own GGUF from the Android file picker.
- The inference server binds to loopback (127.0.0.1) only.

## Install

Download **PLaMo-Select-v0.1.0-release.apk** from the public
[Releases](../../releases) page (not a debug build).
Install the APK, open it and import a compatible PLaMo GGUF file.
Then select text in another app and use Android's text processing action.

> This is an initial release. A valid signature and CI build do not
> guarantee successful inference on every device/model.

## Reproducible public CI build

[Android release workflow](.github/workflows/android-release.yml) runs on Ubuntu GitHub
Actions, installs Java 17, Gradle 9.3.1 and Android 36 build tools,
downloads the official Android ARM64 release of
[ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) at tag b12138,
checks the archive SHA-256 digest, and builds assembleRelease.

The signed APK is published as an Actions artifact after zipalign and
apksigner verification. Manually dispatching the workflow also publishes a
public GitHub Release asset.

## Release signing

Signing credentials are private GitHub Actions repository secrets:
PLAMO_KEYSTORE_B64 and PLAMO_STORE_PASSWORD.
If neither secret is available, CI uses a one-time generated key; Android updates may then require uninstall/reinstall. The same permanent certificate must be retained for in-place upgrades.
Never put keys, passwords, private models, or credentials in Git.

## Privacy and third-party notices

The app does not call any cloud translation endpoint. The public source
repository does not include model weights.
The bundled native runtime is the official upstream llama.cpp release
distributed under its MIT license: see
[llama.cpp license](android-app/LLAMA_CPP_LICENSE.txt).

Publication of the app source does not automatically grant a license for
reuse beyond rights already stated for third-party components.
