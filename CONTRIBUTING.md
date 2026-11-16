# Contributing

Start with the JDK 17 / SDK 36 setup and debug/JVM commands in the
[README](README.md) and [Android guide](android/README.md). Keep changes small
and explain the trigger, resulting behavior and validation actually run.

Behavioral fixes should include a meaningful regression test where possible.
Changes to radio, audio, permissions or lifecycle also need a documented
device acceptance plan; passing JVM tests does not prove device behavior.
Use synthetic messages, photos and coordinates, and state untested cases.

Never commit `local.properties`, signing files, APK/AAB outputs, credentials
or personal test records. Report vulnerabilities through [SECURITY.md](SECURITY.md).
Release publication and existing signing identities are maintainer actions;
see [docs/RELEASE.md](docs/RELEASE.md).

CI retains available HTML unit-test reports for 14 days and debug APKs for 7 days, including after a failed test/build. Missing artifacts are reported without masking the original failure. Debug artifacts are not release-signed store distributions.
