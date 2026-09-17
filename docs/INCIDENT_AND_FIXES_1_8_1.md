# The 1.8.0 incident, and every fix that came out of it

Written 2026-09-17, after v1.8.1. For anyone porting this to the Windows client
— or to any other client built on the same engine.

**Read this first:** almost every defect below lives in `native/aether/aether`,
the vendored engine shared with the desktop build. Only three things in here are
Android-specific. If the Windows client vendors a comparable revision of Aether,
**it has these defects too**, and the fixes port across close to verbatim.

---

## 1. What users saw

Reports after 1.8.0: "I updated and it will not connect." Some were fixed by
going back to 1.6.1. Some were not fixed by anything.

Three screens, all the same underneath:

- `prober: no clean endpoint found. Stopped after 8 attempts.`
- `other: no WireGuard endpoint answered on this network.`
- Stuck on *Finding a working route* for minutes.

Every one of those sentences blames the network. In not one of the cases we
eventually traced was the network at fault.

## 2. The class of defect

One sentence, and everything else follows from it:

> The engine keeps local copies of a remote object — the Cloudflare device —
> changes that object from a distance, and never reconciles the copies. And it
> treats a registration as a step in a pipeline rather than as a scarce resource
> the address is charged for the moment the API answers.

A second, independent class turned up later and turned out to hurt more users:

> Discovery ignores what the server said. The account answer names the endpoint
> this device is on; the engine searched a hard-coded pool instead.

## 3. The defects, with evidence

### D1 — Registrations were thrown away

`load_or_provision_masque` did: register → **enrol** → save. A whole second
network call sat between the expensive step and the only line that persisted it.

Timing, measured on a phone in Iran (log timestamps 18:13:26 → 18:15:08):

| step | cost |
|---|---|
| registration, direct route | 5 attempts × 20 s timeout ≈ **102 s** |
| then the camouflaged route | succeeds |
| enrolment, direct route | the same ladder again ≈ **102 s** |

Against Automatic's lane budgets — 60 s (`ENGINE_QUICK_MS`), 75 s (quick lanes),
180 s (full lanes), 300 s (`AETHER_AS_SET`) — the arithmetic is decisive:

- **Quick lanes never reach the camouflaged route at all.**
- **Full lanes reach registration, succeed, and are then cancelled during
  enrolment — discarding a registration Cloudflare had already counted.**

Cloudflare rate-limits registrations **per address**. An address that spends its
allowance is refused for hours, *on every version of the app the user falls back
to*. That is the group that downgrading did not help.

### D2 — MASQUE enrolment silently revoked WireGuard

`PATCH /reg/{id}` overwrites the same `key` field registration filled with the
Curve25519 public key, and sets `tunnel_type: masque`. Cloudflare then holds no
WireGuard key for that device, and **every endpoint everywhere answers with
silence** — indistinguishable from a network that drops UDP.

The path that shipped:

1. A user has only ever run WireGuard, so only `aether.toml` exists.
2. They run MASQUE. `adopt_legacy_masque_identity` **copies** that identity into
   `aether-masque.toml` rather than paying for a second registration.
3. The copy is enrolled. The device's WireGuard key dies.
4. The certificate is written only into the MASQUE file. `aether.toml` keeps
   looking like a pristine WireGuard identity **for the rest of the install's
   life**.

The guard meant to catch this asked the WireGuard file whether *it* carried a
certificate. Enrolment happened on a copy, so it never did.

Two further traps found while fixing it:

- `has_masque_credentials()` is false once the certificate is **expiring**. An
  expired certificate still means the WireGuard key was overwritten a year
  earlier. The test must be "carries a certificate at all", not "carries a
  usable one".
- `config::save_masque_creds` existed *precisely* to record this and had **no
  production caller since 1.0.0**.

### D3 — The direct API route was retried where it could not work

`send_with_retry` treated a transport error — name did not resolve, connection
refused, reset, timed out — the same as an HTTP status, and repeated the
identical request four more times at 20 s each. On a network that blackholes the
API, that is 102 s spent before the camouflaged route (random Cloudflare edge
address, no DNS lookup, split ClientHello, alternate TLS fingerprints) — the
only one that can work there — was allowed to start.

