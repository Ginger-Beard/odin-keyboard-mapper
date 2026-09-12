# Releasing

## 1. Create a signing keystore (one-time)

```
scripts/make-keystore.sh
```

This writes `keys/release.keystore` (gitignored — never commit it) and
prints the base64 of the keystore plus the exact secret names to create.

## 2. Add repository secrets

In `Ginger-Beard/odin-keyboard-mapper` on GitHub: Settings -> Secrets and
variables -> Actions -> New repository secret. Add all four:

- `KEYSTORE_BASE64` — base64 output from `scripts/make-keystore.sh`
- `KEYSTORE_PASSWORD` — the keystore password you entered
- `KEY_ALIAS` — the key alias you entered (default `release`)
- `KEY_PASSWORD` — the key password you entered

## 3. Tag and push a release

```
git tag v1.0.0
git push --tags
```

Pushing a tag matching `v*` triggers `.github/workflows/release.yml`, which
builds the daemon, assembles a signed release APK, uploads it as a workflow
artifact, and attaches it to the GitHub Release for that tag. The workflow
can also be run manually via `workflow_dispatch` (uses the current run
number as the version code and skips a real tag name for version name in
that case, so prefer tagging for real releases).

## 4. Build a signed APK locally

```
source /home/josh/.local/opt/android-env.sh   # or your own Android/JDK env
export ZIG=/path/to/zig                        # zig 0.13+, or have it on PATH
export KEYSTORE_FILE=keys/release.keystore
export KEYSTORE_PASSWORD=...
export KEY_ALIAS=release
export KEY_PASSWORD=...
VERSION_NAME=1.0.0 scripts/release.sh
```

The APK is written to `dist/odin-keyboard-mapper-<VERSION_NAME>.apk`. If any
of the four `KEYSTORE_*`/`KEY_*` variables are unset, the same command still
succeeds but produces an unsigned APK — `scripts/release.sh` prints whether
the result is signed or unsigned via `apksigner verify --print-certs`.
