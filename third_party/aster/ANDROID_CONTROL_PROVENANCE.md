# Evaluation-only Android control-directory adapter

Original Aster revision:
`d8b8e4141fc6b1accc6f183e6ad65d03bc3b0679`.
Source: https://github.com/defenseunicorns/aster.
License: Apache-2.0; existing LICENSE and THIRD_PARTY_NOTICES.md retained here.

`android-control-directory.patch` changes only `aster-node/src/lib.rs` and
`aster-node/src/runtime.rs`. Original checkout stays unchanged; full patched
checkout lives in isolated scratch. No engine fork/publication is implied.
Patch SHA256:
`635E01C4495689BD8926969ADCE128F9CF3773A3D6EF3F4CB5C5A61327C70496`.

Android callers must explicitly initialize an existing canonical, absolute,
owner-only non-symlink application-private directory. Initialization pins its
canonical path, device and inode for process lifetime. Reinitialization is
idempotent only for the same identity. Every node/client control-directory
lookup revalidates permissions and identity. A missing initializer, changed
directory identity, relative/symlink alias, unsafe permissions or pathname too
long for Android's Unix sockets fails explicitly.

The original Unix listener, socket ownership/permissions, stale/live listener
checks, store/state inode binding and both peer-credential checks are unchanged.
Non-Android paths retain upstream behavior. No environment-variable fallback,
global TMPDIR override, root access, SELinux change or control-channel bypass.

The initializer does not prove a directory belongs to Android's application
sandbox merely from its ownership; application caller must choose that root.
Current Kotlin harness supplies `filesDir/c`, resolves Android's directory
alias to a canonical path, and creates the directory with mode 0700. Package
ID is short enough for the original inode-derived Unix socket filename.
Production integration must budget the complete canonical socket pathname.

Prepare a separate pinned checkout and apply:

```text
git apply --check <repository>\third_party\aster\android-control-directory.patch
git apply <repository>\third_party\aster\android-control-directory.patch
```

Run these inside the separate checkout, never the pristine Cargo cache or core.
Combine the approved redb override with an explicit Git-source Cargo patch:

```toml
[patch.crates-io]
redb = { path = '<isolated patched redb>' }

[patch.'https://github.com/defenseunicorns/aster']
aster-node = { path = '<isolated pinned patched Aster>\crates\aster-node' }
```

```text
cargo +1.97.1 build --manifest-path spike\native\Cargo.toml --lib --features android-control-evaluation --target x86_64-linux-android --config <combined-evaluation.toml>
```

The feature explicitly opts the JNI adapter into the locally patched API.
Without it, control-directory configuration throws an evaluation-build-required
error. Normal repository dependency resolution remains pinned upstream;
save/restore baseline locks around override builds. Cargo may resolve separate
Git and path instances of transitive Aster packages in this isolated graph.
This is an evaluation boundary, not a published portable dependency policy.

Executed directory regressions: unsafe-mode/relative/symlink/rebinding rejection;
same-path inode replacement rejected by initializer and actual node restart;
original inode restoration succeeds; missing initializer fails; 107-byte Unix
socket pathname supports node restart/query and 108 bytes fails explicitly.
See feasibility report for JNI Event persistence/reconnect results and
remaining identity/zeroization/race qualification gaps.
