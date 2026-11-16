# Release provenance checklist

This checklist prepares reviewable release evidence. It does not publish an
APK, create a signing key, or establish Google Play/hardware acceptance.

1. Record the source commit, Gradle `versionName`/`versionCode`, build
   command, JDK/SDK versions and actual CI run URL. Match them against
   `android/play/site/surum.json` before a planned distribution update.
2. Run JVM tests and a debug build as documented in the README. Keep the
   result separate from the two-phone acceptance results in
   [android/README.md](../android/README.md).
3. A maintainer builds/signs the intended release using existing private
   signing material outside Git. Do not replace an established signing
   identity. An unsigned `assembleRelease` output is not a publishable APK.
4. Inspect the actual release APK's permissions and signature; record its
   exact filename, byte size, SHA-256 and signer certificate SHA-256.

Windows PowerShell, with an actual artifact path and installed SDK build tools:

```powershell
$apkPath = 'C:\path\to\the-actual-release.apk'
Get-FileHash -LiteralPath $apkPath -Algorithm SHA256
& "$env:ANDROID_HOME\build-tools\36.0.0\apksigner.bat" verify --verbose --print-certs $apkPath
```

Linux/macOS, replacing the artifact path and build-tools version as needed:

```sh
sha256sum /path/to/the-actual-release.apk # macOS: shasum -a 256 <path>
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs /path/to/the-actual-release.apk
```

5. Compare version metadata, signature identity and hash with the exact
   artifact offered at the chosen download URL. A source manifest is not
   proof that the live APK matches it. Preserve the checksum and signing
   fingerprint in release notes; never include keys, passwords or keystore
   contents.
6. List which devices and scenarios were actually tested, with results and
   limitations. Mark anything not tested explicitly. Play internal/closed
   testing, sideload availability and production publication are distinct.

## Public artifact inspected on 16 November 2026

The existing [LaserDeck 2.1.0 APK](https://laserdeck-ptt.voyagerroc.workers.dev/laserdeck-ptt-2.1.0.apk)
was downloaded and inspected without re-signing it. Android SDK Build Tools
36.1.0 `apksigner verify --verbose --print-certs` exited successfully;
`aapt` reported the metadata below.

| Field | Observed value |
| --- | --- |
| Package | `com.voyagerroc.laserdeck.ptt` |
| Version | `2.1.0` / code `14` |
| Minimum / target SDK | `26` / `36` |
| Signature verification | v2 and v3 verified |
| APK SHA-256 | `dfbf29c785c5a059d53d16450f66e0dd83d3f6572c7661f355fd2b06d0b74e39` |
| Signer certificate SHA-256 | `2991a38afba03ddd29ceb0f35956819632e0bc030a21885fd17c5372d37962aa` |

This records one downloaded artifact at that date. A later download must be
checked again. Its metadata agrees with the checked-in version fields, but
there is no source-to-binary build attestation or SourceStamp verification
claim. Signature verification does not establish device behavior, absence
of vulnerabilities or Google Play approval. No physical-device acceptance
test was performed for this record. A future GitHub release may link this
existing artifact and its checksums; publication remains a separate step.
