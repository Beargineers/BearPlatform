# Releasing

BearPlatform is published to [Maven Central](https://central.sonatype.com/namespace/nl.beargineers) as
`nl.beargineers.platform:core`, `nl.beargineers.platform:ftc` and `nl.beargineers.platform:gradle-plugin`, with the
plugin's marker `nl.beargineers.platform:nl.beargineers.platform.gradle.plugin`, which lets Gradle find the plugin by
its id. All of them share one version. Pushing a git tag does the release:

```
git tag v0.2.0
git push origin v0.2.0
```

The [Release workflow](.github/workflows/release.yml) runs the tests, then signs, uploads and publishes that version.
The version comes from the tag (`v0.2.0` publishes `0.2.0`); the one in `gradle.properties` is only for local builds.
After 10–30 minutes the new version can be downloaded, and its API docs appear on
[javadoc.io](https://javadoc.io/doc/nl.beargineers.platform/core).

A Maven Central version can never be changed or deleted. A broken release gets fixed by the next one.

## Versions

While the version starts with `0.`, any release may break the API. Breaking changes wait for the time between
seasons; during a season, releases only fix and add. Every release that breaks something says in its notes how to
update robot code.

## One-time setup

Done once per repository, not per release.

### Maven Central account and namespace

1. Sign in at [central.sonatype.com](https://central.sonatype.com) with the team's account.
2. **Namespaces → Add Namespace**: `nl.beargineers`. The portal shows a verification key.
3. Add a DNS **TXT** record with that key to `beargineers.nl`, then press **Verify** on the portal. The record can be
   removed once verified.
4. **Account → Generate User Token**. The token's username and password go into the GitHub secrets below; they are not
   the account's login.

### Signing key

Maven Central needs every file signed. Any key works, and a new key can replace a lost one.

```
brew install gnupg
gpg --quick-generate-key "Beargineers BearPlatform <your@email>" rsa4096 sign never
gpg --list-keys                                        # the long hex string is KEY_ID
gpg --keyserver keyserver.ubuntu.com --send-keys KEY_ID
```

Maven Central checks signatures against the public key on the key server, so the last step is required.

### GitHub secrets

In the GitHub repository's **Settings → Secrets and variables → Actions**, or with `gh`:

| Secret                   | Value                                         |
|--------------------------|-----------------------------------------------|
| `MAVEN_CENTRAL_USERNAME` | the user token's username                     |
| `MAVEN_CENTRAL_PASSWORD` | the user token's password                     |
| `SIGNING_KEY`            | `gpg --armor --export-secret-keys KEY_ID`     |
| `SIGNING_KEY_PASSWORD`   | the key's passphrase                          |

```
gpg --armor --export-secret-keys KEY_ID | gh secret set SIGNING_KEY
```

## Trying a version before releasing it

```
./gradlew publishToMavenLocal
```

publishes the `-SNAPSHOT` version from `gradle.properties` to `~/.m2`, unsigned. A robot project then uses it with
`mavenLocal()` in its repositories and that version in its dependency.
