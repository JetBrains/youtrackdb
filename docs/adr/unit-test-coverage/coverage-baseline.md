# Coverage Baseline — Core Module

**Commit:** `7e19d84b51` (develop branch, 2026-04-14)
**Report:** `.coverage/reports/youtrackdb-core/jacoco.xml`

## Aggregate Totals

- **Line coverage**: 63.6% (56,269/88,514 lines covered, 32,245 uncovered)
- **Branch coverage**: 53.3% (23,157/43,458 branches covered, 20,301 uncovered)
- **Packages**: 177

## Target

- **Line coverage target**: 85% (+21.4 percentage points, ~19,000 additional lines)
- **Branch coverage target**: 70% (+16.7 percentage points, ~7,300 additional branches)

## Per-Package Coverage

Sorted by uncovered lines descending.

| Package | Line% | Branch% | Uncovered Lines | Total Lines |
|---------|-------|---------|-----------------|-------------|
| com.jetbrains.youtrackdb.internal.core.sql.executor | 74.6% | 65.4% | 1735 | 6833 |
| com.jetbrains.youtrackdb.internal.core.record.impl | 62.6% | 54.0% | 1412 | 3780 |
| com.jetbrains.youtrackdb.internal.core.metadata.schema | 70.7% | 55.7% | 1278 | 4355 |
| com.jetbrains.youtrackdb.internal.core.db | 66.5% | 52.4% | 1268 | 3788 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local | 60.9% | 58.4% | 1190 | 3043 |
| com.jetbrains.youtrackdb.internal.core.index | 67.7% | 58.8% | 1031 | 3190 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string | 30.9% | 22.3% | 998 | 1444 |
| com.jetbrains.youtrackdb.internal.core.db.tool | 60.8% | 49.9% | 891 | 2274 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary | 74.8% | 71.9% | 850 | 3375 |
| com.jetbrains.youtrackdb.internal.core.sql.operator | 20.9% | 10.0% | 748 | 946 |
| com.jetbrains.youtrackdb.internal.core.gremlin | 53.5% | 68.8% | 713 | 1534 |
| com.jetbrains.youtrackdb.internal.core.command.script | 31.4% | 22.2% | 691 | 1008 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer | 41.4% | 36.0% | 629 | 1073 |
| com.jetbrains.youtrackdb.internal.core.storage.cache.local | 68.7% | 55.4% | 627 | 2002 |
| com.jetbrains.youtrackdb.internal.core.schedule | 45.7% | 31.5% | 598 | 1102 |
| com.jetbrains.youtrackdb.internal.core.metadata.security | 72.3% | 56.3% | 593 | 2138 |
| com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2 | 13.3% | 12.4% | 591 | 682 |
| com.jetbrains.youtrackdb.internal.core.sql.filter | 39.9% | 31.4% | 579 | 963 |
| com.jetbrains.youtrackdb.internal.core.tx | 61.8% | 65.1% | 572 | 1498 |
| com.jetbrains.youtrackdb.internal.core.security | 32.1% | 22.4% | 548 | 807 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.graph | 53.4% | 40.5% | 449 | 963 |
| com.jetbrains.youtrackdb.internal.core.sql | 39.7% | 34.7% | 440 | 730 |
| com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3 | 74.0% | 58.3% | 437 | 1679 |
| com.jetbrains.youtrackdb.internal.common.concur.lock | 45.0% | 33.1% | 405 | 737 |
| com.jetbrains.youtrackdb.internal.core.db.record | 70.5% | 60.9% | 404 | 1371 |
| com.jetbrains.youtrackdb.internal.common.parser | 27.0% | 24.6% | 368 | 504 |
| com.jetbrains.youtrackdb.internal.common.collection | 62.9% | 53.0% | 360 | 971 |
| com.jetbrains.youtrackdb.internal.core.storage.config | 62.5% | 47.1% | 359 | 957 |
| com.jetbrains.youtrackdb.internal.core.command | 48.7% | 48.9% | 325 | 634 |
| com.jetbrains.youtrackdb.internal.core.sql.executor.resultset | 49.2% | 47.2% | 313 | 616 |
| com.jetbrains.youtrackdb.internal.core.sql.query | 2.9% | 2.6% | 297 | 306 |
| com.jetbrains.youtrackdb.internal.common.util | 26.4% | 11.1% | 296 | 402 |
| com.jetbrains.youtrackdb.internal.core.security.symmetrickey | 26.6% | 9.4% | 282 | 384 |
| com.jetbrains.youtrackdb.internal.core.serialization | 14.2% | 6.4% | 277 | 323 |
| com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree | 84.0% | 71.2% | 274 | 1717 |
| com.jetbrains.youtrackdb.internal.core.query.live | 13.4% | 0.0% | 272 | 314 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas | 76.7% | 59.8% | 262 | 1123 |
| com.jetbrains.youtrackdb.internal.core.fetch | 46.6% | 30.9% | 248 | 464 |
| com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v1 | 0.0% | 0.0% | 242 | 242 |
| com.jetbrains.youtrackdb.internal.core.query | 38.8% | 27.2% | 237 | 387 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal | 64.5% | 62.3% | 233 | 656 |
| com.jetbrains.youtrackdb.internal.core.exception | 40.9% | 43.5% | 230 | 389 |
| com.jetbrains.youtrackdb.internal.common.io | 36.2% | 26.0% | 224 | 351 |
| com.jetbrains.youtrackdb.internal.common.console | 0.0% | 0.0% | 212 | 212 |
| com.jetbrains.youtrackdb.internal.common.jnr | 24.1% | 21.4% | 208 | 274 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.coll | 48.6% | 38.4% | 198 | 385 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.misc | 53.0% | 38.3% | 196 | 417 |
| com.jetbrains.youtrackdb.internal.core | 70.4% | 55.7% | 177 | 598 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.index | 65.0% | 67.6% | 165 | 471 |
| com.jetbrains.youtrackdb.internal.core.metadata.security.binary | 0.0% | 0.0% | 164 | 164 |
| com.jetbrains.youtrackdb.internal.common.log | 29.7% | 27.3% | 161 | 229 |
| com.jetbrains.youtrackdb.internal.core.storage.disk | 83.3% | 72.2% | 159 | 954 |
| com.jetbrains.youtrackdb.api.gremlin.embedded | 3.8% | 1.8% | 152 | 158 |
| com.jetbrains.youtrackdb.internal.core.sql.method.misc | 58.6% | 41.6% | 149 | 360 |
| com.jetbrains.youtrackdb.internal.common.serialization | 34.5% | 27.1% | 146 | 223 |
| com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog | 51.5% | 25.2% | 146 | 301 |
| com.jetbrains.youtrackdb.internal.core.security.authenticator | 25.5% | 15.0% | 140 | 188 |
| com.jetbrains.youtrackdb.api.gremlin.tokens.schema | 0.0% | 100.0% | 138 | 138 |
| com.jetbrains.youtrackdb.internal.common.directmemory | 68.5% | 53.5% | 134 | 425 |
| com.jetbrains.youtrackdb.internal.common.thread | 36.0% | 47.5% | 130 | 203 |
| com.jetbrains.youtrackdb.internal.core.db.config | 0.0% | 0.0% | 130 | 130 |
| com.jetbrains.youtrackdb.internal.common.serialization.types | 84.3% | 86.7% | 129 | 824 |
| com.jetbrains.youtrackdb.internal.core.command.traverse | 62.9% | 39.2% | 127 | 342 |
| com.jetbrains.youtrackdb.internal.core.storage.collection.v2 | 90.3% | 75.4% | 127 | 1312 |
| com.jetbrains.youtrackdb.internal.core.index.engine | 90.1% | 85.7% | 126 | 1274 |
| com.jetbrains.youtrackdb.internal.core.id | 64.2% | 63.9% | 125 | 349 |
| com.jetbrains.youtrackdb.internal.core.storage.memory | 59.5% | 53.0% | 124 | 306 |
| com.jetbrains.youtrackdb.internal.core.engine | 17.1% | 11.9% | 121 | 146 |
| com.jetbrains.youtrackdb.internal.core.security.kerberos | 0.0% | 0.0% | 114 | 114 |
| com.jetbrains.youtrackdb.internal.common.concur.resource | 49.1% | 43.1% | 112 | 220 |
| com.jetbrains.youtrackdb.internal.core.compression.impl | 0.0% | 0.0% | 104 | 104 |
| com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2 | 66.9% | 45.3% | 102 | 308 |
| com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v1 | 66.6% | 44.4% | 102 | 305 |
| com.jetbrains.youtrackdb.internal.core.record | 63.3% | 50.7% | 90 | 245 |
| com.jetbrains.youtrackdb.internal.core.storage.ridbag | 87.1% | 64.8% | 86 | 668 |
| com.jetbrains.youtrackdb.internal.core.index.iterator | 43.3% | 44.2% | 85 | 150 |
| com.jetbrains.youtrackdb.internal.common.profiler | 44.4% | 50.0% | 84 | 151 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations | 90.0% | 83.6% | 83 | 834 |
| com.jetbrains.youtrackdb.internal.core.sql.operator.math | 53.6% | 48.9% | 83 | 179 |
| com.jetbrains.youtrackdb.internal.core.sql.executor.match | 95.4% | 84.3% | 79 | 1726 |
| com.jetbrains.youtrackdb.internal.core.storage.cache | 76.9% | 59.3% | 78 | 337 |
| com.jetbrains.youtrackdb.internal.core.metadata.sequence | 84.3% | 72.9% | 75 | 478 |
| com.jetbrains.youtrackdb.internal.core.metadata.function | 72.2% | 45.8% | 74 | 266 |
| com.jetbrains.youtrackdb.internal.core.db.tool.importer | 59.4% | 48.7% | 73 | 180 |
| com.jetbrains.youtrackdb.internal.core.index.engine.v1 | 86.1% | 82.3% | 71 | 509 |
| com.jetbrains.youtrackdb.internal.core.db.record.record | 57.2% | 37.5% | 71 | 166 |
| com.jetbrains.youtrackdb.internal.core.sql.functions | 70.4% | 52.2% | 71 | 240 |
| com.jetbrains.youtrackdb.internal.core.storage | 38.9% | 37.5% | 66 | 108 |
| com.jetbrains.youtrackdb.internal.core.gremlin.io.binary | 16.5% | 0.0% | 66 | 79 |
| com.jetbrains.youtrackdb.internal.core.gremlin.io.graphson | 34.3% | 0.0% | 65 | 99 |
| com.jetbrains.youtrackdb.internal.core.config | 66.1% | 55.4% | 64 | 189 |
| com.jetbrains.youtrackdb.internal.core.sql.method | 62.0% | 36.2% | 62 | 163 |
| com.jetbrains.youtrackdb.internal.common.collection.closabledictionary | 87.9% | 68.5% | 62 | 511 |
| com.jetbrains.youtrackdb.internal.core.storage.fs | 72.9% | 65.9% | 62 | 229 |
| com.jetbrains.youtrackdb.internal.core.storage.cache.chm | 89.3% | 73.2% | 61 | 572 |
| com.jetbrains.youtrackdb.internal.core.sql.executor.metadata | 79.9% | 63.0% | 61 | 304 |
| com.jetbrains.youtrackdb.internal.core.cache | 71.4% | 56.6% | 60 | 210 |
| com.jetbrains.youtrackdb.api.config | 88.8% | 48.1% | 58 | 517 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.math | 73.9% | 62.7% | 58 | 222 |
| com.jetbrains.youtrackdb.internal.core.command.script.formatter | 36.0% | 26.3% | 57 | 89 |
| com.jetbrains.youtrackdb.internal.common.types | 34.9% | 0.0% | 56 | 86 |
| com.jetbrains.youtrackdb.internal.core.storage.collection | 89.2% | 72.5% | 56 | 519 |
| com.jetbrains.youtrackdb.api | 28.8% | 50.0% | 52 | 73 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated | 79.5% | 59.7% | 46 | 224 |
| com.jetbrains.youtrackdb.internal.core.storage.index.nkbtree.normalizers | 71.5% | 20.0% | 45 | 158 |
| com.jetbrains.youtrackdb.internal.common.stream | 50.6% | 61.1% | 43 | 87 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.deque | 72.5% | 55.0% | 38 | 138 |
| com.jetbrains.youtrackdb.api.exception | 53.3% | 11.5% | 35 | 75 |
| com.jetbrains.youtrackdb.internal.core.gremlin.service | 85.1% | 78.8% | 34 | 228 |
| com.jetbrains.youtrackdb.internal.common.comparator | 72.4% | 66.7% | 34 | 123 |
| com.jetbrains.youtrackdb.internal.core.sql.method.sequence | 23.1% | 16.7% | 30 | 39 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.text | 72.5% | 65.7% | 30 | 109 |
| com.jetbrains.youtrackdb.internal.common.profiler.metrics | 91.5% | 75.8% | 30 | 352 |
| com.jetbrains.youtrackdb.internal.common.exception | 51.7% | 100.0% | 29 | 60 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.conversion | 52.5% | 50.0% | 29 | 61 |
| com.jetbrains.youtrackdb.internal.core.iterator | 82.4% | 87.8% | 28 | 159 |
| com.jetbrains.youtrackdb.internal.common.factory | 38.6% | 20.0% | 27 | 44 |
| com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization | 84.1% | 71.3% | 27 | 170 |
| com.jetbrains.youtrackdb.internal.core.storage.index.engine | 0.0% | 100.0% | 26 | 26 |
| com.jetbrains.youtrackdb.internal.core.util | 79.8% | 67.9% | 24 | 119 |
| com.jetbrains.youtrackdb.internal.core.db.record.ridbag | 84.7% | 70.0% | 23 | 150 |
| com.jetbrains.youtrackdb.internal.core.command.script.transformer | 65.6% | 60.5% | 22 | 64 |
| com.jetbrains.youtrackdb.internal.core.engine.local | 67.7% | 44.4% | 21 | 65 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary | 65.6% | 66.7% | 21 | 61 |
| com.jetbrains.youtrackdb.internal.core.storage.index.versionmap | 0.0% | 100.0% | 20 | 20 |
| com.jetbrains.youtrackdb.internal.core.storage.cache.chm.readbuffer | 85.2% | 70.5% | 19 | 128 |
| com.jetbrains.youtrackdb.internal.core.gremlin.io | 36.7% | 0.0% | 19 | 30 |
| com.jetbrains.youtrackdb.internal.core.fetch.remote | 41.9% | 0.0% | 18 | 31 |
| com.jetbrains.youtrackdb.internal.core.metadata.schema.clusterselection | 63.3% | 31.2% | 18 | 49 |
| com.jetbrains.youtrackdb.internal.core.gremlin.io.gryo | 20.0% | 0.0% | 16 | 20 |
| com.jetbrains.youtrackdb.api.gremlin.tokens | 30.4% | 0.0% | 16 | 23 |
| com.jetbrains.youtrackdb.internal.core.command.script.transformer.result | 16.7% | 0.0% | 15 | 18 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer.record | 0.0% | 0.0% | 14 | 14 |
| com.jetbrains.youtrackdb.internal.core.servlet | 0.0% | 0.0% | 14 | 14 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl | 90.8% | 88.9% | 14 | 153 |
| com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect | 81.9% | 81.5% | 13 | 72 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base | 95.4% | 66.0% | 13 | 283 |
| com.jetbrains.youtrackdb.internal.core.command.script.js | 43.5% | 66.7% | 13 | 23 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.stat | 88.9% | 77.1% | 12 | 108 |
| com.jetbrains.youtrackdb.internal.core.gql.parser | 95.5% | 89.7% | 11 | 245 |
| com.jetbrains.youtrackdb.internal.core.gremlin.jsr223 | 0.0% | 100.0% | 11 | 11 |
| com.jetbrains.youtrackdb.api.gremlin.embedded.schema | 0.0% | 100.0% | 10 | 10 |
| com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.filter | 69.7% | 75.0% | 10 | 33 |
| com.jetbrains.youtrackdb.internal.common.concur | 37.5% | 100.0% | 10 | 16 |
| com.jetbrains.youtrackdb.internal.core.metadata.security.jwt | 0.0% | 100.0% | 10 | 10 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.geo | 63.0% | 35.7% | 10 | 27 |
| com.jetbrains.youtrackdb.internal.core.dictionary | 0.0% | 100.0% | 9 | 9 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer.stream | 60.9% | 100.0% | 9 | 23 |
| com.jetbrains.youtrackdb.internal.core.conflict | 55.0% | 100.0% | 9 | 20 |
| com.jetbrains.youtrackdb.internal.core.metadata.security.auth | 0.0% | 100.0% | 9 | 9 |
| com.jetbrains.youtrackdb.internal.core.metadata | 82.7% | 75.0% | 9 | 52 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common | 76.5% | 25.0% | 8 | 34 |
| com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.map | 81.1% | 75.0% | 7 | 37 |
| com.jetbrains.youtrackdb.internal.common.profiler.monitoring | 94.4% | 90.6% | 7 | 124 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.operationsfreezer | 92.8% | 83.3% | 7 | 97 |
| com.jetbrains.youtrackdb.internal.core.storage.index.sbtree | 0.0% | 0.0% | 7 | 7 |
| com.jetbrains.youtrackdb.internal.common.concur.collection | 88.1% | 66.7% | 7 | 59 |
| com.jetbrains.youtrackdb.internal.core.gremlin.sqlcommand | 88.7% | 87.5% | 6 | 53 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.result | 0.0% | 0.0% | 6 | 6 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.sequence | 68.8% | 50.0% | 5 | 16 |
| com.jetbrains.youtrackdb.internal.core.index.comparator | 50.0% | 100.0% | 5 | 10 |
| com.jetbrains.youtrackdb.internal.core.type | 76.2% | 0.0% | 5 | 21 |
| com.jetbrains.youtrackdb.internal.core.metadata.schema.schema | 94.8% | 100.0% | 3 | 58 |
| com.jetbrains.youtrackdb.internal.core.replication | 0.0% | 100.0% | 3 | 3 |
| com.jetbrains.youtrackdb.internal.core.gremlin.executor.transformer | 0.0% | 100.0% | 2 | 2 |
| com.jetbrains.youtrackdb.internal.core.collate | 95.1% | 91.7% | 2 | 41 |
| com.jetbrains.youtrackdb.internal.common.listener | 86.7% | 66.7% | 2 | 15 |
| com.jetbrains.youtrackdb.internal.core.storage.cache.local.aoc | 0.0% | 100.0% | 2 | 2 |
| com.jetbrains.youtrackdb.internal.common.hash | 99.1% | 100.0% | 1 | 114 |
| com.jetbrains.youtrackdb.internal.core.gql.executor | 98.8% | 96.9% | 1 | 86 |
| com.jetbrains.youtrackdb.internal.core.engine.memory | 91.7% | 100.0% | 1 | 12 |
| com.jetbrains.youtrackdb.internal.core.gql.planner | 96.0% | 83.3% | 1 | 25 |
| com.jetbrains.youtrackdb.internal.core.index.multivalue | 66.7% | 100.0% | 1 | 3 |
| com.jetbrains.youtrackdb.internal.core.metadata.schema.validation | 100.0% | 100.0% | 0 | 20 |
| com.jetbrains.youtrackdb.internal.core.gql.executor.resultset | 100.0% | 96.7% | 0 | 72 |
| com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy | 100.0% | 100.0% | 0 | 15 |
| com.jetbrains.youtrackdb.internal.core.storage.cache.chm.writequeue | 100.0% | 100.0% | 0 | 31 |

