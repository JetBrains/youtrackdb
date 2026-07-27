# TinkerPop Fork Dependency

YouTrackDB compiles against `io.youtrackdb:gremlin-*`, a JetBrains fork of Apache TinkerPop, and pins it to a SNAPSHOT version that Sonatype deletes 90 days after publication. The fork republishes only when someone pushes to its source branch. A coordinate left pinned for three months therefore stops resolving, and every YouTrackDB build then fails during dependency resolution, before a single class is compiled. The `Refresh TinkerPop Fork Snapshot` workflow (`.github/workflows/gremlin-fork-snapshot-refresh.yml`) rebuilds the pinned commit from source twice a month and republishes it under the same coordinate, which resets the retention clock.

## What the dependency is

The fork lives in [JetBrains/ytdb-tinkerpop](https://github.com/JetBrains/ytdb-tinkerpop) on the `ytdb-fork-3.8-dev` branch. It re-publishes Apache TinkerPop's modules under the `io.youtrackdb` group ID with YouTrackDB-specific patches. Its root pom builds the version from `${revision}${sha1}${changelist}`, and its publish pipeline passes `-Dsha1=-<short-sha>`, so each push to `ytdb-fork-3.8-dev` produces one coordinate stamped with the commit it was built from — `3.8.1-af9db90-SNAPSHOT` is TinkerPop 3.8.1 at fork commit `af9db90`.

The root `pom.xml` holds that coordinate once, in the `gremlin.version` property, and `<dependencyManagement>` applies it to all nine artifacts YouTrackDB declares (`gremlin-core`, `gremlin-groovy`, `tinkergraph-gremlin`, `gremlin-test`, `gremlin-driver`, `gremlin-server`, `gremlin-util`, `gremlin-console`, `gremlin-language`). The fork publishes all nine at one version, so a single property bump moves all of them.

## Why the pin expires

A pinned coordinate stops resolving 90 days after it was published, because two facts combine. The fork publishes only on a push to `ytdb-fork-3.8-dev`, so a coordinate is written once and never refreshed. YouTrackDB pins one coordinate and holds it until someone bumps `gremlin.version`. Nobody republishes in between.

The fork publishes through `central-publishing-maven-plugin`, which uploads SNAPSHOT versions to `https://central.sonatype.com/repository/maven-snapshots/`. YouTrackDB's root pom declares that URL as its only repository for these artifacts, under the id `central-portal-snapshots`, which is the name Maven prints when resolution fails. Sonatype purges snapshots 90 days after they are published.

Nothing warns before the break. Local builds and any CI job with a warm Maven cache keep working from the cached copy long after the remote artifact is gone, so the first symptom is a cold runner failing to resolve. That is how it surfaced on 2026-07-27. `3.8.1-af9db90-SNAPSHOT` was published on 2026-04-27 and purged around 2026-07-26; the nightly integration pipeline then went red on all four Linux jobs while both Windows jobs stayed green, because the Windows matrix enables `cache: 'maven'` in `actions/setup-java` and the Linux matrix does not.

## The refresh workflow

### Triggers

`Refresh TinkerPop Fork Snapshot` runs at 03:00 UTC on the 1st and 15th of each month, on manual dispatch, and once on any push to `develop` that touches the workflow file itself. That last trigger is a bootstrap: landing a change to the workflow republishes immediately, rather than leaving a purged snapshot broken until the next scheduled run. Nothing routine edits that file, so later recoveries go through manual dispatch. A `gremlin.version` bump deliberately does not fire it, because the fork has just published the new coordinate and its 90-day window has only started.

### What each run does

1. Reads `gremlin.version` from `pom.xml` and splits it into a base version and a fork commit.
2. Checks out the fork at that commit.
3. Rebuilds it with `-Dsha1=-<short-sha>`, so the deploy lands on the coordinate the pin already names.

The source commit is fixed, so every refresh compiles the same code. The jars are rebuilt rather than copied, and the fork sets no `project.build.outputTimestamp`. The bytes behind the coordinate therefore change from run to run, even though the source does not.

### When a run fails

A run every two weeks against a 90-day window leaves margin: five consecutive runs must fail before the artifact can disappear. That margin absorbs a Sonatype outage or an expired token, so a failed run posts to the `ytdb` Zulip stream at low severity and does not dispatch the CI fix agent. After two consecutive failures, read the run logs and either fix the failure or bump the pin (see [Bumping the pin](#bumping-the-pin)).

### The publication gates

Three checks abort the run before anything is published:

- `gremlin.version` does not parse as `<base>-<fork-sha>-SNAPSHOT`, or `pom.xml` declares the property more than once.
- The pinned commit is not an ancestor of `ytdb-fork-3.8-dev`. An abbreviated SHA resolves against the shared object store of the whole `apache/tinkerpop` fork network, so without this gate a commit authored in any sibling fork would be built and published under the `io.youtrackdb` group ID. The gate's guarantee rests on `ytdb-fork-3.8-dev` being a protected branch in a repository this workflow does not control.
- Maven reports a `project.version` for the rebuild that differs from the pin.

A fourth check runs after the deploy, and it is the reason the workflow can be trusted on a routine run. Existence proves nothing while the coordinate is still live: an artifact the deploy skipped answers with its months-old metadata and HTTP 200, and would keep counting down to its original purge date behind a green run. So the check asserts that each artifact's `<lastUpdated>` has advanced past a floor stamped just before the deploy, then fetches the file that metadata points at.

It covers eleven artifacts: the nine YouTrackDB declares, `gremlin-shaded`, which `gremlin-core` pulls in transitively, and the `io.youtrackdb:tinkerpop` parent pom, which every one of them inherits its version from and which Maven must resolve to build any of their effective models.

## Recovering a purged snapshot

Run the workflow manually:

```bash
gh workflow run gremlin-fork-snapshot-refresh.yml --repo JetBrains/youtrackdb
```

It restores whatever `gremlin.version` currently names. No other edit is needed: the republished coordinate is identical to the purged one, so every existing build resolves again as soon as the deploy lands.

Rebuilding the artifacts locally unblocks only that one machine — the jars land in `~/.m2`, not in `central-portal-snapshots`, so CI and every other developer stay broken.

Build with JDK 17: the fork enforces `requireJavaVersion [11,18)`, even though YouTrackDB itself needs 21. `<fork-short-sha>` is the abbreviated form that appears in `gremlin.version`, and it must be passed to `-Dsha1` exactly as written there, because it becomes part of the version string:

```bash
git clone --branch ytdb-fork-3.8-dev https://github.com/JetBrains/ytdb-tinkerpop.git
cd ytdb-tinkerpop && git checkout <fork-short-sha>
JAVA_HOME=/path/to/jdk17 mvn -Dsha1=-<fork-short-sha> -DskipTests \
  -pl :gremlin-core,:gremlin-driver,:gremlin-server,:gremlin-test,:gremlin-console -am install
```

## Bumping the pin

Push to `ytdb-fork-3.8-dev` in the fork; its pipeline tests the change and publishes a snapshot stamped with the new commit. Then set `gremlin.version` in YouTrackDB's root `pom.xml` to `<base>-<new-short-sha>-SNAPSHOT`. The refresh workflow needs no change: it re-reads `gremlin.version` on every run.

## The durable fix this does not do

Refreshing a snapshot keeps the build working; it does not make the build reproducible. A SNAPSHOT coordinate is mutable by definition, so a checkout of an old YouTrackDB commit resolves whatever `gremlin-core:3.8.1-af9db90-SNAPSHOT` happens to contain today rather than what it contained when that commit was written. Publishing the fork as an immutable release (`3.8.1-af9db90`, no `-SNAPSHOT`) to Maven Central would end both the 90-day expiry and the mutability, and retire this workflow. That change belongs in the fork, because it needs GPG signing and a Central Portal release deploy.
