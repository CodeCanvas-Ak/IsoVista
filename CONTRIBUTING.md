# Contributing to IsoVista

Thanks for improving IsoVista. Keep changes focused, reproducible, and explicit about what has actually been verified.

## Before editing

1. Read [README.md](README.md) for the public overview and [ROADMAP.md](ROADMAP.md) for current code facts, commands, and known limitations.
2. Read only the design documents relevant to your task. Files under `docs/plans/` describe target-state designs, not necessarily implemented behavior.
3. Use JDK 11, Node.js 20, and Python 3.10 with `backend/requirements.txt`. Use Linux or a container for native checkers and full integration testing.

Coding agents should also follow [AGENTS.md](AGENTS.md).

## Making a change

1. Create a focused branch from `main`.
2. Keep generated artifacts, local environment overrides, credentials, and personal paths out of version control. Do not edit vendored content under `third_party/` as part of a feature change.
3. Add or update regression tests for behavior changes. When changing a configuration key, API, result field, or DOT attribute, update all Java, FastAPI, and Vue consumers as applicable.
4. Update the single source of truth for your change: current behavior belongs in `ROADMAP.md`, extension steps in the relevant how-to guide, and target designs in `docs/plans/`.
5. Run the relevant commands listed in `ROADMAP.md`. Record exactly what passed and leave unrun database, native checker, Docker, or end-to-end checks explicitly marked as unverified.

## Submitting a pull request

Include:

- the problem and user-visible effect of the change;
- a concise summary of the implementation and documentation updates;
- the exact checks run and their results;
- any remaining limitations, especially unverified external dependencies.

Use only disposable test databases and sanitized histories in examples, issues, and pull requests. Never attach production credentials or sensitive data.
