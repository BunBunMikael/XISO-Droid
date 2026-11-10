# XISO Packer for Android

An Android port of the `xdvdfs-web` tool: **Pack**, **Unpack** and **Compress** XISO images
from a folder or an ISO, entirely on the device. The UI follows the web page (same steps,
wording, colors and dark mode), laid out for phones.

* `../xdvdfs-android` – Rust JNI library (`libxdvdfs_android.so`) built on `xdvdfs-core`
* `app/` – Kotlin / Jetpack Compose app (Storage Access Framework pickers, foreground
  service so long jobs keep running with the screen off or the app in the background)

## Building with GitHub Actions

Push the repository to GitHub; `.github/workflows/android.yml` builds the native library
for `arm64-v8a`, `armeabi-v7a` and `x86_64` with `cargo-ndk`, then the APK with Gradle.
Download the APK from the run's **Artifacts** (for `v*` tags it is also attached to the
release) and install it. The APK is signed with the debug key, which is fine for sideloading.

## Building locally

```sh
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 --platform 26 \
    -o android/app/src/main/jniLibs build --release -p xdvdfs-android
cd android && ./gradlew assembleRelease
```

Requires JDK 17, the Android SDK (platform 35) and NDK r28.

## Notes

* **Pack** writes `<name>.xiso.iso`; **Compress** writes CISO parts (`<name>.1.cso`, ...)
  into the chosen folder; **Unpack** extracts into the chosen folder.
* A failed or cancelled Pack removes the partially written image.
* Android does not allow picking the root of a storage volume or the `Download` folder as a
  folder; choose or create a subfolder.
* Inputs must be raw ISO/XISO files (all XGD layouts are detected), like the web version.