---

## Post-Track-7 Measurement

**Commit:** `6fed5ac147` (`HEAD` at Track 7 Step 8, 2026-04-20)
**Report:** `.coverage/reports/youtrackdb-core/jacoco.xml`

### Aggregate Totals

| Metric | Baseline | Post-Track-7 | Delta |
|---|---|---|---|
| **Line coverage** | 63.6% (56,269 / 88,514) | **70.6%** (66,555 / 94,278) | **+7.0 pp**, +10,286 covered |
| **Branch coverage** | 53.3% (23,157 / 43,458) | **61.0%** (27,977 / 45,869) | **+7.7 pp**, +4,820 covered |
| **Packages** | 177 | 179 | +2 |

Track 7 alone contributes a meaningful portion of this delta; most of the
gain is cumulative across Tracks 2–7.

### Track 7 Target Packages

Baseline → post-Track-7 coverage for the packages Track 7 explicitly
targeted:

| Package | Baseline Line% / Branch% | Post-Track-7 Line% / Branch% | Uncov Lines (base → post) |
|---|---|---|---|
| `core.sql.method.misc` | 58.6% / 41.6% | **92.2% / 88.0%** | 149 → 28 |
| `core.sql.method` | 62.0% / 36.2% | **87.1% / 81.2%** | 62 → 21 |
| `core.sql.method.sequence` | 23.1% / 16.7% | **100.0% / 100.0%** | 30 → 0 |
| `core.sql` | 39.7% / 34.7% | **80.1% / 76.9%** | 440 → 145 |
| `core.sql.query` | 2.9% / 2.6% | **79.1% / 57.9%** | 297 → 64 |

