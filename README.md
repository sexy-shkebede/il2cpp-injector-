# UnityInjector

An Android app (APK) that automates injecting a native `.so` library into a
Unity game APK **directly on-device**. Built for game **modding** — translations,
single-player mods, accessibility patches, reverse-engineering games you own.

It tries several injection techniques and can run them all automatically
("Auto / try everything") so you don't have to care which one a given build needs.

---

## ⚖️ Responsible use

This tool repackages an APK you provide with a native library you provide.
Use it only for things you're allowed to do:

- Mod games **you own**, for **single-player / offline** use.
- Reverse engineering, learning, security research on your own apps.
- **Don't** use it to cheat against other players in online games, to bypass
  anti-cheat, or to redistribute modified copies of someone else's app.
  That typically violates the game's Terms of Service and may be illegal.

The app does not generate any payload — the `.so` is whatever *you* pick.

---

## What it does

Given:
- a **target Unity APK**, and
- a **native library** (`.so`) + target ABI (`arm64-v8a`, `armeabi-v7a`, ...),

it produces a new, **signed** APK with your library injected so it loads when the
game starts.

### Injection methods

| Method | How it works | Needs | Unity version |
|---|---|---|---|
| **DEX / smali** | Inserts `System.loadLibrary("name")` into the Unity activity (`<clinit>`). Detects `UnityPlayerActivity`, `UnityPlayerGameActivity` (Unity 2023+/Unity 6), or the custom launcher activity. | `dexlib2` | all |
| **ELF `DT_NEEDED`** | Adds a `DT_NEEDED` dependency to `libil2cpp.so` / `libunity.so` / `libmain.so`, so the linker auto-loads your lib. No smali changes. | — | all (version-agnostic) |
| **Lib only** | Just drops the `.so` into `lib/<abi>/`. Pair with one of the above, or for libs loaded by name from the game itself. | — | all |
| **Auto** | Runs the applicable methods in order of reliability until one succeeds. | — | all |

After patching, the APK is re-signed with a self-signed key generated on first run
(stored in the app's private files). You must uninstall the original app before
installing the patched one (different signature).

---

## Why the Unity activity name matters

Older Unity exports extend `com.unity3d.player.UnityPlayerActivity`. Newer ones
(Unity 2023.x / Unity 6 with the "Game Activity" application entry) use
`com.unity3d.player.UnityPlayerGameActivity`. Some games subclass either one with
their own activity. The DEX method detects all three cases:

1. The launcher activity declared in the manifest, else
2. any class extending a known Unity activity, else
3. the known Unity activity classes directly.

---

## Build

Open in Android Studio (Giraffe+). Or CLI:

```bash
./gradlew :app:assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

- `minSdk` 26, `targetSdk` 34.
- Dependencies (Maven Central): `org.smali:dexlib2`, `com.android.tools.build:apksig`,
  Bouncy Castle (`bcpkix`/`bcprov`), AndroidX.

## Use

1. Install & open **UnityInjector**.
2. **Pick APK** → select the Unity game APK.
3. **Pick .so** → select your native library; choose the ABI.
4. Choose a method (or leave **Auto**).
5. **Inject**. Watch the log. The signed output path is shown at the end; use
   **Share** to export it.
6. Uninstall original game, install the patched APK.

## Project layout

```
app/src/main/java/com/modkit/injector/
  MainActivity.java            UI + file pickers + runner
  engine/
    Injector.java              orchestrator (Auto + per-method)
    InjectionMethod.java       AUTO / DEX_LOADLIBRARY / ELF_NEEDED / LIB_ONLY
    InjectionOptions.java      inputs
    InjectionResult.java       outcome + log
    Logger.java                log callback to UI
    ApkArchive.java            read/rewrite the APK zip
    UnityDetector.java         find the Unity activity across versions
    methods/
      DexLoadLibraryInjector.java   smali/DEX loadLibrary insertion (dexlib2)
      ElfNeededInjector.java        DT_NEEDED injection
      LibOnlyInjector.java          drop .so into lib/<abi>/
    elf/Elf.java               minimal ELF reader/patcher
    sign/ApkKeySigner.java     BC key gen + apksig signing
    util/IOUtils.java
```
