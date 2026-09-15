# F-Droid

Two separate things, and only one of them is available today.

## Our own repository — what `.github/workflows/fdroid-repo.yml` does

An F-Droid repository is a directory of APKs plus a signed index. Anyone can
host one; F-Droid clients add it by URL. Ours is built from the APKs a release
has already produced and served from GitHub Pages.

What it buys:

- The APK keeps this project's signing key, so somebody already running a build
  from GitHub Releases can switch channels without uninstalling. On a phone in a
  country where re-downloading is the hard part, that is not a small thing.
- F-Droid handles update notifications, so the in-app check has one less reason
  to reach GitHub from an Iranian address.

What it does not buy: reach. `f-droid.org` and `github.io` are both blocked or
degraded in the places this app is for, so this is a better channel for people
who already use F-Droid, not a new way in for people who cannot download at all.
Reach is what the main repository would buy, and that is the other section.

### Turning it on

Three secrets, and one of them has to be made first. The repository index is
signed with its **own** key, not the APK signing key — they answer different
questions, and the app key should not leave the release job.

```bash
keytool -genkeypair -v \
  -keystore fdroid.p12 -storetype PKCS12 \
  -alias whiteaesther-fdroid \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=WhiteAesther"
base64 -w0 fdroid.p12
```

Then in the repository's Actions secrets:

| Secret | Value |
|---|---|
| `FDROID_KEYSTORE_BASE64` | the base64 above |
| `FDROID_KEYSTORE_PASSWORD` | the store password |
| `FDROID_KEY_ALIAS` | `whiteaesther-fdroid` |

Keep `fdroid.p12` backed up with the app signing key. Losing it means every
existing subscriber has to remove the repository and add it again, because the
index signature will no longer match.

Pages has to be set to deploy from GitHub Actions. After the first successful
run the repository URL is:

```
https://whitedns.github.io/WhiteAestherMobile/fdroid/repo
```

The workflow runs after a successful Release, and can be run by hand against any
existing tag. Until those secrets exist it does nothing and says so in a notice:
publishing here is opt-in, and a release that has not opted in has not failed.
Once they are set, a key that cannot be decoded *is* a failure and is reported as
one.

## The main repository — what stands in the way

Worth wanting: f-droid.org is mirrored, trusted, and reaches people who will not
install an APK from a link. It is also not currently possible, for one concrete
reason and three smaller ones.

**`ca.psiphon:psiphontunnel` is the blocker.** F-Droid's inclusion policy accepts
prebuilt dependencies only from a closed list of Maven repositories — Maven
Central, Google, OSS Sonatype, OSS JFrog, JitPack, Clojars. Psiphon publish
theirs as a Maven layout served out of a git repository, which
`settings.gradle.kts` resolves from `raw.githubusercontent.com`. That is not on
the list, and being freely licensed is not sufficient on its own. Getting past
it means building Psiphon's AAR from source with gomobile inside the build
recipe — a project of its own, and one that has to be maintained against every
Psiphon bump.

The rest are work rather than walls:

- **The build scripts are PowerShell.** `native/{chain,psiphon,tor}/*.ps1` run
  fine on CI because GitHub's runners ship `pwsh`, but F-Droid's buildserver is
  Debian and PowerShell is not a Debian package. Porting them to `sh` is the
  clean answer.
- **The toolchain is new.** NDK 29, `compileSdk 37`, AGP 9.4.0, Gradle 9.7.1.
  `fdroidserver` installs NDKs through `sdkmanager` so a recent one is not
  automatically a problem, but this wants confirming rather than assuming.
- **The in-app update check** should be off in an F-Droid build; F-Droid does
  that job itself.

And one thing to declare rather than fix: **AntiFeature `NonFreeNet`**. The
engine terminates on Cloudflare and one carrier is Psiphon's network. Both are
services nobody here runs, which is exactly what that flag is for.

Good news for the licence review, which is usually the other hard part: the app
is AGPL-3.0, `THIRD_PARTY_NOTICES.md` already records every component and its
source, nothing binary is committed (`git ls-files` finds only
`gradle-wrapper.jar`), and the Psiphon bootstrap list is pinned by revision and
checked against a SHA-256 rather than vendored.