All five method subpackages met or exceeded the 85% line / 70% branch
targets. `sql/` and `sql/query/` fall below 85% line at the package
aggregate, **as planned**: their residual uncovered lines are
deliberately-pinned dead code that Track 22 is slated to delete.

- `sql/` dead classes: `CommandExecutorSQLAbstract` (no subclasses),
  `DefaultCommandExecutorSQLFactory` (hardcoded `emptyMap()`),
  `DynamicSQLElementFactory` (empty mutable maps, no production
  mutators), `ReturnHandler` family (0 external instantiators) —
  ~250 LOC of vestigial scaffolding covered only by
  `SqlRootDeadCodeTest` WHEN-FIXED pins (not real tests).
- `sql/query/` dead classes: `ConcurrentLegacyResultSet`,
  `LiveLegacyResultSet`, `LiveResultListener`, `LocalLiveResultListener`
  — covered only by `SqlQueryDeadCodeTest` WHEN-FIXED pins. The
  `sql/query/` branch coverage of 57.9% reflects this — the live class
  (`BasicLegacyResultSet`) is aggressively covered; the dead classes
  pull the aggregate down until Track 22 removes them.

### Cumulative Contribution of Tracks 2–7

Track 7 brings the cumulative total to **70.6% line / 61.0% branch**,
which is roughly halfway toward the 85% / 70% plan targets from the
63.6% / 53.3% baseline. Tracks 8–22 must together close the remaining
**~14.4 percentage-point line gap** and **~9.0 percentage-point branch
gap**.

