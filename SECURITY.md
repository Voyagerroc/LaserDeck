# Security reporting

Report suspected vulnerabilities privately to
[erol@voyagerroctechnologies.com](mailto:erol@voyagerroctechnologies.com).
Include the affected commit/version, Android/device details, impact and a
minimal reproduction with synthetic messages/photos. Do not publish real
messages, location, signing keys or other people's device identifiers.
No response or fix deadline is guaranteed.

The current `main` source is maintained. A source fix, JVM test or debug APK
does not establish that a distributed release is fixed or physically tested.
Use [release provenance](docs/RELEASE.md) to distinguish those states.

Bluetooth transport, device permissions and any future application-layer
encryption have separate boundaries. Do not infer end-to-end encryption,
measured radio range or hardware acceptance from the presence of source code.
