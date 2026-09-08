# sing-box 1.15 Upgrade - Pre-Merge Cleanup Plan

> For the coding agent: execute this plan task-by-task. Do not broaden scope, refactor unrelated code, merge to `router-groups`, create a release, or change the app version.

## Goal

Close the remaining pre-merge blockers after the sing-box 1.15 / Go TUN migration:

1. Make CI reproducible and ensure the new schema tests actually run.
2. Assess and remove historical `scratch/` exposure from the upgrade branch history.
3. Align SSR and Snell support claims with actual sing-box 1.15 capability.
4. Rebuild `libcore.aar` and the OSS Debug APK.
5. Produce a final verification report, then stop coding and move to physical-device testing.

## Current State

Source branch: `router-groups-go-tun`

Known source tip from the previous report: `5a81508`

Target base branch: `router-groups`

Recommended clean branch: `router-groups-go-tun-clean`

Known migration state to preserve:

- Default TUN implementation is Go and final config emits `"stack": "go"`.
- gVisor / System / Mixed remain available as fallbacks.
- URL Test was migrated away from `http.DefaultClient` to sing-box URL test logic using the target outbound/endpoint dialer.
- Stats and ResetAllConnections were restored for sing-box 1.15.
- DNS was migrated to the sing-box 1.15 typed-server schema.
- WireGuard was migrated from outbound form to endpoint form.
- Snell fork-only `quic_proxy_mode` output was removed.
- `config_parse_test.go` was added for schema smoke testing.
- SSR is not supported by upstream sing-box 1.15.
- Snell runtime support is limited to v4/v6; v1/v2/v3/v5 must not be advertised as working.

## Global Constraints

- Do not merge into `router-groups`.
- Do not create a release.
- Do not bump the app version.
- Do not delete gVisor, System, or Mixed fallback stacks.
- Keep Go as the default TUN stack.
- Preserve the current DNS, WireGuard, URL Test, Stats, and ResetAllConnections migrations unless a failing test proves a cleanup-related regression.
- Do not add a new SSR implementation in this pass.
- Do not perform unrelated optimization or refactoring.
- Never print real UUIDs, passwords, private keys, preshared keys, tokens, authenticated URLs, or complete historical configuration dumps.
- If a historical file contains a potentially live secret, report only the file path and secret category, then mark `ROTATION REQUIRED`.
- Git-history cleanup does not revoke credentials; rotation is a separate owner action.
- Do not force-push or rewrite `router-groups`.
- Prefer a new clean branch over rewriting shared history.

---

## Task 1 - Establish a Baseline

### 1.1 Confirm current state

Run:

```bash
git status --short
git branch --show-current
git rev-parse HEAD
git rev-parse router-groups
```

Expected:

- Source branch is `router-groups-go-tun`.
- Working tree is clean before cleanup work.
- Record the source HEAD and base SHA for the final report.

### 1.2 Run baseline tests

Run:

```bash
cd libcore
go test ./...
cd ..
```

Then run the repository's existing OSS Debug APK build command and the existing `libcore.aar` build command.

Record any baseline failure exactly. Do not modify unrelated code merely to silence an existing failure.

---

## Task 2 - Fix CI Reproducibility

### Objective

The new `config_parse_test.go` must run in GitHub Actions, and the workflow Go version must match the version required by `libcore/go.mod`.

### 2.1 Determine the exact Go version

Run:

```bash
grep -nE '^(go|toolchain) ' libcore/go.mod
```

Previous review expectation: Go `1.25.5`. Treat `libcore/go.mod` as the source of truth.

### 2.2 Inspect workflows

Run:

```bash
grep -RInE 'setup-go|go-version|go test|libcore' .github/workflows
```

### 2.3 Update CI

Required result:

- GitHub Actions installs an explicit Go version compatible with `libcore/go.mod`.
- Do not leave `^1.24` if the module requires Go 1.25.5.
- Add an explicit test step that executes the package containing `config_parse_test.go`.

Preferred command:

```bash
cd libcore
go test ./...
```

If full `go test ./...` cannot run in CI for a documented host-platform reason, use the narrowest correct package/test command that definitely executes `config_parse_test.go`, and document why.

### 2.4 Verify locally

```bash
cd libcore
go test ./...
cd ..
```

### 2.5 Commit

Suggested commit:

```bash
git add .github/workflows
git commit -m "ci: align Go toolchain and run libcore tests"
```

Add `libcore/go.mod` or `libcore/go.sum` only if they actually changed.

---

## Task 3 - Audit Historical `scratch/` Exposure Safely

### Objective

Determine whether historical `scratch/` files contained real credentials without printing any credential values.

Known concern: `scratch/` was committed around `c841034` and later removed around `1348c2c`. A later deletion does not remove data from Git history.

### 3.1 List historical scratch paths only

```bash
git log --all --name-only --pretty=format: -- scratch/ | sort -u
```

