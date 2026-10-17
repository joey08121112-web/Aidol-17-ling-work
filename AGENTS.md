# Aidol development guide

This repository maintains three product modules: the native Android app, the
hosted multi-account backend, and the static official website. Keep them in one
repository and share source through `main`. Hosted users have separate UUID
SQLite stores; each developer has an independent local account backend.

## Boundaries

- `android-app/`: shipped Android client and explicit debug hardware diagnostics;
  never bundle provider keys or passwords. Rise SDK ownership and capture belong
  in the Android hardware package. Development uses USB Android devices.
- `website/`: public static product information and APK download links; no account
  passwords, provider credentials or application business APIs.
- `deploy/`: hosted systemd/nginx installation, backup and certificate maintenance,
  plus independent static website publishing.
- `app/providers/`: vendor protocols; no application UI or business rules.
- `app/services/`: account contexts, mode decisions, memory extraction,
  conversation and speech processing.
- `app/main.py`: request validation, authentication, routes and lifecycle wiring.
  `create_hosted_app` is the sole application factory.
- `app/storage.py`: SQLite transactions and persistence.
- `scripts/`: local backend launch, USB connection, SDK import, source handoff and
  deliberate provider checks.
- `docs/`: architecture, public contracts, hardware acceptance and handoff.

The old browser client and standalone provider demos have been removed. Do not
restore their unauthenticated routes or add a second client for development.
Local development uses the same `/auth/*` and `/v1/*` contracts as hosted use,
at `127.0.0.1:8108` with `data/android-local/`; phone access uses USB reverse.

## Validation

```sh
python -m pytest tests -q
python -m compileall -q app
# With JDK 17 and Android SDK 35, in android-app/:
./gradlew testDebugUnitTest lintDebug assembleDebug
```

For website changes, check desktop/mobile layout, interactive controls, referenced
assets and the APK download link. Do not rebuild or publish APKs for a website-only
change unless requested.

Live scripts under `scripts/probe_*.py` can consume API quota. Use them deliberately,
with synthetic speech and public landmark coordinates; keep them out of automated runs.
Record actual observations in `docs/acceptance.md`. Distinguish local mocks,
provider checks, whole-chain tests and physical-headset tests. Historical evidence
does not prove the current build passes or that removed clients remain supported.

## Collaboration

Keep changes in the module owning the responsibility. Update affected API and
handoff documents when contracts change. Tests use temporary databases and mock
providers, never the developer's real credentials. Do not commit `.env`, recordings,
databases or generated artifacts. Do not invent SDK commands for ANC, buttons or
battery; advertise only capabilities established by a real SDK handshake.

Preserve current uncommitted SDK and USB work when changing project structure.
The runtime application contains one orchestrator. Multiple development agents do
not imply that several independently reasoning agents run in the product.
