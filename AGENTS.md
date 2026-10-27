# Yomitori contributor instructions

Yomitori is an Android manga reader based on Yakuyomi and Mihon. Start with [README.md](README.md), [docs/BUILDING.md](docs/BUILDING.md), the relevant module's instructions, and the current diff.

## Scope and coordination

- Preserve existing contributor changes. Assign file ownership before parallel editing; do not revert or stage unrelated changes.
- Use one integration owner for Gradle, device operations and Git mutations. Report implementation and validation separately.
- Keep the app, `yakuyomi-engine` and nested `yakuyomi-nightread` as separate repositories. Preserve their history, licenses, attribution and pinned submodule revisions.
- Implement OCR, translation contracts, image processing and JNI in the engine. Keep reader UI, queues, persistence and app settings in the app.

## Build and verification

- On Windows, dot-source `scripts/android-env.ps1` and use `scripts/dev.ps1 doctor`. Do not change system-wide JDK or SDK settings.
- Run Gradle serially. Start with checks relevant to the change: `:app:spotlessKotlinCheck`, `:app:testDebugUnitTest`, and the engine tests when needed. Do not repeatedly run `clean` without a reproduced reason.
- Native OCR requires ARM64 validation. Synthetic responses, JVM tests, real native processing, an external translation server and a signed release are different validation scopes; report them accurately.
- UI changes require checks through the actual reader/menu entry points. A direct ViewModel call does not verify menu availability, automatic start or visible refresh.
- Discover connected devices for each session and select the target explicitly. Preserve user settings, queues and files; use dedicated test data and restore temporary settings.
- Use neutral, redistributable fixtures. Do not publish user artwork, extracted text, complete content URLs, personal endpoint details or raw logs.

## Behavior to preserve

- A page processing attempt sends the eligible OCR text and region IDs as one logical translation request. Compatible partial resumes must not request already successful regions again. Network retries are separate. Never send page images to the translation server.
- Selected-page online translation must not schedule an entire chapter download. Image prefetch must not start OCR. Whole-chapter processing remains an explicit action; local folders and archives use their local paths.
- Preserve originals before translation or re-typesetting. Failed, cancelled or unresolved work is not complete. Protect active cache leases and resume materials; cache entries are not permanent downloads.
- Bind progress and results to the exact page and attempt. Reject stale updates, maintain cancellation ownership, and refresh the reader when a ready page's backing URI changes.
- Preserve bitmap density metadata while using explicit pixel coordinates for analysis and temporary images. Analyze transparent RGB against white before interpolation; retain original ARGB semantics for opacity decisions. Verify pixel boundaries with independent controls.
- Keep Hitomi DNS/TLS settings separate from translation server clients. Do not disable TLS certificate verification.
- Use the existing encrypted credential storage. Do not hardcode a private endpoint, model choice, credential or device identifier for debugging.
- Keep all eight optional tracker build credential fields blank by default. Missing Bangumi, Hikka, Kitsu or Shikimori pairs must disable that tracker without affecting reading, OCR or the user's configured translation endpoint. Keep the blank `api-keys.properties.example` template public and its populated `api-keys.properties` copy untracked; configured values become part of the APK.

## Changes and releases

- Stage only intended files and review the staged diff. Commit engine changes first, then app changes with the engine gitlink. Push only when requested.
- Keep signing keys, `.signing/`, `keystore.properties`, `local.properties`, credentials, downloaded models, APKs and raw logs out of Git.
- `assembleRelease` can use the debug key when no signing properties exist. Verify the intended certificate, package metadata, ARM64 native libraries, alignment and APK SHA-256 before describing an artifact as a signed release.
- Record the actual source revisions used to build an artifact. Later documentation commits do not change an existing APK's provenance.
- Preserve the original outcome of failed checks and describe the corrected follow-up separately. Do not turn a small sample into a claim of complete feature coverage or long-term stability.
