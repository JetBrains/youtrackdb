---
source_files:
  - docker-tests/src/**
  - docker-tests/pom.xml
last_synced_commit: 2d5822220f
related_docs:
  - CLAUDE.md
---

Unit tests for the Docker-based YouTrackDB server and REPL console.

As those tests require Docker presence, they are not run by default.
To run them enable `docker-images` maven profile.

It is highly recommended for developers to enable it by default in maven settings.xml.

```xml

<activeProfiles>
  <activeProfile>docker-images</activeProfile>
</activeProfiles>
```

All tested containers can be run with debugging enabled.
To do so, set `ytdb.testcontainer.debug.container=true` system property during the build.