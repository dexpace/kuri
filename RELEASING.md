# Releasing kuri

kuri is published to **Maven Central** through the **Central Portal**, and releases are automated
with [release-please](https://github.com/googleapis/release-please). This document covers the
one-time setup a maintainer needs, and the day-to-day flow of cutting a release.

## How it works

- **Versioning.** The version lives in `gradle.properties` and is owned by release-please, which
  derives the next version from Conventional Commit history. kuri is currently on an **alpha
  prerelease series** (`0.1.0-alpha.1` → `0.1.0-alpha.2` → …).
- **Pipeline.** On every push to `main`, release-please maintains a *release PR* that rolls the
  landed commits into the next version bump and the `CHANGELOG.md`. Merging that PR tags the release
  and creates a GitHub Release, which triggers the publish job. Publishing runs on a **macOS** runner
  — the only host that can build every Kotlin target, Apple ones included — so a single job produces
  one complete Central Portal deployment and releases it. The publish job also uploads a zip of all
  target artifacts to the GitHub Release, and a best-effort smoke-test job then checks that the
  artifact resolves from Maven Central.

The two workflows are `.github/workflows/release-please.yml` (the release PR + the publish trigger)
and `.github/workflows/publish.yml` (the reusable publish job, also runnable on demand).

## One-time setup

The publish job will fail until the following repository/organization secrets and Central Portal
settings are in place.

### 1. Central Portal account and namespace

1. Create a [Central Portal](https://central.sonatype.com/) account for the publishing identity.
2. Verify the **`org.dexpace`** namespace (via the GitHub-organization or DNS verification flow).
3. Generate a **user token** (Account → Generate User Token) and store its two halves as secrets:
   - `MAVEN_CENTRAL_USER` — the token *username*.
   - `MAVEN_CENTRAL_PASSWORD` — the token *password*.

   These are the generated **token** credentials, not your sonatype.com login.

### 2. GPG signing key

Maven Central rejects unsigned artifacts, so a GPG key is required.

```sh
# Generate a key (RSA 4096, tied to the maintainer identity), then find its id:
gpg --full-generate-key
gpg --list-secret-keys --keyid-format=long        # note the long key id

# Publish the PUBLIC key so the Central Portal can verify the signatures:
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
gpg --keyserver keys.openpgp.org      --send-keys <KEY_ID>   # optional second keyserver

# Export the PRIVATE key, ASCII-armored, for the CI secret:
gpg --armor --export-secret-keys <KEY_ID>
```

Store the results as secrets:

- `SIGNING_IN_MEMORY_KEY` — the full ASCII-armored private key, including the
  `-----BEGIN PGP PRIVATE KEY BLOCK-----` / `-----END …-----` lines (multi-line secrets are fine).
- `SIGNING_IN_MEMORY_KEY_PASSWORD` — the key's passphrase.

### Required secrets at a glance

| Secret | Purpose |
| --- | --- |
| `MAVEN_CENTRAL_USER` | Central Portal token username |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal token password |
| `SIGNING_IN_MEMORY_KEY` | ASCII-armored GPG private key |
| `SIGNING_IN_MEMORY_KEY_PASSWORD` | GPG key passphrase |

There is also one **optional** secret, `RELEASE_WEBHOOK_URL`. If it is set, the publish job POSTs a
release notification to it after a successful publish; the JSON payload uses Slack's `text` key by
default and can be switched to Discord's `content` key in `publish.yml`. If it is unset, the
notification step is silently skipped.

The publish workflow maps these to the Gradle properties the
[vanniktech maven-publish](https://vanniktech.github.io/gradle-maven-publish-plugin/) plugin reads
(`ORG_GRADLE_PROJECT_mavenCentralUsername`/`…Password`,
`ORG_GRADLE_PROJECT_signingInMemoryKey`/`…KeyPassword`). The pre-existing
`MAVEN_CENTRAL_CREDS_BASE64` secret is **not** used here — the plugin derives the bearer token from
the user/password pair.

## Cutting a release

1. Land your changes on `main` through a pull request. Because the repo **squash-merges**, the *PR
   title* becomes the commit on `main` — and that is what release-please parses, so it must be a
   valid Conventional Commit (enforced by `.github/workflows/commit-convention.yml`).
2. release-please opens or updates a **`chore(main): release …`** PR that bumps the version in
   `gradle.properties` and updates `CHANGELOG.md`. Review it.
3. **Merge the release PR.** That creates the tag and GitHub Release and — in the same workflow run —
   runs the publish job on macOS, which builds, signs, uploads, and releases every target to the
   Central Portal.
4. Watch the **Release Please** workflow run to confirm the publish job succeeds. Artifacts appear on
   Maven Central after the Portal finishes processing (propagation to `search.maven.org` can lag by
   a while).

### Which commits trigger a release

release-please cuts a release only for `feat` (minor), `fix` (patch), and breaking changes
(`!` / `BREAKING CHANGE`, which bump within the alpha series). No other type triggers a release.
Housekeeping types — `ci`, `chore`, `docs`, `test`, `style`, `refactor`, `build` — are also hidden
from the changelog; `perf` and `revert` don't trigger a release either, but do appear in it (under
*Performance* and *Reverted*).

### Prerelease cadence and graduation

While on the alpha series, `feat` and `fix` both advance the prerelease counter
(`0.1.0-alpha.N` → `0.1.0-alpha.N+1`). To graduate to a stable `0.1.0` (or move to a new series such
as `0.2.0-alpha.1`), either add a `Release-As: 0.1.0` footer to a commit, or drop the
`"versioning": "prerelease"` / `"prerelease"` settings in `release-please-config.json`.

## Publishing the initial `0.1.0-alpha.1`

`.release-please-manifest.json` records `0.1.0-alpha.1` as the *current* version, so release-please's
first *automated* release will be `0.1.0-alpha.2`. To put `0.1.0-alpha.1` itself on Maven Central,
publish it manually once (after the secrets above are configured):

- **Actions → Publish → Run workflow**, on `main` (where `gradle.properties` reads
  `version=0.1.0-alpha.1`). Leave the `ref` input blank to publish the branch as-is.

This manual `workflow_dispatch` run automatically creates the `v0.1.0-alpha.1` git tag and GitHub
Release — you no longer have to tag it by hand — and the same run attaches the artifacts zip to that
Release.

From then on, merging release-please's release PRs publishes automatically.

## Manual / re-publish

The publish workflow is also `workflow_dispatch`-able for re-runs or ad-hoc publishes: **Actions →
Publish → Run workflow**, optionally passing a tag as the `ref` input. It uses the same secrets and
runs the same `./gradlew publishAndReleaseToMavenCentral` on macOS.

## Local sanity check

To inspect the artifacts that would be published without any credentials or signing:

```sh
./gradlew publishToMavenLocal
```

This writes the POMs and jars to `~/.m2/repository/org/dexpace/…` for inspection.
