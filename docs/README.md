---
source_files:
  - docs/*.md
  - docs/dev/*.md
  - docs/internals/*.md
related_docs: []
---

# YouTrackDB Documentation

## Table of Contents

| Document | Description |
|---|---|
| [CI/CD Pipeline](dev/ci-cd-diagram.md) | Pipeline architecture diagram, workflow descriptions, quality gates, and deployment details |
| [Test Quality Requirements](dev/test-quality-requirements.md) | Code coverage, mutation testing, and test writing guidelines enforced by CI |
| [TestFlows Runner Setup](dev/testflows-runner-setup.md) | Deployment and maintenance guide for self-hosted GitHub Actions runners on Hetzner |
| [LinkBag Architecture](internals/linkbag-architecture.md) | Edge storage internals: embedded vs BTree-based LinkBag strategies |
| [Documentation Sync Mapping](docs-sync.yml) | Central mapping of source code paths to documentation files, used by the docs-sync GitHub Action |
