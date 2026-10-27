# Building Yomitori

이 문서는 공개 저장소를 새로 복제해 APK를 만드는 절차입니다. 명령은 저장소 루트에서 실행합니다. Gradle 작업은 한 프로세스씩 실행하세요.

The current app version is `0.23.0-yomitori.51` (`versionCode 117`).

## Requirements

The current checkout uses the following tools. The Gradle files and `scripts/dev.ps1` remain the source of truth when versions change.

| Tool | Version / package |
| --- | --- |
| JDK | 21 |
| Android SDK platform | API 37 (`platforms;android-37.0`) |
| Android SDK Build Tools | `37.0.0` |
| Android NDK | `28.2.13676358` |
| CMake | `3.22.1` |
| Gradle | `9.6.1`, supplied by the wrapper |
| Android Gradle Plugin | `9.3.1` |

Install Android SDK command-line tools and platform-tools through Android Studio's SDK Manager. The environment doctor also checks the emulator tool. The app supports Android API 26+, while the native NCNN engine is built for `arm64-v8a`. Use an ARM64 device to validate the complete OCR pipeline.

## Clone and prepare

```sh
git clone --recurse-submodules https://github.com/hvboq/Yomitori.git
cd Yomitori
```

For an existing checkout:

```sh
git submodule sync --recursive
git submodule update --init --recursive
```

The app consumes `yakuyomi-engine` through Gradle `includeBuild`. The engine also includes `yakuyomi-nightread`; both submodules are required during configuration. Keep the pinned revisions. Do not apply an old engine patch on top of the checked-out engine.

On Windows PowerShell, set SDK/JDK paths for the current process and dot-source the helper:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:ANDROID_HOME = 'C:\path\to\Android\Sdk'
. .\scripts\android-env.ps1
.\scripts\dev.ps1 doctor
```

Replace the two example paths with your installations. The helper prefers Android Studio's bundled JDK when available, otherwise uses `JAVA_HOME`. For the SDK it checks `ANDROID_HOME`, then `local.properties`, then the standard per-user SDK location. It sets `ANDROID_SDK_ROOT` and the current process `PATH`; it does not change system-wide settings.

On other platforms, configure `JAVA_HOME` and `ANDROID_HOME` for your local installation and run the Gradle commands with `./gradlew`. The environment and signing examples below use PowerShell syntax; adapt those commands to your shell. Keep `local.properties` local; do not commit machine paths.

## Debug build and checks

```powershell
.\gradlew.bat :app:spotlessKotlinCheck :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
```

The ARM64 debug APK is `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`, using package `app.yomitori.yakuyomi.dev`. The release package is `app.yomitori.yakuyomi`.

When changing the engine, also run its JVM tests from the app checkout:

```powershell
.\gradlew.bat :yakuyomi-engine:engine:testDebugUnitTest :yakuyomi-engine:inference-core:testDebugUnitTest --console=plain
```

Device tests are separate from JVM tests. Build the debug and instrumentation APKs from the same checkout. If using `-PyomitoriIsolatedAudit=true`, use it for both APKs; this selects `app.yomitori.yakuyomi.audit` and does not change the release package. Use a dedicated test folder for storage tests because package separation alone does not isolate shared storage.

Models are not needed to compile the app. To use OCR, download them from the app's model settings or supply the files described in [the model documentation](../yakuyomi-engine/docs/MODELS.md). The optional `python scripts/prepare_translation_models.py` helper downloads and verifies the translation model files into the ignored `.translation-models/` directory; it requires Python 3.11+.

## Optional tracker credentials

Bangumi, Hikka, Kitsu and Shikimori require application credentials supplied at build time. All eight fields default to an empty string. A tracker is unavailable when its credential pair is missing or blank; public builds keep these fields blank. Reading, on-device OCR and your configured OpenAI-compatible translation server do not require these tracker credentials.

For a private build with applications you registered yourself, copy [`api-keys.properties.example`](../api-keys.properties.example) to the ignored `api-keys.properties` in the repository root and populate only the required pair. The template contains exactly these eight blank fields:

```properties
BANGUMI_CLIENT_ID=
BANGUMI_CLIENT_SECRET=
HIKKA_CLIENT_ID=
HIKKA_CLIENT_SECRET=
KITSU_CLIENT_ID=
KITSU_CLIENT_SECRET=
SHIKIMORI_CLIENT_ID=
SHIKIMORI_CLIENT_SECRET=
```

`HIKKA_CLIENT_ID` is Hikka's application client reference. Values configured here are embedded in the APK; an ignored properties file does not make an APK's credentials private. Keep all eight fields blank when producing the public release and do not copy upstream application credentials. The reader's translation endpoint, model and user API key are configured inside the app and are unrelated to this file.

공개 빌드는 위 네 트래커의 자격증명을 빈값으로 유지합니다. 필요하면 직접 등록한 앱의 자격증명으로 자체 빌드할 수 있으며, 이 설정 없이도 일반 OCR과 사용자 LLM 서버 번역을 사용할 수 있습니다.

## Sign your own release

Use a signing key you own. The project's private release key is not part of the repository. Your APK cannot update an installed APK signed with a different key.

For a new personal signing key, create an ignored directory and let `keytool` prompt for passwords:

```powershell
New-Item -ItemType Directory -Force .signing | Out-Null
& "$env:JAVA_HOME/bin/keytool.exe" -genkeypair -keystore .signing/yomitori-release.jks -alias yomitori -keyalg RSA -keysize 4096 -validity 10000
```

If you already have a release key, reuse it instead of generating a replacement. Create an untracked `keystore.properties` in the repository root:

```properties
storeFile=../.signing/yomitori-release.jks
storePassword=YOUR_KEYSTORE_PASSWORD
keyAlias=yomitori
keyPassword=YOUR_KEY_PASSWORD
```

`storeFile` is resolved relative to the `app/` module. The example points at `.signing/` in the repository root. Enter the real passwords only in your local file; keep the file, key and backups private.

```powershell
.\gradlew.bat :app:assembleRelease --console=plain
$releaseApk = 'app/build/outputs/apk/release/app-arm64-v8a-release.apk'
& "$env:ANDROID_HOME/build-tools/37.0.0/apksigner.bat" verify --verbose --print-certs $releaseApk
& "$env:ANDROID_HOME/build-tools/37.0.0/zipalign.exe" -c -P 16 -v 4 $releaseApk
Get-FileHash -Algorithm SHA256 $releaseApk
```

Without `keystore.properties`, this build falls back to the debug signing key even for `assembleRelease`. A successful build alone does not establish release signing. Check the certificate against your intended certificate, the APK metadata and ABI, and the checksum before distributing it. Native runtime and reader behavior still require testing on a device.

소스에는 서명 키·비밀번호·API 키·개인 설정·모델 가중치·APK·원시 로그를 커밋하지 않습니다. 공개 APK를 배포할 때는 실제 빌드에 사용한 앱 커밋과 엔진 하위 모듈 커밋, APK SHA-256을 함께 기록하세요. 이후 문서 커밋을 기존 APK의 빌드 출처로 바꾸지 마세요.
