# Releasing

Releases are cut by pushing a `vX.Y.Z` tag. The [`Release`](.github/workflows/release.yml)
workflow then builds and publishes both halves and attaches them to a GitHub Release:

- **Relay** — `npm pack` tarball + `npm publish` to the public npm registry as `clairvoyant-relay`.
- **Glasses app** — a signed release APK (`clairvoyant-<version>.apk`).

The tag (minus the leading `v`) becomes the relay's npm version and the app's `versionName`;
the app's `versionCode` is the workflow run number (monotonic across releases).

## One-time setup — GitHub secrets

The workflow needs five repository secrets. Add them under
**Settings → Secrets and variables → Actions**, or with the `gh` CLI as shown below.

### 1. npm token

Create an **Automation** token at <https://www.npmjs.com/settings/~/tokens> (works with 2FA in CI):

```sh
gh secret set NPM_TOKEN --body "npm_XXXXXXXXXXXXXXXXXXXX"
```

### 2. Android signing keystore

Generate a release keystore **once** and keep it safe — every future app update must be
signed with the same key or users can't upgrade in place. Do NOT commit it (it's gitignored).

```sh
# Generate the keystore (pick your own passwords when prompted / via -storepass -keypass).
keytool -genkeypair -v \
  -keystore clairvoyant-release.keystore \
  -alias clairvoyant \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Sreekar Chigurupati, O=Clairvoyant, C=US"

# Store the four signing secrets (base64-encode the keystore so it survives as text).
gh secret set ANDROID_KEYSTORE_BASE64 --body "$(base64 < clairvoyant-release.keystore)"
gh secret set ANDROID_KEYSTORE_PASSWORD --body "YOUR_STORE_PASSWORD"
gh secret set ANDROID_KEY_ALIAS --body "clairvoyant"
gh secret set ANDROID_KEY_PASSWORD --body "YOUR_KEY_PASSWORD"
```

Back up `clairvoyant-release.keystore` and its passwords somewhere durable (a password
manager). Losing them means you can never ship an in-place update again.

## Cutting a release

```sh
git tag v0.1.0
git push origin v0.1.0
```

Watch it at **Actions → Release**. When it finishes, the release with the tarball and APK
appears under **Releases**, and `npm i -g clairvoyant-relay` serves the new version.

## Local release builds (optional)

To build a signed APK locally, drop a `keystore.properties` in the repo root (gitignored):

```properties
storeFile=clairvoyant-release.keystore
storePassword=YOUR_STORE_PASSWORD
keyAlias=clairvoyant
keyPassword=YOUR_KEY_PASSWORD
```

```sh
./gradlew :app:assembleRelease -PversionName=0.1.0 -PversionCode=1
```

Without `keystore.properties` (and without the CI env vars) the release build is unsigned.