### D4 — The identity backup did not cover the default configuration

Format 1 exported `aether.toml` and `aether-secondary.toml`. The transport
starts on MASQUE, so an install never switched to WireGuard has **neither** —
and the one defence against losing a registration answered *"there is no
identity to export yet"* to the people who most needed it. Every export test
wrote to the WireGuard path, so nothing caught it.

### D5 — WireGuard never noticed a refused identity

`load_or_provision_warp` had no refusal handling at all. `refresh_profile`,
which detects it, only runs under `adopt_team_profile` — which returns early
unless a Zero Trust team is configured. MASQUE handled refusal; WireGuard did
not. *(Design weakness. Not demonstrated as a cause of any report. Addressed by
the identity store, not by 1.8.1.)*

### D6 — Two provisionings could race

`PREPARE_RUNNING` guards prepare against prepare. `nativeRun` does not check it,
and the service deliberately leaves a stale engine behind. *(Plausible race,
never demonstrated. 1.8.1 takes the lock anyway; it is cheap.)*

### D7 — MASQUE searched for an endpoint it had been handed

**This is the one that hurt most users, and it was found last.**

Every registration answer carries the address this device is on:

```json
"config": { "peers": [ { "endpoint": {
    "v4":"162.159.198.2:0",
    "v6":"[2606:4700:103::2]:0",
    "host":"engage.cloudflareclient.com:2408",
    "ports":[443,500,1701,4500,4443,8443,8095] } } ] }
```

`endpoint_from()` parsed it. `finish_provision` stored it in
`Identity.assigned_endpoint`. And then **only Zero Trust ever read it.** A
consumer account ignored the one address the server had just named and
brute-forced the hard-coded pool:

```
scan mode=balanced ip=dual-stack candidates=3010
ports=[443,500,1701,4500,4443,8443,8095]
concurrency=16 per_probe=6s budget=120s
```

120 s ÷ 6 s × 16 = **320 probes out of 3,010 — about a tenth of the pool per
attempt.** That is why MASQUE took minutes when it worked at all, and why a scan
that found nothing reported it as a dead network.

WireGuard never had this problem: it has a documented-anchor pass
(`trying 36 endpoints across 5 profiles`) and connects in about three seconds in
every log we have.

Measured against a live account, from a network with no filtering:

| target | H2 (pinned) | H2 (unpinned) | QUIC |
|---|---|---|---|
| assigned `162.159.198.2:443` | **OK 1.15 s** | **OK 260 ms** | **OK 471 ms** |
| pool address `162.159.192.1:443` | pin failure | `connect-ip status 400` | closed before data plane |

## 4. Wrong turns — read these, they cost hours

### 4.1 "The TLS pins are stale" — they were not

The first diagnostic probed five addresses from the hard-coded CIDR pool
(`162.159.192.1`, `.193.1`, `.195.1`, `.196.1`, `.204.1`) and every one failed
pin verification, so the conclusion was that `consts::MASQUE_PINS` no longer
matched what Cloudflare serves. **Wrong.**

Those `.1` addresses are **ordinary Cloudflare web edges**, not MASQUE gateways:

```
162.159.192.1  subject=CN=cloudflareclient.com  issuer=Let's Encrypt YE2
162.159.198.2  subject=CN=masque.cloudflareclient.com
               issuer=Cloudflare, Inc., CN=2024-02-27 Self-Signed Root
```

The real gateway still serves the self-signed root the pins were taken from, and
the pin matched byte for byte. The `400` on connect-ip was simply a normal HTTPS
endpoint refusing a protocol it does not speak.

**Lesson, and it generalises:** probe what the account names. A diagnostic that
guesses its own targets reproduces the very defect it is investigating.

The wrong conclusion is in commit `39843db9`; `b6772c51` corrects it in the
history. Do not trust the earlier message.

### 4.2 Gradle exit codes lie through a pipe

