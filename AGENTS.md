# Aidol development guide

This repository is a local-first, single-user hackathon application. Each teammate
runs an independent backend and SQLite database; share source through `main`.

## Boundaries

- `web/audio/`: hardware and browser audio; keep vendor SDK assumptions here.
- `app/providers/`: vendor protocols; no application UI or business rules.
- `app/services/`: mode decisions, memory extraction, conversation and speech sessions.
- `app/main.py`: request validation, authentication, routes and lifecycle wiring.
- `app/storage.py`: SQLite transactions and persistence.
- `docs/`: architecture, public contracts, hardware acceptance and handoff.
- Original `qwen_omni_demo/` and `web_duplex_demo/` are reference demos. Do not edit
  their protocols as part of normal Aidol changes or collect their standalone tests.

## Validation

```sh
python -m pytest tests -q
node --test tests/audio-worklet.test.mjs tests/audio-session.test.mjs
python -m compileall -q app
```

Live scripts under `scripts/probe_*.py` can consume API quota. Use them deliberately,
with synthetic speech and public landmark coordinates; never run them in CI.
Record actual observations in `docs/acceptance.md`. Distinguish local mocks,
provider checks, whole-chain tests and physical-headset tests.

## Collaboration

Keep changes in the module owning the responsibility. Update affected API and
handoff documents when contracts change. Tests use temporary databases and mock
providers, never the developer's real credentials. Do not commit `.env`, recordings,
databases or generated artifacts. Do not invent SDK commands for ANC, buttons or
battery; advertise only capabilities established by a real SDK handshake.

The runtime application contains one orchestrator. Multiple development agents do
not imply that several independently reasoning agents run in the product.