### Coverage-Gate on Changed Production Lines

Track 7's production-line footprint is tiny (tests-only track). The
only touched production files are pre-existing fixes carried forward
from earlier tracks:

```
Found 2 JaCoCo report(s)
Checking coverage for 189 changed file(s)
Line coverage:   PASSED — 100.0% (6/6 lines)
Branch coverage: PASSED — 100.0% (2/2 branches)
PASSED: All coverage meets thresholds (85% line, 70% branch)
```

Step 8 added one regression test
(`EntitySchemaOperatorsTest#testTraverseSpecificFieldBranchReachedViaElse`)
to cover the previously-missed `else` branch in
`QueryOperatorTraverse.traverse()` (Track 5 copy-paste bug fix at
`QueryOperatorTraverse.java:136`).

### Post-Track-7 Per-Package Coverage

Sorted by uncovered lines descending.


| com.jetbrains.youtrackdb.internal.core.sql.executor | 75.1% | 65.6% | 1703 | 6833 |
| com.jetbrains.youtrackdb.internal.core.record.impl | 62.7% | 54.2% | 1410 | 3780 |
| com.jetbrains.youtrackdb.internal.core.db | 66.4% | 52.4% | 1272 | 3788 |
| com.jetbrains.youtrackdb.internal.core.metadata.schema | 71.4% | 56.9% | 1244 | 4355 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local | 61.6% | 59.0% | 1199 | 3119 |
| com.jetbrains.youtrackdb.internal.core.index | 67.7% | 59.0% | 1029 | 3190 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string | 32.7% | 25.7% | 972 | 1444 |
| com.jetbrains.youtrackdb.internal.core.db.tool | 61.0% | 49.9% | 889 | 2278 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary | 74.5% | 71.5% | 862 | 3375 |
| com.jetbrains.youtrackdb.internal.core.gremlin | 53.9% | 69.4% | 715 | 1550 |
| com.jetbrains.youtrackdb.internal.core.command.script | 31.4% | 22.2% | 691 | 1008 |
| com.jetbrains.youtrackdb.internal.core.storage.cache.local | 68.5% | 55.2% | 635 | 2015 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer | 44.1% | 41.1% | 600 | 1073 |
| com.jetbrains.youtrackdb.internal.core.schedule | 45.7% | 31.5% | 598 | 1102 |
| com.jetbrains.youtrackdb.internal.core.metadata.security | 72.3% | 56.3% | 593 | 2138 |
| com.jetbrains.youtrackdb.internal.core.tx | 61.8% | 65.1% | 572 | 1498 |
| com.jetbrains.youtrackdb.internal.core.security | 32.1% | 22.4% | 548 | 807 |
| com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3 | 84.2% | 70.2% | 405 | 2563 |
| com.jetbrains.youtrackdb.internal.core.db.record | 70.6% | 60.9% | 403 | 1371 |
| com.jetbrains.youtrackdb.internal.core.storage.config | 62.5% | 47.1% | 359 | 957 |
| com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2 | 81.6% | 65.9% | 338 | 1838 |
| com.jetbrains.youtrackdb.internal.core.command | 49.5% | 50.0% | 320 | 634 |
| com.jetbrains.youtrackdb.internal.core.sql.executor.resultset | 49.8% | 48.9% | 309 | 616 |
| com.jetbrains.youtrackdb.internal.common.collection | 69.6% | 58.8% | 295 | 971 |
| com.jetbrains.youtrackdb.internal.core.security.symmetrickey | 26.6% | 9.4% | 282 | 384 |
| com.jetbrains.youtrackdb.internal.core.serialization | 14.2% | 6.4% | 277 | 323 |
| com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree | 88.1% | 75.7% | 273 | 2285 |
| com.jetbrains.youtrackdb.internal.core.query.live | 13.4% | 0.0% | 272 | 314 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas | 76.5% | 59.6% | 264 | 1123 |
| com.jetbrains.youtrackdb.internal.core.fetch | 46.6% | 30.9% | 248 | 464 |
| com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v1 | 0.0% | 0.0% | 242 | 242 |
| com.jetbrains.youtrackdb.internal.core.query | 41.1% | 28.3% | 228 | 387 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal | 70.9% | 68.4% | 219 | 753 |
| com.jetbrains.youtrackdb.internal.common.console | 0.0% | 0.0% | 212 | 212 |
| com.jetbrains.youtrackdb.internal.core.exception | 46.5% | 56.5% | 208 | 389 |
| com.jetbrains.youtrackdb.internal.common.jnr | 24.1% | 21.4% | 208 | 274 |
| com.jetbrains.youtrackdb.internal.core.sql.filter | 78.6% | 66.0% | 206 | 963 |
| com.jetbrains.youtrackdb.internal.core.sql.executor.match | 93.0% | 79.3% | 191 | 2741 |
| com.jetbrains.youtrackdb.internal.core | 70.0% | 55.7% | 182 | 606 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.graph | 82.3% | 67.5% | 170 | 963 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.index | 65.0% | 67.6% | 165 | 471 |
| com.jetbrains.youtrackdb.internal.core.metadata.security.binary | 0.0% | 0.0% | 164 | 164 |
| com.jetbrains.youtrackdb.internal.core.storage.disk | 83.3% | 72.2% | 159 | 954 |
| com.jetbrains.youtrackdb.internal.core.sql.operator | 83.7% | 76.4% | 154 | 947 |
| com.jetbrains.youtrackdb.api.gremlin.embedded | 3.8% | 1.8% | 152 | 158 |
| com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog | 50.2% | 20.9% | 150 | 301 |
| com.jetbrains.youtrackdb.internal.common.serialization | 34.5% | 27.1% | 146 | 223 |
| com.jetbrains.youtrackdb.internal.core.sql | 80.1% | 76.9% | 145 | 730 |
| com.jetbrains.youtrackdb.internal.core.security.authenticator | 25.5% | 15.0% | 140 | 188 |
| com.jetbrains.youtrackdb.api.gremlin.tokens.schema | 0.0% | 100.0% | 138 | 138 |
| com.jetbrains.youtrackdb.internal.core.storage.collection.v2 | 91.3% | 76.5% | 136 | 1557 |
| com.jetbrains.youtrackdb.internal.core.index.engine | 90.8% | 84.6% | 134 | 1451 |
| com.jetbrains.youtrackdb.internal.common.serialization.types | 84.2% | 86.7% | 130 | 824 |
| com.jetbrains.youtrackdb.internal.core.db.config | 0.0% | 0.0% | 130 | 130 |
| com.jetbrains.youtrackdb.internal.core.command.traverse | 62.9% | 39.2% | 127 | 342 |
| com.jetbrains.youtrackdb.internal.core.id | 64.5% | 63.9% | 124 | 349 |
| com.jetbrains.youtrackdb.internal.common.directmemory | 71.1% | 61.1% | 123 | 425 |
| com.jetbrains.youtrackdb.internal.core.engine | 17.1% | 11.9% | 121 | 146 |
| com.jetbrains.youtrackdb.internal.core.storage.memory | 61.8% | 57.6% | 117 | 306 |
| com.jetbrains.youtrackdb.internal.core.security.kerberos | 0.0% | 0.0% | 114 | 114 |
| com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2 | 87.6% | 69.9% | 111 | 896 |
| com.jetbrains.youtrackdb.internal.core.compression.impl | 0.0% | 0.0% | 104 | 104 |
| com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v1 | 66.6% | 44.4% | 102 | 305 |
| com.jetbrains.youtrackdb.internal.common.concur.lock | 87.0% | 72.0% | 96 | 737 |
| com.jetbrains.youtrackdb.internal.core.record | 63.3% | 50.7% | 90 | 245 |
| com.jetbrains.youtrackdb.internal.core.storage.ridbag | 87.1% | 64.8% | 86 | 668 |
| com.jetbrains.youtrackdb.internal.core.index.iterator | 43.3% | 44.2% | 85 | 150 |
| com.jetbrains.youtrackdb.internal.common.profiler | 44.4% | 50.0% | 84 | 151 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations | 90.6% | 82.8% | 84 | 891 |
| com.jetbrains.youtrackdb.internal.core.storage.cache | 76.9% | 59.3% | 78 | 337 |
| com.jetbrains.youtrackdb.internal.core.db.tool.importer | 59.4% | 48.7% | 73 | 180 |
| com.jetbrains.youtrackdb.internal.core.metadata.function | 72.6% | 45.8% | 73 | 266 |
| com.jetbrains.youtrackdb.internal.core.index.engine.v1 | 86.5% | 82.5% | 71 | 526 |
| com.jetbrains.youtrackdb.internal.core.db.record.record | 57.2% | 37.5% | 71 | 166 |
| com.jetbrains.youtrackdb.internal.core.metadata.sequence | 85.4% | 73.4% | 70 | 478 |
| com.jetbrains.youtrackdb.internal.core.storage | 38.9% | 37.5% | 66 | 108 |
| com.jetbrains.youtrackdb.internal.core.gremlin.io.binary | 16.5% | 0.0% | 66 | 79 |
| com.jetbrains.youtrackdb.internal.core.gremlin.io.graphson | 34.3% | 0.0% | 65 | 99 |
| com.jetbrains.youtrackdb.internal.core.config | 66.1% | 55.4% | 64 | 189 |
| com.jetbrains.youtrackdb.internal.core.sql.query | 79.1% | 57.9% | 64 | 306 |
| com.jetbrains.youtrackdb.internal.common.collection.closabledictionary | 87.9% | 68.5% | 62 | 511 |
| com.jetbrains.youtrackdb.internal.core.storage.fs | 72.9% | 65.9% | 62 | 229 |
| com.jetbrains.youtrackdb.internal.core.storage.cache.chm | 89.3% | 73.2% | 61 | 572 |
| com.jetbrains.youtrackdb.internal.core.sql.executor.metadata | 79.9% | 63.0% | 61 | 304 |
| com.jetbrains.youtrackdb.internal.core.cache | 71.4% | 56.6% | 60 | 210 |
| com.jetbrains.youtrackdb.api.config | 89.0% | 48.1% | 58 | 526 |
| com.jetbrains.youtrackdb.internal.core.command.script.formatter | 36.0% | 26.3% | 57 | 89 |
| com.jetbrains.youtrackdb.api | 28.8% | 50.0% | 52 | 73 |
| com.jetbrains.youtrackdb.internal.common.log | 78.6% | 58.0% | 49 | 229 |
| com.jetbrains.youtrackdb.internal.core.storage.collection | 94.3% | 78.9% | 48 | 847 |
| com.jetbrains.youtrackdb.internal.common.util | 88.3% | 83.3% | 47 | 402 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated | 79.5% | 59.7% | 46 | 224 |
| com.jetbrains.youtrackdb.internal.core.storage.index.nkbtree.normalizers | 71.5% | 20.0% | 45 | 158 |
| com.jetbrains.youtrackdb.internal.common.io | 88.0% | 79.6% | 42 | 351 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.deque | 72.5% | 55.0% | 38 | 138 |
| com.jetbrains.youtrackdb.internal.core.gremlin.service | 85.1% | 78.8% | 34 | 228 |
| com.jetbrains.youtrackdb.internal.common.concur.resource | 84.5% | 77.8% | 34 | 220 |
| com.jetbrains.youtrackdb.api.exception | 61.3% | 19.2% | 29 | 75 |
| com.jetbrains.youtrackdb.internal.core.sql.method.misc | 92.2% | 88.0% | 28 | 360 |
| com.jetbrains.youtrackdb.internal.core.iterator | 82.4% | 87.8% | 28 | 159 |
| com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization | 84.1% | 71.3% | 27 | 170 |
| com.jetbrains.youtrackdb.internal.core.storage.index.engine | 0.0% | 100.0% | 26 | 26 |
| com.jetbrains.youtrackdb.internal.core.util | 79.8% | 67.9% | 24 | 119 |
| com.jetbrains.youtrackdb.internal.core.db.record.ridbag | 84.0% | 68.3% | 24 | 150 |
| com.jetbrains.youtrackdb.internal.core.sql.functions | 90.4% | 78.9% | 23 | 240 |
| com.jetbrains.youtrackdb.internal.common.parser | 95.6% | 90.8% | 22 | 504 |
| com.jetbrains.youtrackdb.internal.core.command.script.transformer | 65.6% | 60.5% | 22 | 64 |
| com.jetbrains.youtrackdb.internal.core.sql.method | 87.1% | 81.2% | 21 | 163 |
| com.jetbrains.youtrackdb.internal.core.engine.local | 67.7% | 44.4% | 21 | 65 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary | 65.6% | 66.7% | 21 | 61 |
| com.jetbrains.youtrackdb.internal.core.storage.index.versionmap | 0.0% | 100.0% | 20 | 20 |
| com.jetbrains.youtrackdb.internal.core.storage.cache.chm.readbuffer | 84.4% | 67.0% | 20 | 128 |
| com.jetbrains.youtrackdb.internal.core.gremlin.io | 36.7% | 0.0% | 19 | 30 |
| com.jetbrains.youtrackdb.internal.core.fetch.remote | 41.9% | 0.0% | 18 | 31 |
| com.jetbrains.youtrackdb.internal.common.exception | 70.0% | 100.0% | 18 | 60 |
| com.jetbrains.youtrackdb.internal.core.metadata.schema.clusterselection | 63.3% | 31.2% | 18 | 49 |
| com.jetbrains.youtrackdb.internal.core.gremlin.io.gryo | 20.0% | 0.0% | 16 | 20 |
| com.jetbrains.youtrackdb.api.gremlin.tokens | 30.4% | 0.0% | 16 | 23 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.misc | 96.2% | 87.5% | 16 | 417 |
| com.jetbrains.youtrackdb.internal.core.sql.operator.math | 91.1% | 90.2% | 16 | 179 |
| com.jetbrains.youtrackdb.internal.common.profiler.metrics | 95.5% | 75.8% | 16 | 352 |
| com.jetbrains.youtrackdb.internal.core.command.script.transformer.result | 16.7% | 0.0% | 15 | 18 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer.record | 0.0% | 0.0% | 14 | 14 |
| com.jetbrains.youtrackdb.internal.core.servlet | 0.0% | 0.0% | 14 | 14 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.coll | 96.4% | 89.8% | 14 | 385 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl | 90.8% | 88.9% | 14 | 153 |
| com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect | 81.9% | 81.5% | 13 | 72 |
| com.jetbrains.youtrackdb.internal.core.command.script.js | 43.5% | 66.7% | 13 | 23 |
| com.jetbrains.youtrackdb.internal.annotations.gremlin.dsl | 97.6% | 92.0% | 12 | 504 |
| com.jetbrains.youtrackdb.internal.common.comparator | 90.2% | 83.3% | 12 | 123 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base | 95.8% | 66.5% | 12 | 283 |
| com.jetbrains.youtrackdb.internal.core.gql.parser | 95.5% | 89.7% | 11 | 245 |
| com.jetbrains.youtrackdb.internal.core.gremlin.jsr223 | 0.0% | 100.0% | 11 | 11 |
| com.jetbrains.youtrackdb.api.gremlin.embedded.schema | 0.0% | 100.0% | 10 | 10 |
| com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.filter | 69.7% | 75.0% | 10 | 33 |
| com.jetbrains.youtrackdb.internal.common.concur | 37.5% | 100.0% | 10 | 16 |
| com.jetbrains.youtrackdb.internal.core.metadata.security.jwt | 0.0% | 100.0% | 10 | 10 |
| com.jetbrains.youtrackdb.internal.core.dictionary | 0.0% | 100.0% | 9 | 9 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer.stream | 60.9% | 100.0% | 9 | 23 |
| com.jetbrains.youtrackdb.internal.common.thread | 95.6% | 92.5% | 9 | 203 |
| com.jetbrains.youtrackdb.internal.core.conflict | 55.0% | 100.0% | 9 | 20 |
| com.jetbrains.youtrackdb.internal.core.metadata.security.auth | 0.0% | 100.0% | 9 | 9 |
| com.jetbrains.youtrackdb.internal.core.metadata | 82.7% | 75.0% | 9 | 52 |
| com.jetbrains.youtrackdb.internal.common.stream | 90.8% | 91.7% | 8 | 87 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common | 76.5% | 25.0% | 8 | 34 |
| com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.map | 81.1% | 75.0% | 7 | 37 |
| com.jetbrains.youtrackdb.internal.common.profiler.monitoring | 94.4% | 90.6% | 7 | 124 |
| com.jetbrains.youtrackdb.internal.core.storage.index.sbtree | 0.0% | 0.0% | 7 | 7 |
| com.jetbrains.youtrackdb.internal.common.concur.collection | 88.1% | 66.7% | 7 | 59 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.operationsfreezer | 93.8% | 85.7% | 6 | 97 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.math | 97.3% | 97.1% | 6 | 222 |
| com.jetbrains.youtrackdb.internal.core.gremlin.sqlcommand | 88.7% | 87.5% | 6 | 53 |
| com.jetbrains.youtrackdb.internal.core.index.comparator | 50.0% | 100.0% | 5 | 10 |
| com.jetbrains.youtrackdb.internal.core.type | 76.2% | 0.0% | 5 | 21 |
| com.jetbrains.youtrackdb.internal.core.metadata.schema.schema | 94.8% | 100.0% | 3 | 58 |
| com.jetbrains.youtrackdb.internal.core.replication | 0.0% | 100.0% | 3 | 3 |
| com.jetbrains.youtrackdb.internal.core.gremlin.executor.transformer | 0.0% | 100.0% | 2 | 2 |
| com.jetbrains.youtrackdb.internal.common.types | 97.7% | 90.9% | 2 | 86 |
| com.jetbrains.youtrackdb.internal.common.listener | 86.7% | 66.7% | 2 | 15 |
| com.jetbrains.youtrackdb.internal.core.storage.cache.local.aoc | 0.0% | 100.0% | 2 | 2 |
| com.jetbrains.youtrackdb.internal.core.collate | 97.6% | 91.7% | 1 | 41 |
| com.jetbrains.youtrackdb.internal.common.hash | 99.1% | 100.0% | 1 | 114 |
| com.jetbrains.youtrackdb.internal.core.gql.executor | 98.8% | 96.9% | 1 | 86 |
| com.jetbrains.youtrackdb.internal.core.engine.memory | 91.7% | 100.0% | 1 | 12 |
| com.jetbrains.youtrackdb.internal.core.storage.cache.chm.writequeue | 96.8% | 87.5% | 1 | 31 |
| com.jetbrains.youtrackdb.internal.core.gql.planner | 96.0% | 83.3% | 1 | 25 |
| com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue | 0.0% | 100.0% | 1 | 1 |
| com.jetbrains.youtrackdb.internal.core.index.multivalue | 66.7% | 100.0% | 1 | 3 |
| com.jetbrains.youtrackdb.internal.common.factory | 100.0% | 100.0% | 0 | 44 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.stat | 100.0% | 100.0% | 0 | 108 |
| com.jetbrains.youtrackdb.internal.core.metadata.schema.validation | 100.0% | 100.0% | 0 | 20 |
| com.jetbrains.youtrackdb.internal.core.sql.method.sequence | 100.0% | 100.0% | 0 | 39 |
| com.jetbrains.youtrackdb.internal.core.gql.executor.resultset | 100.0% | 96.7% | 0 | 72 |
| com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy | 100.0% | 100.0% | 0 | 15 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.text | 100.0% | 100.0% | 0 | 109 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.sequence | 100.0% | 100.0% | 0 | 16 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.conversion | 100.0% | 100.0% | 0 | 61 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.geo | 100.0% | 100.0% | 0 | 27 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.result | 100.0% | 100.0% | 0 | 6 |