`./gradlew … | tail -5` reports the **pipe's** exit code. Two builds were
reported as succeeding when they had failed. Worse, the task name is flavoured —
`:app:compileDebugKotlin` is ambiguous and fails; the real names are
`:app:compileStableDebugKotlin`, `:app:assembleStableDebug`,
`:app:testStableDebugUnitTest`. **Read the last line, not the exit code.**

### 4.3 The NDK is found through the environment, not `local.properties`

`local.properties` had `sdk.dir=E:/android-sdk`, but `ANDROID_HOME` pointed at a
different SDK with no NDK, and `cargo-ndk` reads the environment. Builds failed
with an NDK path error that named the wrong SDK. Fixed per-invocation with:

```bash
ANDROID_HOME=E:/android-sdk ANDROID_SDK_ROOT=E:/android-sdk \
ANDROID_NDK_HOME=E:/android-sdk/ndk/29.0.14206865 ./gradlew …
```

Worth fixing in the build so the next person does not hit it.

### 4.4 Test fixtures encoded a state that is now a contradiction

Several existing tests wrote a WireGuard identity **carrying a certificate**.
After the fix that is a contradiction — a certificate is the mark of the
enrolment that took the WireGuard key away — so those tests started failing for
the right reason. The fixtures were wrong, not the code. A `wireguard_identity()`
helper (no cert) now exists beside `sample_identity()`.

### 4.5 Device testing kept missing the point

Three rounds of on-device testing produced no evidence for D2's repair, because
the install was never in the broken state: the phone already had a MASQUE
identity of its own, so `adopt_legacy_masque_identity` never ran and there was
nothing to repair. **The precondition matters more than the steps.** In the end
the repair was proved by a live test from the host, not on the phone.

## 5. The fixes — all engine-side unless marked

### 5.1 Write-ahead persistence — `lib.rs`, `config.rs`

The identity is saved **the instant `POST /reg` returns**, before any other
await. Enrolment was already resumable (the branch that loads a saved identity
with no certificate enrols it), so the fix is the order of two lines.

`config::write_private` also `fsync`s the **directory** after the rename — the
contents were durable, the directory entry naming them was not.

### 5.2 One direct attempt — `account.rs`

A transport error returns immediately and hands over to the camouflaged route.
The retry ladder (`API_ATTEMPTS = 5`) is kept only for answers that ask for one:
**429, 5xx, `Retry-After`**.

### 5.3 Recording an enrolment where it can be seen — `lib.rs`

- `record_enrolment_beside(path, identity)` — after a successful enrolment,
  marks every sibling identity file holding the **same `device_id`** by writing
  the certificate into it (**the certificate only — not the private key**; that
  file will never present it).
- `device_enrolled_elsewhere(path, device_id)` — before a WireGuard identity is
  used, asks the whole directory whether that device was enrolled anywhere else.
- The test is `!cert_pem.is_empty()`, **not** `has_masque_credentials()`.
- `preserve_masque_identity` skips an identity whose `key_pem` is empty — a
  certificate with no key beside it is a *mark*, not a credential.

Both directions, so an install broken by an earlier build repairs itself on the
first connect whichever protocol it connects with.

### 5.4 A non-destructive read — `config.rs`

`config::peek` parses an identity file **without quarantining** what it cannot
parse. `config::load` sets aside anything unreadable, which is right for the
file in use and catastrophic for a directory sweep: `aether-lastconn.toml` sits
beside the identities and is not one. Sweeping with `load` would have deleted a
working endpoint cache on **every connect** — fixing one bug by shipping another.

### 5.5 Single-flight provisioning — `lib.rs`

One global `tokio::sync::Mutex` around register → enrol → save, for **all**
identity files. What it guards is not a file but the per-address quota behind
them.

### 5.6 Backups that cover everything — `lib.rs`

Export format **2** carries all four slots (`identity`, `secondary`, `masque`,
`masque_secondary`); format 1 still imports. Export reads with `peek`, so one
damaged slot cannot refuse the backup of the rest.

### 5.7 The assigned endpoint, before any search — `lib.rs`

`assigned_masque_peers()` returns, in order:

1. `identity.assigned_endpoint` — free, stored at registration.
2. The current one from `fetch_device` — because **Cloudflare moves a device
   between edges**. On one account minutes apart: registration said
   `162.159.192.2`, the device record said `162.159.198.2`.

Both are tried with `quick_verify_masque_peer` before `hunt_masque_peer` is
reached. The API question is **bounded to 4 s** (`ASSIGNED_ENDPOINT_LOOKUP`) — it
is an optimisation on the path to a connect, and on the networks this app exists
for the API is exactly what stalls.

### 5.8 The edge's own reason — `masque_h2.rs`

`describe_refusal()` puts the status **and** any `cf-*` response headers into the
error instead of a bare number.

### 5.9 Android-only

- `isConclusive()` in `AetherVpnService.kt` also matches
  `"registration is on hold"`, so a 3-second retry ladder does not run inside an
  hour-long wait. *(The wait itself is on the 1.9 branch.)*

## 6. For the Windows client specifically

**Everything in §5 except 5.9 is platform-independent and ports directly.**

What differs, and what to check:

| area | Android | Windows |
|---|---|---|
| socket protection | `VpnService.protect()` via `socketprotect` | `egress::apply` only sets `SO_MARK` on Linux/Android; on Windows it is a no-op. Check nothing is silently unprotected that needs to be. |
| identity paths | app `files/` dir | wherever the desktop client points `config_path`. **The sibling-file naming (`-masque`, `-secondary`, `-lastconn`) is derived, so the sweep in 5.3 works unchanged.** |
| log level | `android_logger` pinned at `INFO`, so probe failures at `trace` are invisible | check the desktop logger actually honours `AETHER_LOG_LEVEL`; if it does, §7's first item is already half solved there |
| the planner | `AutoPlanner` in Kotlin | desktop has its own; D7 is in the engine and affects both |

**Priority for the port:** D7 first. It is the one that turns "MASQUE takes over
two minutes or never connects" into "MASQUE connects in under a second", it is
about thirty lines, and it needs no migration.

## 7. Still open — from a full architectural review

Not fixed in 1.8.1. Ordered as reviewed:

1. **Probe settings are not the connection's settings.** ECH is `None` in
   prepare and in the scanner, and is fetched and injected only at connect. So
   what the scanner validated is not what runs. *(A real latent defect — but it
   cannot explain the failures observed here, which all ran with `ech=false` on
   both sides.)*
2. **A fatal QUIC close returns `Ok(())`** after printing `local_error`. The
   layer above sees "the engine stopped", not *why* — so Automatic cannot choose
   its next move on evidence.
3. **Automatic does not choose anti-censorship tactics.** It varies framing
   (H2/H3) and scan depth only. Fragmentation, ECH, noize profiles and TLS
   fingerprints are still left to the user in Advanced — which is exactly the
   decision Automatic exists to remove. H2 also fetches ECH it never uses.
4. **Every probe failure becomes `None`** at `trace`, and the Android logger is
   capped at `INFO`. A pin mismatch, a rejected identity, blocked UDP and a
   timeout all end as `NoCleanEndpoint`. Five endpoints failing the same way is a
   common-mode fault and should stop the scan, not fund another 3,000 probes.
5. **Three independent planners** — scanner UI, the old ladder, `AutoPlanner` —
   which can disagree about ordering.
6. **The cache and the scan result are too poor**: `peer` + `rttMs`, and
   `peer` + `profile`. Transport, ECH mode, fragmentation, real noize profile,
   network, success rate and validation stage are all forgotten. Worse, the
   endpoint is cached **after prepare, not after the session is ready** — so an
   endpoint that probed clean and then failed to connect can be recorded as the
   last good one.
7. **Automatic's worst case is 690 s per pass**, ~23 min over two passes, ~25
   with the remembered-engine step. A technical timeout, not a tolerable wait.
8. **Roaming is not event-driven.** No `ConnectivityManager.NetworkCallback`;
   `Control::Migrate` exists in QUIC and is never called from Android, and is a
   no-op on H2.