### 3.2 List commits touching scratch

```bash
git log --all --oneline -- scratch/
```

### 3.3 Inspect for secret categories without exposing values

Check historical files for categories including:

- proxy protocol UUID/user IDs
- passwords
- private keys
- preshared keys
- API tokens
- subscription URLs
- authenticated proxy URLs
- WireGuard private keys
- SSH private key material

Output format must be limited to:

```text
<historical path> | <credential category> | ROTATION REQUIRED / NO LIVE SECRET DETECTED
```

Do not print the value. Do not print a reversible encoding or a hash intended to identify the value.

If potentially live credentials are detected, put this exact marker in the final report:

```text
SECURITY: ROTATION REQUIRED
```

### 3.4 Confirm future scratch protection

Ensure `.gitignore` contains an effective rule equivalent to:

```gitignore
scratch/
```

Do not duplicate the rule if it already exists.

---

## Task 4 - Create a Clean Upgrade Branch

### Objective

Create a reviewable branch directly from `router-groups` that contains the final intended migration changes but does not replay the historical `scratch/` commits.

Do not rewrite `router-groups`.

### 4.1 Inspect the final net diff

```bash
git diff --stat router-groups..router-groups-go-tun
git diff --name-status router-groups..router-groups-go-tun
```

Required check:

- No `scratch/` path appears in the final net diff.

### 4.2 Create clean branch from base

```bash
git switch router-groups
git pull --ff-only
git switch -c router-groups-go-tun-clean
```

If `git pull --ff-only` is not appropriate because the local base is intentionally pinned or not tracking a remote, do not improvise. Use the verified local base SHA and record it.

### 4.3 Apply the final net code state without replaying old commits

Preferred method:

```bash
git diff --binary router-groups..router-groups-go-tun > /tmp/go-tun-clean.patch
git apply --index /tmp/go-tun-clean.patch
```

Inspect before committing:

```bash
git status --short
git diff --cached --stat
git diff --cached --name-status
```

Required checks:

- No `scratch/` files are staged.
- No unrelated debug/local artifacts are staged.
- The staged change set matches the intended final source-branch tree.

### 4.4 Run tests and builds before the clean commit

```bash
cd libcore
go test ./...
cd ..
```

Build `libcore.aar` and OSS Debug APK using the repository's established commands.

### 4.5 Commit clean migration state

A clean squash is acceptable and preferred over replaying the old history.

Suggested commit:

```bash
git commit -m "feat: complete sing-box 1.15 Go TUN migration"
```

### 4.6 Confirm clean branch history contains no scratch paths

```bash
git log --name-only --pretty=format: router-groups..HEAD -- scratch/
```

Expected: no output.

### 4.7 Compare clean branch to old source branch

```bash
git diff --stat router-groups-go-tun..HEAD
git diff --name-status router-groups-go-tun..HEAD
```

Expected:

- Differences are only deliberate cleanup changes such as CI, docs, or protocol support gating.
- Investigate every unexpected difference.

---

## Task 5 - Align SSR and Snell Claims With Runtime Reality

### Objective

The app must not claim support for protocol variants that the current sing-box 1.15 core cannot execute.

### 5.1 Search all claims and code paths

```bash
grep -RInE 'SSR|ShadowsocksR|shadowsocksr|Snell|snell' app README* docs 2>/dev/null
```

Inspect:

- profile creation UI
- profile editing UI
- import paths
- config builders
- runtime validation
- protocol support tables
- README/docs

### 5.2 SSR behavior

Required behavior:

- Do not silently convert SSR to Shadowsocks.
- Do not implement a new SSR core here.
- If SSR is already hidden/disabled and imports produce a clear unsupported error, keep it.
- Otherwise disable new SSR use and surface a clear message equivalent to:

```text
SSR is not supported by the current sing-box 1.15 core.
```

### 5.3 Snell behavior

Required behavior:

- Snell v4: supported.
- Snell v6: supported.
- Snell v1/v2/v3/v5: rejected or clearly marked unsupported before starting the core.
- Do not emit `quic_proxy_mode`.

### 5.4 Tests

Add or update focused tests using the repository's existing test patterns.

Required assertions:

- SSR unsupported path is deterministic and user-readable.
- Snell v4 accepted.
- Snell v6 accepted.
- Snell v1 rejected.
- Snell v2 rejected.
- Snell v3 rejected.
- Snell v5 rejected.
- Supported Snell config parses against the current sing-box 1.15 schema.

Run:

```bash
cd libcore
go test ./...
cd ..
```

Also run the relevant Android unit tests if validation is implemented in Android/Kotlin/Java code.

### 5.5 Update documentation

Update README/docs support claims to match actual runtime capability.

### 5.6 Commit separately

Suggested commit:

```bash
git add app README* docs libcore
git commit -m "fix: align SSR and Snell support with sing-box 1.15"
```

