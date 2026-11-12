# WearTAK Plugin Aster

## What it includes

This repository is an evaluation-only Android integration of Aster's selected
Event stack as an optional WearTAK plugin. It does not modify WearTAK core.

- `app` implements the WearTAK SDK Binder contract, hosted status UI, and an
  asynchronous action that commits or verifies one synthetic local Event.
- `integration-test-host` is a separate test app that binds through the SDK
  Binder contract and checks callbacks, Event receipt, and node-reopen reads.
- `spike` contains the JNI and Android runtime probes.
- `third_party` retains narrow, evaluation-only Android portability patches
  and their upstream provenance.

The test action requires an explicitly imported synthetic reference bundle.
This is not a production plugin, protected provisioning flow, persistent
network service, or release APK. The Binder host is a harness, not the actual
WearTAK host application.

## Build and test

Prerequisites: JDK 17, Android SDK (platform 34), Android NDK r27d, and Rust
1.97.1 with the Android target. Build native JNI libraries with the isolated
patch overrides described in
[the feasibility report](docs/android-feasibility.md); normal Cargo
dependencies remain pinned to upstream sources.

With ABI-organized libraries containing `libweartak_aster_spike.so`:

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug `
  :integration-test-host:assembleDebug :integration-test-host:lintDebug `
  :spike:runtime:lintDebug `
  -PasterJniLibsDir=<abi-organized-jni-libraries>
```

Run the `integration-test-host` instrumentation only on a disposable emulator
with the plugin installed and synthetic fixture imported. It verifies SDK
Binder calls and local Event persistence, not remote delivery or the real
WearTAK host. See the report for the exact emulator evidence and remaining
gates.

## Notes

The project follows the WearTAK plugin template's Gradle, SDK AAR, service
action, and signature-protected Binder conventions. SDK AAR provenance and
hashes are recorded in the feasibility report. Do not copy template signing
material or enable production signing/release publication for this spike.

The ARMv7 evaluation test passed on the authorized SM-L705U and SM-L715U.
Two separately provisioned test nodes synchronized and acknowledged one
synthetic Event over explicitly configured direct Wi-Fi peers. Receiver
remained active through a brief Wi-Fi interruption, then received and retained
the Event after recovery; publisher also retained it across process restart.
This does not prove discovery, relay/Bluetooth transport, background
operation, or continuous mesh. Aster remains evaluation-stage; ARM64 runtime,
protected provisioning, actual WearTAK host integration, battery, and broader
network qualification remain unproven. Test apps and synthetic watch data
were removed.

On Knox, distinct synthetic wrong-carrier and wrong-mission loopback checks
were rejected without contact or Event delivery; the baseline Event remained
readable after process restart. These were local identity checks, not
cross-device rejection tests.

Original project material is licensed under GPL-3.0-or-later; see [LICENSE](LICENSE).
Aster and the adapted native probe lifecycle retain Apache-2.0 terms; see
[Aster license](third_party/aster/LICENSE) and
[upstream third-party notices](third_party/aster/THIRD_PARTY_NOTICES.md).
Preserve applicable third-party licenses and notices when distributing
dependencies. The Gradle wrapper scripts retain their Apache-2.0 notices.
The evaluation-only redb portability patch retains upstream Apache-2.0 OR MIT
licensing; see [provenance](third_party/redb/PROVENANCE.md).
The launcher icon uses the full-color Doug mascot from Defense Unicorns'
[Brand Toolkit](https://defenseunicorns.com/brand-toolkit/). This third-party
brand asset is not covered by the project's GPL-3.0-or-later license.