9. **TLS pins are hard-coded**, with no versioning, overlap or expiry. They are
   correct today — but rotating them requires shipping a binary, which makes
   them a single point of failure for the whole protocol.

The proposed shape for fixing 1–6 properly: one `AttemptSpec` (transport,
endpoint, IP family, ECH policy, fragmentation, noize, TLS groups, validation
level, network epoch, identity generation, deadline) used **unchanged** by
prepare, probe and connect — not process-wide environment variables — and a
typed `ProbeOutcome` (`UdpBlocked`, `TlsAlert`, `EchRejected`, `PinMismatch`,
`IdentityRejected`, `DataPlaneTimeout`, …) so Automatic moves on cause rather
than by blind alternation.

## 8. The identity store (1.9) — the durable fix for §2

On [PR #51](https://github.com/WhiteDNS/WhiteAestherMobile/pull/51), designed in
[`IDENTITY_STORE.md`](IDENTITY_STORE.md). Summary, because the Windows client
will want the same thing:

One file, `aether-store.toml`. A **device** is the remote object; a **slot**
(`wireguard`, `wireguard_inner`, `masque`, `masque_inner`) is a role. Slots point
at devices; devices are never copied. Adoption becomes two slots naming one
device, which the invariants forbid across families — **the bad state is
unrepresentable**.

Invariants, each a test:

1. Every slot names a device that exists, or nothing.
2. No device is named by both a `wireguard*` and a `masque*` slot.
3. `tunnel_type = "masque"` is never handed to a WireGuard slot.
4. `enrolment_pending_since != 0` means unknown server state: usable for MASQUE
   (enrolling twice costs a round trip), **refused to WireGuard** until
   `GET /reg/{id}` says what Cloudflare holds.
5. `refused_at` is recorded, not overwritten.

Invariant 4 closes the one window 1.8.1 cannot: a `PATCH` Cloudflare accepted
whose answer never reached disk. The marker is written **and flushed before the
request goes out**, and cleared by the write that stores the certificate.

`[registration]` holds a persistent backoff — attempts, reason, and a
`next_attempt_at` derived from Cloudflare's own `Retry-After`, carried out of the
retry ladder in `AetherError::RateLimited` instead of being slept on and
forgotten. A ladder of 30 s → 1 h covers failures that came with no answer.

Migration is the repair: legacy files folded in, deduplicated by device id, a
certificate in **any** copy meaning the device is MASQUE and the WireGuard slot
cleared. `migrate_from_legacy` is **pure**, the store is **read back** before
anything is built on it, and **nothing is deleted** — for one release the store
answers for reads while the legacy files are still written, so rolling back is
deleting one file. Reconciliation handles going back and then forward again, and
runs **before** the invariants so an earlier build's mark cannot smuggle a dead
key past them.

Adoption is deleted outright. It costs nothing 1.8.1 did not already cost.

Also on that branch: `provision_embedded` + `nativeProvision`, which buy an
identity through a carrier that is already carrying traffic — Psiphon needs no
account, so it wins the race, and the engine can register through its SOCKS
listener instead of staying stuck every session.

## 9. Tests — what exists and how to run it

### 9.1 Offline

```bash
cd native/aether/aether && cargo test --lib     # 315 pass
cd native/android-bridge   && cargo check --lib
ANDROID_HOME=E:/android-sdk ANDROID_NDK_HOME=E:/android-sdk/ndk/29.0.14206865 \
  ./gradlew :app:testStableDebugUnitTest
```

New offline tests, one per defect:

| test | defect |
|---|---|
| `a_route_that_never_completes_a_request_is_not_repeated` | D3 |
| `a_device_enrolled_in_another_file_is_not_a_wireguard_identity_any_more` | D2 |
| `an_expired_certificate_still_marks_the_device_as_enrolled` | D2 |
| `marking_a_sibling_writes_the_certificate_and_not_the_secret` | D2 |
| `a_cache_beside_the_identities_is_left_alone` | the trap in 5.4 |
| `an_install_that_has_only_ever_run_masque_can_be_backed_up` | D4 |
| `every_account_an_install_holds_travels_in_the_backup` | D4 |
| `a_backup_in_the_older_format_is_still_read` | D4 |

### 9.2 The discipline that made them worth having

**Every new test was run against the code without its fix, to confirm it fails
there.** A green test that cannot fail is worse than no test. The D3 test
reported `left: 1  right: 5` against the old ladder — the exact retry count.

Do this for the Windows port too. It is the only way to know a ported test
actually ported.

### 9.3 Live tests — gated twice, they cost real registrations

```bash
# The whole D2 failure and its repair, against the real API.
AETHER_LIVE_ENROLL_TEST=1 cargo test -p aether adopted_by_masque -- --ignored --nocapture
```

Run once on v1.8.1:

```
wireguard device    99d50163-872e-482e-ae5a-59be8278fcd7   hand shook
after enrolment     the same key no longer hand shakes
replacement device  13041922-3bbd-46a4-b7e6-aa59087f9a34   hand shook
ok in 102.33s
```

It waits **90 s** between enrolment and the re-check: the revocation takes
roughly 30–60 s to reach the edge, which is long enough for a check made
straight afterwards to come back clean. That delay is why this shipped.

```bash
# Why MASQUE probes fail here: asks the account where to probe, then probes
# with pins on, pins off, and over QUIC.
AETHER_LIVE_PROBE_TEST=1 cargo test -p aether why_masque -- --ignored --nocapture
```

```bash
# Does an identity pulled off a phone hand shake from a machine known to work?
adb shell run-as com.whitedns.whiteaesther cat files/aether.toml > id.toml
AETHER_TEST_IDENTITY=id.toml cargo test -p aether identity_from -- --ignored --nocapture
```

`why_masque` is the tool the whole incident lacked. When somebody reports that
MASQUE finds nothing, the first question is whether the edge is refusing
everyone — and until it existed there was no way to ask.

### 9.4 Device plan

`DEVICE_TEST_PLAN.md` gained an **Identity and registration** section. The case
that would have caught all of this and was in no version of that document:

> A first connect on a network where `api.cloudflareclient.com` cannot be
> reached directly.

Every release up to 1.8.0 passed every other check on that page.

Deterministic way to simulate a blocked API with no special network: set the
upstream proxy to `socks5://127.0.0.1:1`. Every API call then fails at the
transport layer instantly — the same shape filtering produces. Count
`registration retry` lines: **zero** is the fix, **four** is the regression.

### 9.5 Reproducing the broken-install state

To exercise D2's repair, the install must have a WireGuard identity **and no
MASQUE identity**, so adoption happens:

```bash
adb shell run-as com.whitedns.whiteaesther ls -l files/
adb shell run-as com.whitedns.whiteaesther rm files/aether-masque.toml
```

Then connect on MASQUE (cancel as soon as `[+] MASQUE key enrolled` appears —
the enrolment precedes the endpoint hunt), then switch back to WireGuard. Expect
`the identity in … was enrolled for MASQUE`.

`run-as` needs a debuggable build.

## 10. Commits

```
5efb3984  Stop losing Cloudflare registrations, and stop hiding revoked ones   D1 D2 D3 D4 + lock
e2db9091  Prove the repair against the live API, not just against files        live D2 proof
39843db9  Ask the edge why MASQUE fails, instead of counting that it did       diagnostic (wrong conclusion)
b6772c51  Try the endpoint Cloudflare assigns before searching for one         D7 + corrects 39843db9
6b4fdafd  Bound the assigned-endpoint lookup, so it cannot delay a connect     D7 safety
5d4e975a  merge → main, tagged v1.8.1 (published as a prerelease)
```

Identity store: PR #51, branch `feat/identity-store`.

## 11. The one-paragraph version

MASQUE was slow or dead because the engine ignored the endpoint Cloudflare
assigns and searched a tenth of a hard-coded pool instead. Identities were being
destroyed because enrolling MASQUE on a device silently revokes its WireGuard
key and only one of two files was told. Registrations were being bought and
thrown away because the save came after a second network call. And none of it
was visible, because every probe failure collapses into one sentence that blames
the network. Fix D7 first; it is the cheapest and the largest.