Stage only files actually changed by this task.

---

## Task 6 - Final Build and Artifact Verification

### 6.1 Build `libcore.aar`

Verify:

- `app/libs/libcore.aar` exists.
- It contains the expected four Android ABI native libraries.
- No `scratch/` content is packaged.

### 6.2 Build OSS Debug APK

Verify:

- `app/build/outputs/apk/oss/debug/app-oss-debug.apk` exists.
- Resource merge succeeds.
- Build succeeds without relying on uncommitted local files.

### 6.3 Re-run Go tests after Android build

```bash
cd libcore
go test ./...
cd ..
```

### 6.4 Confirm repository state

```bash
git status --short
git log --oneline --decorate router-groups..HEAD
```

Expected:

- Working tree clean except generated artifacts intentionally ignored by repository policy.

### 6.5 Push only the clean branch

```bash
git push -u origin router-groups-go-tun-clean
```

Do not force-push `router-groups`.
Do not merge.
Do not create a release.

---

## Task 7 - Final Verification Report

Update:

```text
GO_TUN_UPGRADE_VERIFICATION.md
```

Add:

```markdown
## Pre-Merge Cleanup Verification
```

The section must contain the following.

### A. CI

- Exact Go version declared by `libcore/go.mod`.
- Exact Go version installed by GitHub Actions.
- Exact CI command that executes `config_parse_test.go`.
- Local Go test command and PASS/FAIL.

### B. Git Security

- Historical `scratch/` paths found: yes/no.
- Credential categories detected: categories only, never values.
- `ROTATION REQUIRED`: yes/no.
- Clean branch name.
- Clean branch base SHA.
- Confirmation that upgrade history on the clean branch contains no `scratch/` path.

### C. Protocol Support

Use this table unless code/tests prove a different state:

| Protocol | Final status |
|---|---|
| VLESS | supported |
| VMess | supported |
| Trojan | supported |
| Shadowsocks | supported |
| Hysteria2 | supported |
| TUIC | supported |
| AnyTLS | supported |
| SSH | supported |
| SOCKS | supported |
| HTTP | supported |
| ShadowTLS | supported |
| Snell v4 | supported |
| Snell v6 | supported |
| Snell v1/v2/v3/v5 | unsupported / rejected |
| WireGuard | supported via endpoint migration; physical-device test pending |
| SSR | unsupported / rejected |
| Juicity | supported |

### D. Build Outputs

Report:

- `libcore.aar`: PASS/FAIL
- four ABI native libraries: PASS/FAIL
- OSS Debug APK: PASS/FAIL
- config parse tests: PASS/FAIL
- Android unit tests: exact command and PASS/FAIL

### E. Still Pending on Physical Device

Keep all of these explicitly pending unless they were actually tested on a physical Android device:

- Go/gVisor/System/Mixed TUN startup and reconnect.
- CPU / memory / power A/B comparison.
- URL Test correctness through the selected target node.
- Stats runtime traffic counters.
- ResetAllConnections runtime behavior.
- WireGuard connectivity.
- IPv4/IPv6 behavior.
- UDP/QUIC behavior.
- old SSR/Snell config user-facing error behavior.

Do not claim host-side tests validate these runtime behaviors.

---

## Stop Conditions

Stop and report instead of making broad changes if any of the following happens:

1. The clean branch cannot reproduce the source branch's final intended functionality without replaying sensitive history.
2. A test failure indicates a new core/runtime regression unrelated to CI, history cleanup, or protocol capability alignment.
3. Fixing a failure would require a new SSR implementation, a large UI redesign, or another sing-box architecture migration.
4. The base branch moved enough to create large conflicts.
5. A historical secret is detected and its validity cannot be determined.

In those cases, report the exact blocker and the smallest next action. Do not opportunistically "fix everything".

---

## Definition of Done

This cleanup is complete only when all items below are true:

- [ ] CI installs a Go version compatible with `libcore/go.mod`.
- [ ] CI explicitly runs the libcore tests containing `config_parse_test.go`.
- [ ] Local libcore tests pass.
- [ ] Historical `scratch/` exposure is assessed without leaking values.
- [ ] Any potentially live secret is marked `ROTATION REQUIRED`.
- [ ] A clean branch based on `router-groups` exists without `scratch/` in the upgrade history.
- [ ] SSR is no longer represented as functional.
- [ ] Snell support is accurately limited to v4/v6.
- [ ] `libcore.aar` builds successfully.
- [ ] OSS Debug APK builds successfully.
- [ ] Final verification report is updated.
- [ ] Clean branch is pushed.
- [ ] Nothing is merged into `router-groups`.
- [ ] No release is created.
- [ ] No version number is changed.
- [ ] No unrelated refactor is introduced.

After completing this plan, stop coding. The next phase is physical-device validation, not another refactor pass.
