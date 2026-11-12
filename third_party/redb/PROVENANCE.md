# Evaluation-only redb Android patch

Original: redb 4.4.0, crates.io archive `redb-4.4.0.crate`.
SHA256: `39178e0a867f9e3eaa6292a4ab1dd3613f2519c5b564341e1e7b29b270736cc4`.
Archive VCS revision: `7061f7e60d4573311c0636e161d417b93e9fd2b1`.
Upstream: https://github.com/cberner/redb.
Upstream licenses: Apache-2.0 OR MIT; both notices retained here.

`android-ofd-locks.patch` changes the normalized/source Cargo manifest libc
platform gate and `range_lock.rs` platform gates. Android 64-bit uses Bionic
`libc::flock` and `libc::fcntl` with OFD lock commands.
`android-armv7-ofd-locks.patch` selects `libc::flock64` on 32-bit Android while
retaining Bionic `fcntl` and the same OFD commands. Bionic's LP32 `fcntl`
dispatches to `__fcntl64`; its `flock64` carries 64-bit offsets. Existing
shared/exclusive, blocking, query, unlock, range validation, EINTR retry, and
native error handling remain unchanged. Linux, Apple, Windows, and other
platform behavior remain unchanged.

The selected redb code cross-built for Android x86_64, ARM64, and ARMv7 with
API-30 NDK linkers. The ARMv7 range/storage tests and the local Event app
integration also passed on one authorized SM-L705U, Android 16/API 36. This is
bounded evaluation evidence, not general 32-bit Android or production support.
The app test used an isolated signature permission, not the actual WearTAK host
permission contract.

Full dependency source stays outside this repository. Explicit evaluation
override required; normal Cargo manifests/locks retain unmodified redb.
No upstream fix, public fork, publication, or production approval is implied.

Prepare a fresh directory from the original archive:

```powershell
.\spike\prepare-redb-patch.ps1 -ArchivePath <redb-4.4.0.crate> -Destination <new-scratch-directory>
cargo +1.97.1 build --manifest-path spike\storage-probe\Cargo.toml --target x86_64-linux-android --config <new-scratch-directory>\redb-evaluation.toml
```

Cargo overrides alter the selected manifest's lockfile. Preserve baseline
lockfiles before evaluation and restore those saved baseline files afterward.
`cargo update -p redb` without the override did not resolve the local package
identity in this experiment; the original registry source/checksum lines were
restored explicitly, with locked baseline resolution checked afterward.
Do not publish an absolute local path as a portable dependency resolution.

`spike/storage-probe/range-tests.rs` includes the actual patched source via
`REDB_RANGE_LOCK_SOURCE`; compile as a Rust test executable with the Android
target/linker and matching compiled libc rlib. Run on Android with `TMPDIR`
set to a writable test directory. Storage probe requires a fresh database
path each time. See the feasibility report for executed evidence and limits.
