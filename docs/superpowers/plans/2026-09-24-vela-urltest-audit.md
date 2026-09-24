# Vela URLTest Audit Plan

> **For agentic workers:** Native execution is authorized by the user's request to audit and repair this branch.

**Goal:** Identify why the Vela profile's isolated URLTest still times out and ship only evidence-supported EgoX client changes on the existing feature branch.

**Architecture:** Recheck the isolated URLTest path from profile selection through Vela sidecar startup and libcore probing. The available device logs predate the latest APK and no Android device is attached, so add narrow stage diagnostics where source inspection shows the failure boundary is currently hidden; do not guess at Vela protocol or server changes.

**Tech Stack:** Kotlin, Android/Gradle, sing-box libcore integration.

**Spec:** Current user request and the supplied URLTest error/log history.

## Global Constraints

- Work only on `codex/vela-protocol-egox`; never merge to `main`.
- Do not publish or modify Vela server/protocol source.
- Preserve existing Vela data-plane behavior and user configuration.
- Do not claim device acceptance without a current phone run.

## Review Focus

- Distinguish `TestInstance.init`, isolated sing-box startup, Vela process startup, and HTTP probe failures.
- Confirm the test target tag still resolves to the Vela SOCKS outbound.
- Avoid logging node keys, full custom probe URLs, or other credentials.
- Preserve the main VPN cache configuration while changing test-instance behavior.
- Keep the reported test result tied to the profile ID and type.

---

### Task 1: Re-audit existing evidence and URLTest wiring

**Files:**
- Inspect `app/src/main/java/io/nekohasekai/sagernet/bg/proto/TestInstance.kt`.
- Inspect `app/src/main/java/io/nekohasekai/sagernet/bg/proto/BatchUrlTestRunner.kt`.
- Inspect `app/src/main/java/io/nekohasekai/sagernet/bg/proto/BoxInstance.kt`.
- Inspect `app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt` and `libcore/box.go`.

- [x] Verify whether supplied logs postdate commit `08c0dfb` and whether an Android device is available.
- [x] Trace Vela profile type 25 from batch eligibility to isolated URLTest and its target-tag resolution.
- [ ] Reconcile a fresh device log with the exact setup/probe phase before making a behavioral change.

### Task 2: Add narrow URLTest stage diagnostics

**Files:**
- Modify `app/src/main/java/io/nekohasekai/sagernet/bg/proto/TestInstance.kt` only if stage boundaries are not already logged.

- [x] Log elapsed time and phase for isolated config initialization, sing-box launch, and URL probe failure.
- [x] Log the resolved target tag and effective test cache flag; never log credentials or a full URL.

### Task 3: Build and close out on the feature branch

**Files:**
- Build `app/build/outputs/apk/oss/debug/EgoX-4.3.2-arm64-v8a-debug.apk`.

- [x] Run `git diff --check` and the OSS debug APK build.
- [ ] Review the changed files, then commit and push only to `codex/vela-protocol-egox`.
- [ ] Verify local and remote branch hashes match and report device-validation limits.
