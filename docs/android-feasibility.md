# Aster Android feasibility

**Decision: bounded selected-Event JNI commit/reopen passed on an x86_64
emulator and an ARMv7 Knox watch; authenticated direct-Wi-Fi transfer and
interruption/recovery passed between two ARMv7 watches; wrong-carrier and
wrong-mission identities were rejected on Knox.** These results use explicitly
authorized evaluation patches; the unmodified stack remains blocked. ARM64
runtime, discovery/relay/BLE, background and battery behavior, actual WearTAK
host integration, and production readiness remain unproven.
This is an evaluation of an optional WearTAK plugin, not a change to WearTAK
core. It does not establish operational readiness or deployment authorization.

## Scope and evidence

Source inspection uses Aster commit
[`d8b8e4141fc6b1accc6f183e6ad65d03bc3b0679`](https://github.com/defenseunicorns/aster/tree/d8b8e4141fc6b1accc6f183e6ad65d03bc3b0679).
The selected Event stack is `aster-node` with `aster-core`,
`aster-redb-store`, and `aster-iroh`; it is distinct from the broader semantic
API exposed by `aster-ffi`. No upstream source or fixture is vendored here.

| Question | Status |
| --- | --- |
| Android ARM64 build | Selected node and JNI shared library cross-built and linked; Android execution remains unverified. |
| Android ARMv7 watch | Bounded local Event commit/reopen and wrong-carrier/wrong-mission rejection passed on the SM-L705U using the isolated `flock64` adaptation. |
| Event local commit and restart | Successful selected Event publish/query and separate Android process restart read, with authorized redb/control-directory patches. |
| Authenticated sync after disconnect | Offline committed Event transferred to separately provisioned node after restart/reconnection; exact ID/payload and authenticated active peer verified. Receiver Event/ack survive separate process restart. |
| Kotlin/JNI | Selected-node methods executed inside Android JVM/app sandbox; initializer, publication, restart read and reconnection passed. |
| Watch battery and background behavior | Unmeasured. |

## Build boundary

Aster requires Rust 1.91 or later and its
[quickstarts](https://github.com/defenseunicorns/aster/blob/d8b8e4141fc6b1accc6f183e6ad65d03bc3b0679/docs/quickstart/README.md)
pin Rust 1.97.1. The workspace uses exact dependency versions and a local
`netlink-packet-core` patch. The selected graph includes redb, rustix, Iroh,
rustls/ring, Tokio, and cryptographic crates. The standalone probe pins Aster
and its patch to that commit, with its own `spike/native/Cargo.lock`; its
transitive versions are not identical to Aster's workspace lockfile.

[Rust's Android target documentation](https://doc.rust-lang.org/rustc/platform-support/android.html)
describes `aarch64-linux-android` and the NDK linker requirement. Match the NDK
Clang API level to the intended minimum Android API. The inspected WearTAK
template uses compile and target SDK 34, min SDK 30, JDK 17, AGP 8.7.2, Kotlin
2.0.21, and debug/release SDK AARs under `app/libs/`. These are template
observations, not proof that Aster supports those settings. Its manifest
declares a standalone watch app and a signature-protected plugin service.
Signing/publication integration is not enabled in this evaluation.

On Windows with Rust 1.97.1, NDK r27d, and API 30, the upstream command
`cargo build --locked -p aster-node --lib --target aarch64-linux-android`
succeeded. The probe's `cargo build --locked --manifest-path
spike/native/Cargo.toml --target aarch64-linux-android` also succeeded. The
result was an AArch64 ELF shared object with the expected JNI exports, not an
Android runtime test. The final optimized build measured 34,242,760 bytes
unstripped and 23,757,624 bytes after `llvm-strip --strip-unneeded`; this is not
a signed or published release and does not establish an acceptable watch
footprint.

The approved patched ARM64 optimized JNI library subsequently built with
`cargo +1.97.1 build --locked --manifest-path spike\native\Cargo.toml --lib
--release --target aarch64-linux-android --config <redb-evaluation.toml>`.
After the same strip command it measured **23,762,600 bytes** (4,976 bytes above
the unmodified measurement), SHA256
`D4D181E1F7EEA52A998293A786A398C57F139BE0C85F6C8937AC70C8B9CA256F`.
The much larger stripped debug libraries used for emulator instrumentation
(53,926,704 ARM64; 66,756,376 x86_64 bytes) are not deployment-size measurements.

With both authorized patches and the completed reconnection probe, optimized
ARM64 cross-build also succeeds. Stripped shared library: **23,889,784 bytes**,
SHA256 `9F4EB4E537B4098991AA73CE393737B28EE9CC513CAFCDAC56DF33FE12587B3E`.
This remains a local measurement, not an ARM64 runtime or battery/footprint
qualification.

The Android library uses the Cargo-lock-selected
`org.rustls:rustls-platform-verifier` component and retains its classes under
shrinking. The JNI initializer calls the upstream Android verifier initializer
with an application context before allowing node startup. The Gradle runtime
dependency resolved to version 0.2.0 for the current lockfile. Do not remove
or bypass certificate verification. A consuming app must retain the library's
transitive dependencies; a standalone AAR does not embed the verifier AAR.
Iroh also requires a JVM/application context for Android system DNS access.
The initializer installs that context once and retains a global JNI reference
for the process lifetime before node startup. This code assumes this standalone
probe owns that initialization; embedding with another native Android runtime
requires coordination rather than initializing `ndk-context` twice.

### Android runtime blocker

An isolated Wear OS emulator using API 36 and x86_64 booted successfully.
The unmodified pinned Aster CLI cross-built for `x86_64-linux-android` and ran
as the Android shell user on the emulator's ext4 `/data` filesystem. These
commands were run against two distinct fresh state roots:

```text
/data/local/tmp/aster demo --nodes 2 --root /data/local/tmp/aster-spike-mesh
/data/local/tmp/aster demo --nodes 2 --root /data/local/tmp/aster-spike-repeat
```

Both exited with code 1 and the same error:

```text
ERROR error=redb%20did%20not%20enforce%20exclusive%20writer%20ownership%20for%20the%20store%20path
```

The selected store's
[`require_exact_writer_exclusion`](https://github.com/defenseunicorns/aster/blob/d8b8e4141fc6b1accc6f183e6ad65d03bc3b0679/crates/aster-redb-store/src/lib.rs#L5661-L5676)
reopens the backing file and requires redb to reject a second writer.
A successful second open produces `ProcessExclusiveLockUnavailable`.
The resolved redb 4.4.0 file backend's `range_lock.rs:56-57` uses the unsupported
default implementation on Android: its native range-lock implementations are
gated to Linux, Apple, and Windows, not `target_os = "android"`.
redb's backend contract permits unsupported locking, but Aster deliberately
does not. This is a concrete portability issue, not a permission failure.
It does not prove the correct remedy is merely changing a cfg expression.

### Isolated redb reproduction and native locking control

`spike/storage-probe` is a separate executable depending only on redb 4.4.0
and libc for the Unix control test. It does not change Aster or redb. Build
for `x86_64-linux-android` with the NDK API-30 linker, copy the executable to
the isolated emulator, and invoke it with a **new** database path:

```text
cargo +1.97.1 build --locked --manifest-path spike/storage-probe/Cargo.toml --target x86_64-linux-android
/data/local/tmp/aster-storage-probe probe /data/local/tmp/aster-redb-crash-probe/probe.redb
```

Actual API-36 x86_64 results:

```text
SAME_PROCESS_REFUSED=false
CROSS_PROCESS_REFUSED=false
REOPEN_VALUE_RETAINED=true
HOLDER_REFUSED=false REOPEN_AFTER_KILL_VALUE_RETAINED=true
OFD_CROSS_PROCESS_REFUSED=true
OFD_SAME_PROCESS_REFUSED=true OFD_RELEASED_ON_CLOSE=true
OFD_HOLDER_REFUSED=true OFD_RELEASED_AFTER_KILL=true
```

Exit code 1 is the expected failing baseline: redb permits another writable
open while a writer is alive. A committed key/value survives clean reopening
and killing an idle holder, but this does **not** establish safe concurrent
writes or interrupted-transaction recovery. The control uses Bionic
`fcntl(F_OFD_SETLK)` on a separate file. It excludes independently opened
handles within one process and across processes and releases the lock on close
and after killing its holder. These are primitive-level results on one
emulator/filesystem, not a validated redb patch or ARM64 runtime result.
The reproduction builds and passes Clippy with `-D warnings`.

Official upstream research on 2026-11-07 found no Android range-lock fix in
redb's latest release
[`v4.5.0`](https://github.com/cberner/redb/releases/tag/v4.5.0)
or master commit
[`96ce31fe5cafcf735425a02f1781487a4287e2de`](https://github.com/cberner/redb/blob/96ce31fe5cafcf735425a02f1781487a4287e2de/src/tree_store/page_store/file_backend/range_lock.rs).
Both retain the native-lock platform gates excluding Android. Aster's current
main manifest still pins redb 4.4.0. Historical Android issues/PRs found in a
bounded search do not establish a remedy for this locking path; simply upgrading
to 4.5.0 is not supported by the inspected source as a fix.

### Approved isolated redb portability patch

User authorized an evaluation-only dependency patch after the failing baseline.
The reproducible patch and original archive checksum/VCS revision are retained
in [redb provenance](../third_party/redb/PROVENANCE.md), with both upstream
licenses. Full source stays in isolated scratch; pristine Cargo cache and Aster
engine source remain unchanged. `spike/prepare-redb-patch.ps1` verifies the
original archive and prepares an explicit Cargo override. Host PowerShell
execution policy prevented executing this script (`PSSecurityException`,
`UnauthorizedAccess`, running scripts disabled); no approval request was reported
by the tool. Policy was not changed or bypassed. PowerShell's read-only parser
accepted the script. The saved patch independently passed `git apply --check` against
pristine redb 4.4.0; executed builds used the isolated patched copy.

Patch scope: three files, only Android platform gates in `Cargo.toml`,
`Cargo.toml.orig`, and `range_lock.rs`. Android selects Bionic `libc::flock`
and `libc::fcntl`, preserving existing OFD shared/exclusive, blocking, query,
unlock, range checks, EINTR retry, and native errors. No GNU syscall assumptions,
NDK JNI lock shim, lock/TLS/crypto bypass, or change to Aster's ownership gate.

Evaluation commands (override file points only to the isolated patched redb):

```text
cargo +1.97.1 build --manifest-path spike\storage-probe\Cargo.toml --target x86_64-linux-android --target aarch64-linux-android --config <redb-evaluation.toml>
cargo +1.97.1 clippy --manifest-path spike\storage-probe\Cargo.toml --target x86_64-linux-android --config <redb-evaluation.toml> -- -D warnings
cargo +1.97.1 build -p aster-node --bin aster --example live_event_application --target x86_64-linux-android --config <redb-evaluation.toml>
cargo +1.97.1 test -p aster-node --test mesh_cli --no-run --target x86_64-linux-android --config <redb-evaluation.toml>
cargo +1.97.1 build --manifest-path spike\native\Cargo.toml --target aarch64-linux-android --target x86_64-linux-android --config <redb-evaluation.toml>
```

The `-p aster-node` commands run in the pinned upstream scratch checkout.
Set the corresponding NDK linker/CC/AR environment variables first. Initial
override resolution updates the selected lockfile; subsequent builds can use
`--locked`. Baseline repository locks retain registry redb, not absolute paths.

Patched probe cross-builds for API-30 x86_64 and ARM64. Actual API-36 x86_64
emulator `/data` ext4 results, fresh root
`/data/local/tmp/aster-redb-patched-dirty/probe.redb`, exit 0:

```text
SAME_PROCESS_REFUSED=true
CROSS_PROCESS_REFUSED=true
REOPEN_VALUE_RETAINED=true
HOLDER_REFUSED=true REOPEN_AFTER_KILL_VALUE_RETAINED=true
DIRTY_WRITER_REFUSED=true UNCOMMITTED_WRITE_ROLLED_BACK=true
OFD_CROSS_PROCESS_REFUSED=true
OFD_SAME_PROCESS_REFUSED=true OFD_RELEASED_ON_CLOSE=true
OFD_HOLDER_REFUSED=true OFD_RELEASED_AFTER_KILL=true
```

The dirty-holder test kills a process after staging an overwrite and new key
inside an uncommitted write transaction. Reopen retains the previous committed
value and omits the uncommitted key. This does not simulate every crash point
inside commit/fsync or power failure.

`spike/storage-probe/range-tests.rs` directly includes the actual patched
`range_lock.rs`, not a substitute lock implementation. Its Android test
executable passed (1 test, 0 failures): overlapping shared locks coexist;
exclusive overlap is refused; disjoint ranges succeed; queries detect conflicts;
unlock restores access; empty/reversed/overflow ranges fail with InvalidInput;
offsets above 8 GiB retain exclusion; unbounded locks cover future growth;
blocking shared/exclusive methods complete after release. Blocking exclusive
acquisition remains blocked for the 100-ms observation interval and completes
within 5 seconds after release. Native fault injection/EINTR and alternate
filesystems remain untested. Compile the standalone range test against the same
target's compiled libc rlib:

```text
REDB_RANGE_LOCK_SOURCE=<patched-redb>\src\tree_store\page_store\file_backend\range_lock.rs
rustc +1.97.1 --edition=2024 --test spike\storage-probe\range-tests.rs --target x86_64-linux-android --extern libc=<matching-libc.rlib> -L dependency=<target-debug-deps> -C linker=<NDK-x86_64-api30-clang> -o <range-test-executable>
TMPDIR=/data/local/tmp /data/local/tmp/redb-range-tests --nocapture
```

The first line denotes an environment variable, not a PowerShell command.

No upstream publication, public fork, production signing, or production
deployment occurred. Authorized physical-watch tests used only the
evaluation APK and synthetic fixtures; they do not prove protected
provisioning or actual WearTAK host integration.

### Patched selected-node process test: next Android boundary

The pinned upstream `aster-node` CLI/example and `mesh_cli` integration-test
executable cross-built for x86_64 with the explicit redb override. Running
the unchanged selected Event process regression on the emulator:

```text
TMPDIR=/data/local/tmp /data/local/tmp/aster-mesh-tests offline_publish_later_real_process_sync_poll_ack_and_restart --exact --nocapture
```

exited 101 (1 failed test). Its offline publisher now passes redb admission
but node startup fails with `Io(Os { code: 13, kind: PermissionDenied,
message: "Permission denied" })`. Android audit evidence identifies the exact
denied operation:

```text
avc: denied { create } for comm="live_event_proc"
name="mesh-000000000000fe35-0000000000010037.sock"
scontext=u:r:shell:s0 tcontext=u:object_r:shell_data_file:s0
tclass=sock_file permissive=0
```

Aster's `LocalZeroizationControl::bind_with_identities` creates a Unix listener
before the Iroh endpoint. `local_zeroization_control_directory_path`
(`runtime.rs:15019-15025`) hard-codes `/tmp/aster-zeroize-<effective_uid>`.
This emulator exposes shell-owned `/tmp`; its shell SELinux domain refuses
socket-file creation there. `TMPDIR` affects the test's state root but not
this upstream control path. No root, SELinux permissive mode, control-channel
bypass, or Aster engine patch was used. This is a separate Android control
path/policy portability boundary, not evidence of a failed redb lock patch.
Offline Event publication, restart and authenticated reconnection assertions
were not reached.

### Redb-only Kotlin/JNI execution (historical blocker)

`spike/runtime` is a test-only Android application without a launcher,
WearTAK SDK, Binder service, release signing configuration, or publication
workflow. Its platform Instrumentation runs native calls on the instrumentation
thread, not the UI thread. It uses the matching verifier component through the
Android library. A debug test APK containing stripped patched x86_64/ARM64 JNI
libraries built and passed `:spike:runtime:lintDebug`; only the x86_64 emulator
executed it.

The public deterministic evaluation fixture generator is
`spike/native/examples/reference_fixture.rs`. It issues one unprotected
reference bundle for scope `weartak/spike` and topic `jni-evaluation`, never an
operational credential. The bundle was imported by binary stdin into the app's
private `files/aster-evaluation/reference.bundle`, mode 0600, with parent mode
0700. No fixture bytes or identity key contents are recorded here.

```text
adb -s emulator-5564 install -t spike\runtime\build\outputs\apk\debug\runtime-debug.apk
adb -s emulator-5564 shell am instrument -w -e stage publish com.weartak.plugin.aster.spike.runtime/.ProbeInstrumentation
```

Actual publication attempt:

```text
INSTRUMENTATION_RESULT: jni_initialized=true
INSTRUMENTATION_RESULT: global_tmp=/tmp
INSTRUMENTATION_RESULT: error=java.lang.IllegalStateException: node I/O: Permission denied (os error 13)
    at com.weartak.plugin.aster.spike.AsterProbe.publishAndQuery(Native Method)
INSTRUMENTATION_CODE: 0
```

App-private identity/store/publication-journal files were created, but selected
node startup failed before publication. No Event receipt was returned. A fresh
instrumentation process separately verified initialization and the control
root's access boundary:

```text
INSTRUMENTATION_RESULT: global_tmp=/tmp
INSTRUMENTATION_RESULT: global_tmp_writable=false
INSTRUMENTATION_RESULT: jni_initialized=true
INSTRUMENTATION_RESULT: pid=2827
INSTRUMENTATION_RESULT: result=JNI_INITIALIZATION_OK
INSTRUMENTATION_RESULT: uid=10082
INSTRUMENTATION_CODE: -1
```

`/tmp` has mode 0771 and shell ownership on this emulator; application UID
10082 cannot write the hard-coded control directory. Matching rustls verifier
and Iroh DNS JNI initialization therefore execute, but this is not a TLS
handshake test or successful selected Event lifecycle. Instrumentation failure
returns code 0 while `adb` itself exits 0: check instrumentation result/code,
not only adb's process status. Successful instrumentation uses code -1.
The `read` stage is provided for a separate-process reopen proof after a
successful `publish`; it was not run because no Event receipt exists.

Next portability decision: upstream Aster needs an explicitly supplied,
validated Android app-private zeroization-control root, used consistently by
node startup and zeroization clients. Preserve owner-only non-symlink checks,
stable path/inode authority, stale/live listener exclusion, and peer credential
authentication. Do not substitute global `TMPDIR`, disable the control channel,
relax permissions, or change SELinux policy. That is an additional Aster
dependency change requiring separate authorization, not part of the approved
redb-only patch.

### Authorized Aster control-directory adapter and successful JNI lifecycle

The user subsequently authorized an isolated Aster app-private control-root
adapter, preserving ownership, symlink/inode checks, live-holder exclusion and
peer authentication. Earlier redb-only failures above remain valid baseline
evidence. [Adapter provenance](../third_party/aster/ANDROID_CONTROL_PROVENANCE.md)
records the pinned revision, exact two-file patch and build override.

The Android-only initializer pins an existing canonical absolute owner-only
directory by path/device/inode in a process-lifetime OnceLock. Every control
lookup revalidates the identity. Rebinding, symlink aliases, unsafe permissions,
missing initialization and overly long Unix socket paths fail explicitly.
Node startup and local zeroization clients share the same lookup. Existing
socket creation, stale/live checks, store/state authority and peer credentials
are unchanged. Non-Android behavior stays upstream. No global TMPDIR, SELinux,
root, TLS, lock or zeroization bypass.

Harness package ID is now `com.weartak.aster.eval`, with private control root
`files/c` mode 0700. The shorter identifier/root budgets the original
inode-derived Unix socket filename under Android's 108-byte sockaddr_un limit.
Kotlin supplies the canonical private directory, not a shell/global path.
Native library explicitly requires feature `android-control-evaluation` plus
the combined redb/Aster Cargo override; default upstream resolution does not
silently enable the adapter.

Executed on isolated Wear OS API-36 x86_64 emulator, application UID 10082:

```text
stage publish:
  result=JNI_PUBLISH_QUERY_OK
  pid=2595
stage read:
  result=JNI_RESTART_READ_OK
  pid=2633
stage reconnect:
  result=JNI_AUTHENTICATED_RECONNECT_RETAINED
  pid=2901
stage read-receiver:
  result=JNI_RECEIVER_RESTART_EVENT_ACK_RETAINED
  pid=3061
event=7ca5134b9819a0bccd1a1f3138c51156be8780f87352c20c55f0d884c9caafdb
INSTRUMENTATION_CODE: -1
```

Each invocation starts a new Android instrumentation process. Offline publish
commits a fixed `weartak-spike-event` payload, queries its exact ID/payload and
shuts down the node. A later PID reopens publisher state and verifies that Event.
Reconnection reopens publisher, starts a separately provisioned receiver and
configures both exact carrier and mission identities over loopback Iroh.
Receiver durably subscribes through its publication journal, polls the exact
Event/payload, requires authenticated contact plus the expected Active mission
peer, acknowledges it and verifies no redelivery. Both nodes shut down;
receiver reopens and queries the Event. A subsequent PID independently verifies
receiver Event retention and durable acknowledgement with no pending delivery.
This is two nodes in one Android process during transfer, not two physical
devices, radios or simultaneously independent Android apps.

An initial query-only reconnect probe exceeded its 120-second deadline. The
completed probe follows upstream consumer-journal/subscribe/poll/ack pattern
and passes; the query-only failure is not silently reclassified as success.

Control regression instrumentation also returned code -1:

```text
CONTROL_UNSAFE_ALIAS_REBIND_REFUSED
CONTROL_INODE_REPLACEMENT_REFUSED_ORIGINAL_RESTORED
CONTROL_MISSING_INITIALIZER_REFUSED
CONTROL_SOCKET_PATH_107_ACCEPTED_108_REFUSED
```

Tests check exact rejection reasons for mode-0777 root, alternate canonical
root after pinning, relative path and symlink leaf. A separate process pins
the root, renames it and installs a fresh same-path inode: reinitialization and
actual node restart both reject the replacement. Restoring the original inode
allows restart/query again. These tests do not simulate every path race, peer
UID attack, or zeroization/crash point.
Missing initialization fails in an actual node restart. An exact boundary
test pins a directory yielding a 107-byte socket pathname and successfully
restarts/queries the node; a directory yielding 108 bytes is rejected before
pinning. At this stage, rejected-peer identity and destructive zeroization
protocol end-to-end tests were unperformed. The additional approved validation
below exercises these paths without further dependency patches.

Reproduction:

```text
cargo +1.97.1 build --manifest-path spike\native\Cargo.toml --lib --example reference_fixture --features android-control-evaluation --target x86_64-linux-android --config <combined-evaluation.toml>
gradlew.bat :spike:runtime:assembleDebug :spike:runtime:lintDebug -PasterJniLibsDir=<patched-ABI-directory>
adb -s emulator-5564 install -t spike\runtime\build\outputs\apk\debug\runtime-debug.apk
adb -s emulator-5564 shell am instrument -w -e stage <stage> com.weartak.aster.eval/com.weartak.plugin.aster.spike.runtime.ProbeInstrumentation
```

Generate disposable bundles with the example using separate serials 1 and 2;
for the distinct-authority check, use optional issuer-seed byte `201` for the
second bundle (default authority seed is `0xb7`);
import them by binary stdin under `files/aster-evaluation/reference.bundle`
and `receiver.bundle`, mode 0600. Run stages `publish`, `read`, `reconnect`,
`read-receiver`, `control-validation`, `control-inode`, `control-uninitialized`,
`control-path-limit`, `zeroize-wrong-authority`, `zeroize-live`, and
`inspect-interrupted-zeroize`. A fresh publish requires
fresh state. Check instrumentation code -1/results, not adb exit status alone.
Exact fixture contents and identity keys are not logged or committed.

### Additional synthetic identity, zeroization and independent-app validation

The user explicitly authorized these bounded tests with disposable synthetic
fixtures only. The two dependency patches remain unchanged. Added harness
code does not alter Aster authorization, TLS, cryptography or writer locking.
Execution used Wear OS API 36 x86_64, not ARM64 or physical watches.

| Check | Actual result |
| --- | --- |
| Wrong carrier identity | `REJECTED_carrier_NO_CONTACT_NO_EVENT`, PID 3459, UID 10082. |
| Wrong mission identity | `REJECTED_mission_NO_CONTACT_NO_EVENT`, PID 3506, UID 10082. |
| Stopped-node zeroization | `ZEROIZATION_STOPPED_COMPLETE_EVENT_PRESERVED_REPLAY_LOCKOUT`, PID 3589. |
| Live-node zeroization | `ZEROIZATION_LIVE_COMPLETE_EVENT_PRESERVED_REPLAY_LOCKOUT`, PID 3548. |
| Live terminal state, new process | `ZEROIZATION_PROCESS_RESTART_COMPLETE_TOMBSTONES_LOCKOUT_REPLAY`, PID 3626. |
| Stopped terminal state, new process | Same terminal restart result, PID 3658. |
| Independent publisher app | `INDEPENDENT_PUBLISHER_CONTACTS=1`, PID 3091, UID 10082. |
| Independent receiver app | `INDEPENDENT_RECEIVER_EVENT_ACK contacts=1`, PID 3045, UID 10083. |
| Independent receiver restart | `INDEPENDENT_APP_RECEIVER_PROCESS_RESTART_EVENT_ACK`, PID 3135; final optimized harness PID 3764, UID 10083. |
| Publisher restart after negative tests/transfer | `JNI_RESTART_READ_OK`, PID 3174; final optimized harness PID 3405, UID 10082. |

All listed instrumentation runs returned code -1. Publisher retained Event ID
`dd463119178665b932c8641d064644eb52b3fd9f2adb8eb1a456b47ce21eb69e`.
Both identity tests observe 20 seconds, require zero authenticated contacts
and at least one failed contact attempt, reject any protected Event delivery,
and verify an empty receiver query. The carrier candidate is an actual unrelated
`NodeIdentity`, not invalid public-key bytes. An earlier invalid-byte candidate
failed parsing with `data is not a valid public key`; that attempt is not
authentication rejection evidence. The mission candidate comes from a separate,
valid synthetic mission fixture.

Stopped and live zeroization use separate fresh synthetic states and fixtures.
Each starts with one committed sealed Event. Receipts must report `Complete`,
mission/carrier identity destruction, the correct live/stopped request flag,
and unchanged Event count/sealed-byte occupancy. Mission and `identity.key`
remain zero-length tombstones. Replay is idempotent. Live facade status fails
with `StateUnavailable`. Separate new Android processes verify durable
`Complete`, tombstones and replay; normal reopening with a still-valid,
different mission bundle specifically fails with
`store is terminally locked out in Complete state`.
This proves logical cleanup and terminal admission, not physical flash secure
erasure, arbitrary power-failure recovery, every interrupted-zeroization point,
or operational protected-provider behavior.

Independent transfer uses two installed test packages:
`com.weartak.aster.eval` and `com.weartak.aster.recv`, different Android UIDs,
separate private storage and separately provisioned identities. Only public
carrier/mission descriptors are exchanged. Receiver starts a durable
subscription and exposes a test readiness marker; publisher reopens an
already committed offline Event and connects through explicit loopback UDP
endpoints `127.0.0.1:43101`/`:43102`. Receiver verifies exact ID/payload,
authenticated contact and expected Active mission peer before acknowledging.
A later receiver PID reopens Event and durable journal and verifies no pending
redelivery. This extends the earlier same-process transfer proof; it remains
same-emulator networking, not two devices, Wi-Fi/BLE, NAT or background service
qualification.

Identity and fresh-state live/stopped zeroization checks were repeated using
the final optimized x86_64 library; the table records those latest PIDs.
Prior terminal states were renamed and retained until app uninstall rather
than overwritten. Separate-process inspections also check unchanged nonzero
sealed-byte occupancy on replay. Both runtime package variants built and
passed lint with matching final x86_64/ARM64 JNI interfaces.

While receiver was active, publisher UID attempted:

```text
adb -s emulator-5564 shell run-as com.weartak.aster.eval ls /data/data/com.weartak.aster.recv/files/c
ls: /data/data/com.weartak.aster.recv/files/c: Permission denied
```

This exercises outer app-private filesystem isolation. It does not directly
exercise the control listener's peer-credential rejection: access failed before
socket connection. No permissions or SELinux policy were relaxed.

#### Distinct-authority rejection and forced interruption

The wrong-authority fixture uses a distinct issuer seed, not merely a different
node serial under the same authority. With one committed synthetic Event,
zeroization rejected the mismatched mission while the node was live with
`request differs from the retained mission artifact`, then rejected it again
while stopped with `different mission authority`. State stayed `Live`; Event
count/sealed bytes and both fixtures and identity remained unchanged. The
correct mission then completed cleanup. A different-serial fixture under the
same issuer was separately observed to share the authority and is not valid
evidence for this rejection.

On the same x86_64 emulator, an app-UID native watcher observed the actual
live-zeroization boundary and sent SIGKILL to the instrumentation process:

```text
KILLED_AT_MISSION_TOMBSTONE pid=15523 iterations=567469
pre-recovery:
  zeroize-live.bundle          0 bytes
  identity.key                32 bytes
  zeroize-interruption-marker 22 bytes
```

`adb am instrument -w -e stage zeroize-live ...` reported `Process crashed`;
that is the intentional interruption, not a successful stage result. A new
instrumentation process ran `inspect-interrupted-zeroize` and recovered:

```text
ZEROIZATION_INTERRUPTED_RESUMED marker=mission=0 identity=32
  pre_state=unreadable-before-recovery:redb storage error: Database repair aborted.
  events=1 sealed_bytes=10522
```

The recovery receipt confirmed `Complete`, zero-length mission and identity
tombstones, and the original Event retained. Another fresh process then
returned `ZEROIZATION_PROCESS_RESTART_COMPLETE_TOMBSTONES_LOCKOUT_REPLAY`,
rechecking durable completion, Event retention, terminal admission lockout and
idempotent replay. Thus one real process-kill interval is covered; other crash
points, commit/fsync failure, power loss, physical flash erasure and protected
production provisioning remain untested. No dependency or security behavior
patch was added for these checks.

Commands used after setting the NDK linker, CC, AR and isolated Cargo homes:

```text
cargo +1.97.1 fmt --manifest-path spike\native\Cargo.toml
cargo +1.97.1 build --manifest-path spike\native\Cargo.toml --lib --features android-control-evaluation --target x86_64-linux-android --config <combined-evaluation.toml> --quiet
gradlew.bat :spike:runtime:assembleDebug :spike:runtime:lintDebug "-PasterJniLibsDir=<patched-ABI-directory>" --quiet --console=plain
gradlew.bat :spike:runtime:assembleDebug :spike:runtime:lintDebug "-PasterJniLibsDir=<patched-ABI-directory>" "-PasterEvaluationApplicationId=com.weartak.aster.recv" --quiet --console=plain
adb -s emulator-5564 shell am instrument -w -e stage <stage> <package>/com.weartak.plugin.aster.spike.runtime.ProbeInstrumentation
adb -s emulator-5564 shell am instrument -w -e stage independent-receiver -e bind 127.0.0.1:43102 -e peer <publisher-carrier>@127.0.0.1:43101=<publisher-mission> -e event <event-id> com.weartak.aster.recv/com.weartak.plugin.aster.spike.runtime.ProbeInstrumentation
adb -s emulator-5564 shell am instrument -w -e stage independent-publisher -e bind 127.0.0.1:43101 -e peer <receiver-carrier>@127.0.0.1:43102=<receiver-mission> -e event <event-id> com.weartak.aster.eval/com.weartak.plugin.aster.spike.runtime.ProbeInstrumentation
```

Stages include `carrier`, `mission`, `zeroize-live`, `zeroize-stopped`,
`inspect-zeroize-live`, `inspect-zeroize-stopped`, `describe-peer`,
`independent-read` and `read`. Do not rerun fresh destructive stages on terminal
states; use newly generated disposable fixtures/state. The receiver package
imports only its own bundle as `reference.bundle`. An unquoted dotted Gradle
property initially split into a task name and failed; quoting the property
resolved it without changing build behavior.

Current harness additions are `spike/native/src/validation.rs`, selected-node
negative reconnect logic in `lib.rs`, Kotlin JNI declarations, instrumentation
stages, and configurable runtime application ID/manifest target package.
No new dependency/security behavior patch is introduced.

### ARM64 emulator runtime attempt

The user subsequently authorized an emulator-only ARM64 runtime attempt. Wear
OS 6.0 `arm64-v8a` system image was installed into the isolated session SDK,
not the shared SDK, and an API-36 small-round AVD was created. Host is x86_64.
No APK was installed on this AVD, and no Android runtime/JNI test executed.

Two starts using the copied isolated emulator package, with `-no-accel` and
first `-gpu swiftshader`, then `-gpu off`, exited before ADB exposed a device
with Windows status **`-1073741819` (`0xC0000005`, access violation)**. Verbose
output reached system-image selection but gave no more specific fault.
Retrying the installed original SDK emulator removed the copied-binary
ambiguity and reported the explicit platform limitation:

```text
INFO | Found AVD target architecture: arm64
FATAL | Avd's CPU Architecture 'arm64' is not supported by the QEMU2 emulator on x86_64 host. System image must match the host architecture.
```

Therefore ARM64 runtime is **blocked by this host/emulator combination**, not
by an observed Aster runtime failure. No architecture-conversion workaround,
physical device, or production install was used. The ARM64 build/ELF/export
checks remain cross-build evidence only; all successful Android JNI results
remain x86_64. Android's
[emulator acceleration documentation](https://developer.android.com/studio/run/emulator-acceleration)
likewise requires matching host/guest architecture for VM acceleration.

Final optimized, explicitly stripped JNI libraries:

| ABI | Bytes | SHA256 |
| --- | --- | --- |
| arm64-v8a | 24,101,816 | `65D75BC6A292027F601A791AEEFD85354B008D80A6A0C259CDAA673D24F2EE1C` |
| x86_64 | 28,698,048 | `CEF1814FEB22D47362C836E4901DF5AD6286C502DA2CF40FC7658C7739503698` |

Measured after `cargo +1.97.1 build --manifest-path spike\native\Cargo.toml
--lib --release --features android-control-evaluation --target <target>
--config <combined-evaluation.toml> --quiet` and NDK
`llvm-strip --strip-unneeded`. ARM64 is AArch64 ELF; `llvm-readelf -Ws`
confirms new `validateSecurity`, `describePeer` and `runIndependentPeer`
exports. This larger test harness contains negative/zeroization instrumentation,
not a minimum production plugin or an acceptable watch-size conclusion.

Final `cargo fmt --check`, patched-feature and default-upstream Clippy
`-D warnings`, runtime variants' assembly/lint and Android library lint passed.
Initial Clippy caught a nested conditional in new harness code; it was corrected
without changing authorization conditions and checks reran. Existing upstream
dependency warnings remain unchanged. Portable native lock was restored from
the saved baseline, SHA256
`B99A0C7681E68786BE9516E9E61A74C9DBCF39F540D539ED33085920E0BA86CE`.
`cargo +1.97.1 metadata --manifest-path spike\native\Cargo.toml --locked
--offline --format-version 1` resolves 409 packages and zero scratch/path
dependencies other than the probe itself. Original pinned checkout is clean;
both documented dependency patch hashes are unchanged.

Merged-branch cleanup is not applicable in this worktree: current branch is
unborn with all project/evidence files untracked. No commit, merged branch or
safe branch-deletion target exists here. Preserve this worktree; no commit,
push, merge, mirror publication or changes in other repositories were made.

### Verification performed

- Unmodified selected library cross-build: Android ARM64, API 30, Rust 1.97.1,
  Android NDK r27d (`27.3.13750724`), succeeded.
- Final selected-node JNI build, Clippy with `-D warnings`, and Rust formatting:
  succeeded.
- AArch64 shared ELF and exports for `initialize`, `publishAndQuery`, and
  `readAfterRestart`: verified with NDK `llvm-readelf`.
- Android library `assembleDebug` and `lintDebug`: succeeded with JDK 17,
  SDK 34, AGP 8.7.2, Kotlin 2.0.21, and Gradle 8.10.2.
- Runtime classpath resolved `org.rustls:rustls-platform-verifier:0.2.0`;
  AAR contains the ARM64 library, Kotlin classes, and consumer shrinking rules.
- Unmodified Android x86_64 CLI startup: failed twice at storage ownership.
- Patched redb storage and range tests: passed on API-36 x86_64; ARM64
  cross-build passed, not executed.
- Patched selected Event process regression: failed at Unix control-socket
  creation under Android shell SELinux policy; Event/reconnect assertions not reached.
- App-private JNI initializer: executed successfully on x86_64; publish
  invocation failed at startup under unwritable global control root.
- Patched optimized ARM64 JNI build and native/fixture Clippy `-D warnings`:
  succeeded. A first lint invocation omitted the NDK CC/AR environment and failed
  in ring; repeating with the required environment succeeded without changes.
- Registry redb source/checksum restored in both repository locks and upstream
  scratch lock; `cargo metadata --locked --offline --no-deps` succeeded for all
  three manifests. Pinned upstream scratch checkout is clean.
- Both-patch Android JNI publication, publisher process restart, authenticated
  reconnection, receiver process Event/ack retention and control-root regressions:
  passed on x86_64. Optimized ARM64 cross-build passed; not executed.
- Final default-upstream and explicit patched-feature native Clippy
  `-D warnings`, formatting, multi-ABI Android app assembly and lint: passed.
  Original upstream Android-only warnings remain in the isolated dependency.
- Final native baseline lock regenerated without overrides; full locked Cargo
  metadata confirms all dependency sources are upstream/registry, no scratch
  paths. Initial offline metadata lacked non-Android cached dependencies;
  ordinary Cargo retrieval into isolated cache resolved that validation boundary.

The stripped optimized library's SHA256 is
`03A00CB97C29707318AEFA5545706157FAF0D86AB9A2431E800E9CD4115B36BF`.
Build artifacts and isolated tooling remain outside version control. Debug
AAR assembly warns that it cannot automatically strip the native library; use
the explicit NDK strip measurement above rather than treating debug size as a
deployment result.

### Build the evaluation probe

Install the pinned Rust toolchain, add `aarch64-linux-android`, and install an
NDK. Set `CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER`,
`CC_aarch64_linux_android`, and `AR_aarch64_linux_android` to the NDK's
API-30 Clang wrapper and `llvm-ar`, respectively. Then:

```text
cargo +1.97.1 build --locked --manifest-path spike/native/Cargo.toml --target aarch64-linux-android
```

Place the generated `libweartak_aster_spike.so` in an `arm64-v8a` directory
under a separate JNI libraries directory. With `ANDROID_HOME` pointing to an
SDK with platform 34 and JDK 17, run:

```text
gradlew.bat :spike:android:assembleDebug -PasterJniLibsDir=<jni-libraries-directory>
```

The library build fails when no native library is provided. It is not a
WearTAK plugin, does not include signing or publishing, and must not be used
with operational provisioning material.

## Experimental WearTAK SDK integration

The `app` module now implements the WearTAK SDK plugin Binder contract,
signature-protected service action, hosted status UI, and asynchronous
`aster.event.publish` action. It accepts only an imported synthetic reference
bundle in app-private storage. It shuts down the selected Aster node after
each action; it is not a resident node, operational provisioning flow, or
network service.

The separate `integration-test-host` app binds from another Android UID, calls
the SDK interface, receives callback/state updates, and verifies the same
Event ID after node reopen. It is a test harness, not the actual WearTAK host.
The host app and plugin were signed by the same debug key and used separate
UIDs (10083 and 10082); emulator package state recorded the signature
permission as granted. The service permission is `signature` protected.

Template SDK AARs were copied from
`weartak-plugins/weartak-plugin-template` at commit
`a4af9f1b9bd015310743a6b0aef4bb0b24d65bf6`. SHA256:

- Debug AAR: `4A02C90B3BEE6A0C63337640E8FE609626B8758BE4C4D359E4EC8AC2710458D2`
- Release AAR: `D0DC15F6F7AD7C70003C832C672483D798DF76E1968B12911AE76914B4A38B4F`

At this initial integration stage the release AAR was present only to follow
repository-local SDK dependency conventions; no release APK, production signing,
or publishing path was added. The later CI work below adds a signed evaluation
distribution path, not production readiness.
The app and host manifests disable backup and exclude app data from cloud
backup and device transfer. Their launcher icon uses the platform placeholder.

Build and lint command, with the patched x86_64 and ARM64 JNI libraries in an
ABI-organized directory:

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug `
  :integration-test-host:assembleDebug :integration-test-host:lintDebug `
  :spike:runtime:lintDebug `
  -PasterJniLibsDir=<abi-organized-jni-libraries>
```

All five tasks passed. Plugin lint reported no issues; host lint reported zero
errors and one `OldTargetApi` warning because the test harness keeps template
target SDK 34. On a disposable Wear OS API-36 x86_64 emulator, the host
instrumentation was run as

```powershell
adb -s emulator-5564 shell am instrument -w `
  com.weartak.plugin.aster.hosttest/com.weartak.plugin.aster.hosttest.AsterWearTakHostInstrumentation
```

It reported
`WEARTAK_SDK_BINDER_PUBLISH_RESTART_READ_OK` with Event ID
`b6073a69e95133dcb2d460f6a42f144bf131cfe10f17dc5b1d3c2bf77aa074cf` on two
consecutive runs. After force-stopping the plugin process, another bind/action
returned the same ID. Thus the proven path includes cross-UID SDK Binder
invocation, callback/state receipt, local Event commit, Aster node reopen, and
process restart. Instrumentation returned code `-1` (success); the output
marker is the harness assertion result. This does not establish remote
delivery, real WearTAK host discovery/UI behavior, ARM64 runtime, or a
production-supported plugin lifecycle.

### 32-bit Knox watch build feasibility

The authorized Samsung SM-L705U reports Android 16/API 36,
`ro.product.cpu.abilist=armeabi-v7a,armeabi`, and `uname -m=armv8l`.
Its Android userspace exposes 32-bit ARM ABIs, not `arm64-v8a`; the existing
ARM64 JNI library therefore cannot be loaded by this device.

With Rust 1.97.1, NDK r27d/API 30, and the same isolated redb/Aster evaluation
patches, the selected-node JNI library cross-built successfully for
`armv7-linux-androideabi`. This compiled the Aster node, redb, Iroh,
cryptography, Android verifier, and JNI code for 32-bit ARM. The optimized
stripped ELF is 16,701,628 bytes (SHA256
`AE0993BE425B5140E933F80653D4549F695434DDEE6F9063530DD3431816276C`) and
exports the expected JNI methods. Upstream `aster-node` emitted two existing
`unused_mut`/`dead_code` warnings; build succeeded.

The app also assembled locally with only
`lib/armeabi-v7a/libweartak_aster_spike.so`, min SDK 30, target SDK 34. APK
size: 19,382,664 bytes. This establishes cross-build and package ABI
compatibility, not watch runtime viability.

The first install was rejected because another installed app already owned
the template's generic `com.weartak.plugin.permission.BIND_PLUGIN` permission.
After separate user approval, a test-only build used
`-PasterBindPluginPermission=com.weartak.plugin.aster.permission.BIND_PLUGIN`;
this kept `protectionLevel=signature` and was requested by the same-debug-signed
host harness. It does not validate the actual WearTAK host's generic permission
contract. The plugin and harness installed without replacing existing apps;
Package Manager selected `primaryCpuAbi=armeabi-v7a`.

The on-watch SDK Binder action reached Aster native storage initialization but
failed before Event commit:

```text
INSTRUMENTATION_RESULT: error=java.lang.IllegalStateException:
Event publish failed: I/O error: unrepresentable lock range
```

The probe created a zero-byte publication journal; no Event receipt or Event
was created. Android's 32-bit `libc` binding defines `off_t=c_long`, and its
`flock.l_start`/`l_len` therefore cannot represent the lock range redb
requested. The evaluation redb patch currently selects `libc::flock` for all
Android ABIs; the same path worked on x86_64, where `c_long` is 64-bit.
`libc::flock64` exposes 64-bit offsets on Android ARMv7, but no redb lock
patch or runtime workaround was applied. A narrowly scoped 32-bit lock
adaptation and additional lock tests require separate approval.

Both newly installed test packages were uninstalled after capturing the
failure; their app-private synthetic fixture/state was removed. The helper and
temporary fixture on the watch were also removed. Existing apps, data,
settings, and Knox protections were left unchanged. No ARMv7 Event commit,
reopen, network sync, battery, or background test passed. The main blocker is
the unrepresentable redb lock range, not ABI packaging or Binder permission.

### ARMv7 flock64 follow-up and on-watch Event result

The initial failure above was reproduced and addressed only in the isolated
evaluation patch. Android documents 32-bit `off_t` as signed 32-bit; Bionic
LP32 `fcntl` dispatches to `__fcntl64`, and `struct flock64` uses 64-bit
offsets ([Bionic implementation](https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/bionic/fcntl.cpp),
[layout](https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/include/bits/flock.h),
[32-bit ABI](https://android.googlesource.com/platform/bionic/+/refs/heads/main/docs/32-bit-abi.md)).
The supplemental
[`android-armv7-ofd-locks.patch`](../third_party/redb/android-armv7-ofd-locks.patch)
selects `libc::flock64` only for Android 32-bit targets; it does not change the
lock command, syscall, or other platform paths. The preparation script applies
it after the base Android patch. No full dependency source or absolute path was
added to normal Cargo resolution.

On the same authorized SM-L705U/API 36, the actual patched redb range-lock
suite passed (1 test, 0 failures), including overlapping/shared locks,
exclusive exclusion, range queries, blocking/unlock, invalid ranges, and
offsets above 8 GiB. The ARMv7 storage probe also passed all checks:

```text
SAME_PROCESS_REFUSED=true
CROSS_PROCESS_REFUSED=true
REOPEN_VALUE_RETAINED=true
HOLDER_REFUSED=true REOPEN_AFTER_KILL_VALUE_RETAINED=true
DIRTY_WRITER_REFUSED=true UNCOMMITTED_WRITE_ROLLED_BACK=true
OFD_CROSS_PROCESS_REFUSED=true
OFD_SAME_PROCESS_REFUSED=true OFD_RELEASED_ON_CLOSE=true
OFD_HOLDER_REFUSED=true OFD_RELEASED_AFTER_KILL=true
```

The ARMv7 plugin and separate Binder test host APKs assembled; app and host
lint passed. The plugin APK contained only
`lib/armeabi-v7a/libweartak_aster_spike.so`, and Package Manager selected
`primaryCpuAbi=armeabi-v7a`. After confirming both package IDs were absent,
the two test-only packages installed with a unique
`com.weartak.plugin.aster.permission.BIND_PLUGIN` signature permission. This
avoided overwriting the pre-existing generic permission owner; it does not
validate binding from the actual WearTAK host.

The host Binder action committed one synthetic Event and reopened the Aster
node to read the same ID. After force-stopping the plugin app and binding
again, a fresh plugin process returned that same ID:

```text
WEARTAK_SDK_BINDER_PUBLISH_RESTART_READ_OK
event=c99b613f36cf99f521a05e574b31d8a1e1a97ada6bacfa2d4d54dc8e384854a0
INSTRUMENTATION_CODE: -1
```

The app-private receipt matched the ID. The test used only an unprotected,
disposable synthetic fixture; it did not test operational provisioning,
two-watch synchronization, or network delivery. Both newly installed
packages were uninstalled after the test, clearing their private data, and
the test fixture/generator directory under `/data/local/tmp` was removed.
Existing apps, data, settings, permission owners, and Knox protections were
left unchanged.

## Android patch maintenance

The Android redb OFD-lock and Aster private-control-directory changes remain
separate, narrow patch files with pinned upstream provenance. Full dependency
source is prepared in isolated scratch directories and selected only by
explicit evaluation Cargo overrides; normal manifests and lockfiles retain
upstream dependencies. The short-term maintainable path is to preserve those
pins, patch checks, test evidence, and scratch-only overrides. Do not vendor
full patched sources or make local absolute paths the repository's normal
dependency resolution.

The long-term path is upstream-reviewed Android OFD locking in redb and an
upstream-configurable app-private control root in Aster, followed by removal
of local overrides. No upstream submission or public fork was made. Until
those changes are reviewed and available in pinned releases, the patches are
evaluation-only and do not establish production support.

## Application and security boundaries

The [selected Event quickstart](https://github.com/defenseunicorns/aster/blob/d8b8e4141fc6b1accc6f183e6ad65d03bc3b0679/docs/quickstart/selected-event-api.md)
documents local commit, bounded queries, durable subscription/poll/ack, and
contact status. Local publish is not remote delivery; contact counters are not
proof of remote application consumption. Consumers must handle redelivery and
acknowledge only after their own durable side effect.

The [C ABI](https://github.com/defenseunicorns/aster/blob/d8b8e4141fc6b1accc6f183e6ad65d03bc3b0679/bindings/c/README.md)
exposes the broader semantic API, not selected live node bindings or selected
Event page controls. It accepts an unprotected provisioning bundle and does
not expose a protected-provider entry point. Its handles and ownership rules
cannot simply be projected into Kotlin. The evaluation JNI bridge instead uses
the selected Rust node API directly. Call `AsterProbe.initialize` once with
the application context before either probe method, and execute blocking
operations off the UI thread. `publishAndQuery` publishes a fixed local Event;
`readAfterRestart` reopens the node and looks up the returned ID and payload.
Both methods shut the node down before returning. They are experimental and
accept only unprotected reference mission bundles. The additional bounded
`reconnect` probe exercises two selected nodes over configured loopback Iroh;
none of these methods implements a persistent WearTAK service.

Upstream says its protected provisioning provider contract lacks an admitted
operational implementation. Never commit credentials, log provisioning data,
reuse one node's bundle for another node, or treat a public disposable fixture
as an operational credential. Android Keystore integration, recovery, storage
encryption, backup policy, and a protected provider remain security design and
validation gates, not guarantees supplied by this report.

Selected direct Iroh uses independently admitted carrier and mission identities;
carrier authentication alone is not mission authorization. Direct endpoints
require explicit configuration, and the selected direct path does not provide
automatic address discovery, port mapping, or dynamic-NAT policy. Relay and
nearby discovery have separate constraints; no selected Android BLE driver is
established. See [carriers and contacts](https://github.com/defenseunicorns/aster/blob/d8b8e4141fc6b1accc6f183e6ad65d03bc3b0679/docs/transports.md)
and [security](https://github.com/defenseunicorns/aster/blob/d8b8e4141fc6b1accc6f183e6ad65d03bc3b0679/docs/security.md).

## Runtime and validation gates

1. Local Event commit/reopen passed on the x86_64 emulator and ARMv7 watch.
   Extend with disk-full, interrupted-commit, and reboot recovery tests.
2. Authenticated two-node reconnection passed on one x86_64 emulator and on
   two separate ARMv7 watches over one Wi-Fi network with explicitly
   configured direct peers. Discovery, relay/Bluetooth transport, roaming,
   and other carrier/network transitions remain untested.
3. Selected-node JNI passed on the x86_64 emulator and SM-L705U ARMv7 using a
   test-only signature permission. ARM64 runtime and actual WearTAK host
   permission/service integration remain unverified. Keep blocking operations
   off the UI thread and close native nodes; do not substitute the reference C
   ABI test for selected-node evidence.
4. Measure storage growth, disk-full and interrupted-write behavior, startup
   recovery, memory, CPU, radio activity, and battery drain. Test process death,
   network transitions, reboot, and background restrictions before proposing
   any device workflow.

App-private storage and Android backup behavior need explicit policy; storage
durability and encryption must not be assumed. Deferrable work can use
WorkManager, but scheduling is not immediate or continuous. Foreground services
have platform and policy restrictions and cannot be assumed to provide an
always-on mesh. See Android's
[background work guidance](https://developer.android.com/develop/background-work/background-tasks)
and [battery guidance](https://developer.android.com/develop/background-work/background-tasks/optimize-battery).

## Two-watch ARMv7 direct-Wi-Fi validation

On 2026-11-12, a disposable ARMv7 instrumentation APK was installed on the
authorized SM-L705U (Android 16/API 36) and SM-L715U (Android 17/API 37). Both
reported `armeabi-v7a,armeabi`. Each watch generated its own unprotected
reference fixture from the same public deterministic evaluation authority;
node serials were distinct. The fixtures were not reused across devices and
were not operational credentials.

The Knox watch committed one synthetic Event, then ran as publisher on a
direct Iroh UDP peer configured to the personal watch. The personal watch ran
as an independent receiver, authenticated the publisher, acknowledged the
Event, and retained it after instrumentation process restart. The publisher
also reopened and read the same Event after restart.

| Evidence | Result |
| --- | --- |
| Publisher commit/query | `JNI_PUBLISH_QUERY_OK` |
| Receiver authenticated contact and Event acknowledgement | `INDEPENDENT_RECEIVER_EVENT_ACK contacts=1` |
| Publisher authenticated contact | `INDEPENDENT_PUBLISHER_CONTACTS=1` |
| Receiver restart/read | `INDEPENDENT_APP_RECEIVER_PROCESS_RESTART_EVENT_ACK` |
| Publisher restart/read | `JNI_RESTART_READ_OK` |
| Event ID on both watches | `07487d320a6a7956f5982eead7abc01f5ac590690f4c0f4b33acec88930eb0e8` |

### Wi-Fi interruption/recovery follow-up

A second synthetic Event test kept the receiver process and node active while
the personal watch's Wi-Fi was disabled for about 25 seconds. The publisher
node started while that peer was unreachable and remained active until Wi-Fi
returned. The timed restore succeeded (`wifi_on=1`); the watch's Wireless
Debugging endpoint changed, and the receiver's ADB shell disconnected during
the interruption. After reconnecting through mDNS, the receiver's durable
`received-event` matched publisher Event ID
`e0a40e0a9f589f2ca191bc9ff9f5fd1c4d6131670bba2114523364db0d1bfe80`. A fresh
receiver process reported
`INDEPENDENT_APP_RECEIVER_PROCESS_RESTART_EVENT_ACK`; the publisher reported
`INDEPENDENT_PUBLISHER_CONTACTS=1` and `JNI_RESTART_READ_OK`.

A receiver-only setup attempt first timed out before the publisher was started
(`contacts=0, failed=8`); it was restarted for the actual recovery test.
This verifies an already-running receiver can receive and durably acknowledge
an Event after its Wi-Fi interface returns while the publisher remains active.
The receiver's original instrumentation output was lost with the ADB
connection, so its acknowledgement was verified from the durable receipt and
fresh-process read instead. It does not test a previously established peer
session surviving a drop, roaming, IP changes, relay/BLE, reboot, or other
network transitions.

### Knox physical identity-rejection checks

The authorized SM-L705U ran the `carrier` and `mission` validation stages
with distinct disposable synthetic fixtures. Each stage used local loopback
nodes inside the test app's sandbox; the personal watch was not involved.
Both returned instrumentation code `-1` and rejected the candidate before
authenticated contact or Event delivery. The publisher's Event remained
readable after a separate process restart.

| Check | Result |
| --- | --- |
| Publish baseline Event | `JNI_PUBLISH_QUERY_OK`, ID `f8ec58ca6994cfbd73f0f1d0a690e2869eb33910ab0431d64b0631b76c1a3bdb` |
| Wrong carrier | `REJECTED_carrier_NO_CONTACT_NO_EVENT`, UID 10213 |
| Wrong mission | `REJECTED_mission_NO_CONTACT_NO_EVENT`, UID 10213 |
| Restart read | `JNI_RESTART_READ_OK`, same Event ID |

The test package and its fixtures were removed afterward. These checks establish
ARMv7 app-sandbox behavior for the negative identity cases, not wrong-identity
rejection across two devices or production provisioning.

Together, the tests prove synthetic Event transfer between these two ARMv7
devices on the same Wi-Fi network with manually configured peer addresses.
They do not prove discovery, BLE/nearby or relay transport, network roaming,
background or battery behavior, protected provisioning, actual WearTAK host
integration, or production suitability. The test APK, per-watch fixtures, and
temporary files were removed; pre-existing apps, data, and settings were left
untouched. No code/dependency behavior patch, commit, push, release, or
publication was made.

Revisit production readiness only after the remaining runtime gates and a
separate operational security review are satisfied.

## Host lifecycle and signed evaluation CI (2026-11-12)

Plugin service discovery no longer loads JNI on Android's service main thread.
Android context/worker setup is independent of native availability. The bounded
Event worker checks for an explicitly imported synthetic bundle before loading
JNI and pinning the same private control root. A missing library, ABI, or JNI
entry point produces a terminal failed action and host error callback instead
of crashing service discovery or leaving the action pending. Native Event
scope, topic, payload, receipt/reopen behavior, and dependency patches are
unchanged. This does not start a persistent peer or create credentials.

Legacy and V2 initialization share metadata validation and transition to READY.
Host actions are rejected unless the host lifecycle is RUNNING; stopped and
shutdown plugins cannot start new host-requested operations. Already accepted
bounded operations are allowed to finish and close their nodes; stop is not
native cancellation. The standalone activity still invokes the bounded worker
directly without requiring the optional ATAK Companion or a bound WearTAK host.

Source inspection pinned the current host to
[`00290203f9a6bb09870c53f2cdac0e110ed88125`](https://git.tak.gov/core/weartak-core/wearos-tak-civ/-/tree/00290203f9a6bb09870c53f2cdac0e110ed88125)
and current SDK to
[`86bdc8b04797c2cf6caa30e78e796abac1c2a330`](https://git.tak.gov/weartak-plugins/weartak-plugin-sdk/-/tree/86bdc8b04797c2cf6caa30e78e796abac1c2a330).
The host accepts API 1 and calls V2 initialization for that version. It already
has transport/route-status extensions; no core API change is established as
necessary by this inspection. The old bundled SDK AARs do not contain those
transport extension classes. The plugin does not negotiate them, implement
host chat/CoT egress/ingress, or publish route status. Replacing an AAR alone
would not implement that transport.

The current host renders STATUS/KEY_VALUE_LIST titles/subtitles rather than
the plugin's arbitrary state keys. Schema queries now project the current
message and latest local Event ID into those rendered fields. The existing
main-UI-state callback already triggers schema refresh in the inspected host.
Messages fit the SDK's 256-byte subtitle budget without splitting UTF8.
The bundled SDK truncation helper retained a partial leading byte in the
multibyte regression; a local boundary check avoids that defect without
changing the SDK/core. Cards retain an explicit evaluation label and do not imply peer
delivery; no hosted action buttons or transport settings are invented.

Local validation uses Robolectric, not a physical watch, Android emulator,
remote Binder transaction, or actual WearTAK process. Four regression tests
run against each debug/release SDK AAR: discovery and callback-based missing
fixture/native failures; legacy/V2 start; and rejection before start, after
stop, and after shutdown; plus hosted error projection and a multibyte
subtitle budget. The JNI-unavailable case is intentional on the JVM.
Nine Python contracts cover publication gates, artifact/provenance tampering,
prerelease-only publication after download verification, non-owned release
protection, stale pipelines, credential-safe HTTPS redirects, and dependency
notice collection. Both app and test-host lint complete with no errors;
existing launcher/target-SDK warnings are not changed.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:testReleaseUnitTest `
  :app:lintDebug :integration-test-host:lintDebug `
  -x :spike:android:preBuild
python -B -m unittest discover -s ci -p 'test_*.py' -v
```

The excluded task is the native-library presence check for **native-free JVM
tests only**. This command does not assemble, validate, sign, or publish an APK.
Native packaging jobs must run the normal check with real JNI libraries.
Previous JNI artifacts/worktrees were unavailable; no prior artifact was
silently substituted. New Android/JNI validation therefore belongs to the
canonical pipeline, not the local JVM evidence.

The CI signing/artifact model follows inspected canonical
[Lattice `15f4a317`](https://git.tak.gov/weartak-plugins/weartak-plugin-lattice/-/tree/15f4a31798f02f0eb0d1084c0b1dab6ce013b88f)
and [Sextant `c4e4ba05`](https://git.tak.gov/weartak-plugins/weartak-plugin-sextant/-/tree/c4e4ba0576d045485277e5de2cb907983b161750)
patterns, with a deliberately separate evaluation channel:

- Native CI rebuilds ARMv7 and ARM64 from the pinned Aster revision with Rust
  1.97.1 and NDK r27d/API 30. It verifies upstream archive checksums, applies
  the retained evaluation patches in scratch copies, resolves their override
  graph there, and uses the resulting lockfile for the build. The tracked
  portable lockfile/cache is not rewritten. Resolved dependency licenses and
  supplied notices, the resolved lockfile, patch/AAR/JNI checksums, and project
  notices are retained. The NDK checksum is Google's published Linux SHA1;
  redb uses the documented SHA256.
- Host tests/lint and real native APK assembly must pass before a protected,
  canonical `main` push can sign. Signing consumes inherited protected
  WearTAK CI variables without copying keys into source or artifacts; its
  temporary keystore is removed after the job. APK checks require exactly the
  existing WearTAK plugin signer
  `1579b600cd84e619e76a91c2db6b031938c999bc2aad926b9a51ec2b166d348d`,
  package/version identity, both ARM JNI libraries, and embedded legal assets.
- Signed evaluation output is
  `WearTAK-Plugin-Aster-0.1.0-evaluation-p<project-pipeline-IID>.apk`,
  with checksum and source/pipeline/job/signer provenance.
  Version code uses the project pipeline IID. This is not a production
  version bump or operational-readiness assertion.
- GitHub mirroring requires masked/protected `ASTER_GITHUB_TOKEN` with
  Aster repository Contents:write, and an administrator-created fixed
  `TAK.gov-Forge-CI-CD-Evaluation` anchor tag on the mirror. It never pushes
  or changes source refs. Publication stays a draft until uploaded APK bytes
  are downloaded and verified; it always remains a prerelease, never latest.
  Existing assets are retained, and foreign/stable releases or same-pipeline
  substitutions are refused. Release text discloses that fixed-tag source
  archives may differ from the authoritative canonical source SHA.

At configuration time the group exposed the required signing variable names,
but actual key validity is established only by the protected signing job.
Read-only GitHub requests with existing Sextant, LACE, and goTenna publisher
credentials each returned HTTP 401. No token was copied or scope expanded;
401 establishes credential rejection, not its cause. A valid publisher token
must be supplied through
[Aster CI/CD Variables](https://git.tak.gov/weartak-plugins/weartak-plugin-aster/-/settings/ci_cd),
never chat/source/logs. Canonical CI lint accepted this configuration.

No watch was modified in this work. Production-suitable deployment remains
blocked on a protected provisioning/provider design, distinct durable
per-watch identities, a native peer lifecycle/host transport adapter, real
host trust/discovery tests, and background/resource/battery/recovery
qualification. Signed evaluation packaging does not remove those gates.
