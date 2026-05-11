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

**Measured at:** Track 7 Step 8 HEAD (2026-04-20)
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

| Package | Line% | Branch% | Uncovered Lines | Total Lines |
|---------|-------|---------|-----------------|-------------|
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

---

## Post-Track-22a Measurement

**Measured at:** Track 22a Step 10 HEAD (2026-05-09)
**Report:** `.coverage/reports/youtrackdb-core/jacoco.xml`

> **Interim post-22a aggregate; final headline measured after 22b
> denominator drop per plan §Goals** (recorded as an OBSERVATION per
> Phase A finding A1 — coverage gaming is forbidden, this number is
> not a gate, it is a checkpoint snapshot).

### Aggregate Totals

| Metric | Baseline | Post-Track-7 | Post-Track-22a | Delta vs Baseline |
|---|---|---|---|---|
| **Line coverage** | 63.6% (56,269 / 88,514) | 70.6% (66,555 / 94,278) | **80.3%** (75,910 / 94,504) | **+16.7 pp**, +19,641 covered |
| **Branch coverage** | 53.3% (23,157 / 43,458) | 61.0% (27,977 / 45,869) | **69.9%** (32,326 / 46,223) | **+16.6 pp**, +9,169 covered |
| **Packages** | 177 | 179 | 178 | +1 |

The headline interim aggregate sits at **80.3% line / 69.9% branch**,
~5 pp below the 85% line target and ~0.1 pp below the 70% branch
target. The plan §Goals deliberately defers final headline measurement
until after Track 22b drops the dead-code denominator (~250 LOC of
vestigial scaffolding still pinned by `*DeadCodeTest` markers, plus
the `core.command.script` / `core.query.live` 22c-deferred clusters);
22a alone closes the test-side gap and leaves the denominator-side
gap to 22b.

### Track 22a Target Packages

Baseline / Post-Track-7 → Post-Track-22a coverage for the packages
Track 22a explicitly targeted (Steps 2–6 + Step 8 production-bug
fixes):

| Package | Baseline Line% / Branch% | Post-Track-7 Line% / Branch% | Post-Track-22a Line% / Branch% | Uncov Lines (base → post) |
|---|---|---|---|---|
| `core.tx` | 61.8% / 65.1% | 61.8% / 65.1% | **73.1% / 69.6%** | 572 → 403 |
| `core.gremlin` | 53.5% / 68.8% | 53.9% / 69.4% | **56.3% / 72.1%** | 713 → 677 |
| `core.engine` | 17.1% / 11.9% | 17.1% / 11.9% | **65.8% / 73.8%** | 121 → 50 |
| `core.exception` | 40.9% / 43.5% | 46.5% / 56.5% | **99.0% / 87.1%** | 230 → 4 |
| `core.cache` | 71.4% / 56.6% | 71.4% / 56.6% | **85.2% / 72.4%** | 60 → 31 |
| `core.id` | 64.2% / 63.9% | 64.5% / 63.9% | **94.6% / 85.6%** | 125 → 19 |
| `core.conflict` | 55.0% / 100.0% | 55.0% / 100.0% | **90.0% / 100.0%** | 9 → 2 |
| `core.collate` | 95.1% / 91.7% | 97.6% / 91.7% | **100.0% / 100.0%** | 2 → 0 |
| `core.type` | 76.2% / 0.0% | 76.2% / 0.0% | **100.0% / 100.0%** | 5 → 0 |
| `core.replication` | 0.0% / 100.0% | 0.0% / 100.0% | **100.0% / 100.0%** | 3 → 0 |
| `api.exception` | 53.3% / 11.5% | 61.3% / 19.2% | **100.0% / 96.2%** | 35 → 0 |
| `api.config` | 88.8% / 48.1% | 89.0% / 48.1% | **95.4% / 71.2%** | 58 → 24 |
| `core.servlet` | 0.0% / 0.0% | 0.0% / 0.0% | **35.7% / 33.3%** | 14 → 9 |

The largest absolute gains (`core.exception` 230→4 uncovered,
`core.id` 125→19, `core.tx` 572→403, `core.engine` 121→50) reflect
Steps 2 / 4 / 5's PSI-throw-site-filtered exception fan, the live
`core.id` cluster sweep, the transaction-path coverage, and the
`core.engine` lifecycle tests. `core.servlet` deliberately remains at
35.7% line because its single live no-op branch is its only
coverable surface — finding A4 reframed it as `22a-coverage-only` and
the rest is `22b-delete`. `core.gremlin` improves modestly because
most of its package surface is covered through Cucumber feature tests
in the `embedded` module rather than `core` unit tests; the 22a delta
here reflects the focused Step 3 unit-test additions only.

### Coverage-Gate on Changed Production Lines

The cumulative Track 22a production-line footprint is the 6 lines and
2 branches changed by Step 8's in-22a inherited production-bug fixes
(`LRUCache.removeEldestEntry`, `EntityImpl.OPPOSITE_LINK_CONTAINER_PREFIX`,
`BinarySerializerFactory.create`, `BasicCommandContext.copy`,
`MemoryAndLocalPaginatedEnginesInitializer.warningInvalidMemoryLeftValue`).
Each Step 8 fix paired with a falsifiable regression test (the
flipped WHEN-FIXED pin, plus one transitive flip in
`StringCacheTest`). Step 8's foreground coverage gate confirmed
100% line / 100% branch on the changed production lines.

### Post-Track-22a Per-Package Coverage

Sorted by uncovered lines descending.

| Package | Line% | Branch% | Uncovered Lines | Total Lines |
|---------|-------|---------|-----------------|-------------|
| com.jetbrains.youtrackdb.internal.core.record.impl | 66.1% | 57.2% | 1283 | 3780 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local | 63.4% | 59.3% | 1141 | 3114 |
| com.jetbrains.youtrackdb.internal.core.db | 72.8% | 58.4% | 1030 | 3788 |
| com.jetbrains.youtrackdb.internal.core.sql.executor | 87.7% | 76.8% | 832 | 6749 |
| com.jetbrains.youtrackdb.internal.core.db.tool | 63.5% | 52.6% | 831 | 2278 |
| com.jetbrains.youtrackdb.internal.core.gremlin | 56.3% | 72.1% | 677 | 1550 |
| com.jetbrains.youtrackdb.internal.core.metadata.schema | 84.8% | 69.9% | 660 | 4355 |
| com.jetbrains.youtrackdb.internal.core.storage.cache.local | 69.4% | 56.4% | 616 | 2015 |
| com.jetbrains.youtrackdb.internal.core.index | 80.7% | 70.3% | 615 | 3190 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary | 82.8% | 80.3% | 582 | 3375 |
| com.jetbrains.youtrackdb.internal.core.metadata.security | 74.6% | 57.9% | 542 | 2138 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string | 64.1% | 59.5% | 519 | 1444 |
| com.jetbrains.youtrackdb.internal.core.command.script | 53.9% | 37.8% | 465 | 1008 |
| com.jetbrains.youtrackdb.internal.core.tx | 73.1% | 69.6% | 403 | 1498 |
| com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3 | 86.0% | 72.9% | 359 | 2563 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer | 66.6% | 60.4% | 358 | 1073 |
| com.jetbrains.youtrackdb.internal.core.storage.config | 68.2% | 51.4% | 304 | 957 |
| com.jetbrains.youtrackdb.internal.common.collection | 72.3% | 61.5% | 286 | 1034 |
| com.jetbrains.youtrackdb.internal.core.sql.executor.match | 92.3% | 78.2% | 265 | 3453 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas | 78.7% | 62.7% | 239 | 1123 |
| com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2 | 87.2% | 69.4% | 235 | 1838 |
| com.jetbrains.youtrackdb.internal.core.security.symmetrickey | 43.2% | 38.3% | 218 | 384 |
| com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v1 | 12.4% | 7.7% | 212 | 242 |
| com.jetbrains.youtrackdb.internal.common.console | 0.0% | 0.0% | 212 | 212 |
| com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree | 90.8% | 79.9% | 210 | 2285 |
| com.jetbrains.youtrackdb.internal.common.jnr | 24.1% | 21.4% | 208 | 274 |
| com.jetbrains.youtrackdb.internal.core.sql.filter | 78.6% | 66.0% | 206 | 963 |
| com.jetbrains.youtrackdb.internal.core.fetch | 57.8% | 48.2% | 196 | 464 |
| com.jetbrains.youtrackdb.internal.core | 70.3% | 56.2% | 180 | 606 |
| com.jetbrains.youtrackdb.internal.core.query | 53.5% | 40.0% | 180 | 387 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.graph | 82.3% | 67.5% | 170 | 963 |
| com.jetbrains.youtrackdb.internal.core.sql.operator | 83.7% | 76.5% | 154 | 947 |
| com.jetbrains.youtrackdb.api.gremlin.embedded | 3.8% | 1.8% | 152 | 158 |
| com.jetbrains.youtrackdb.internal.core.schedule | 86.7% | 75.6% | 147 | 1102 |
| com.jetbrains.youtrackdb.internal.core.security | 82.0% | 71.3% | 145 | 807 |
| com.jetbrains.youtrackdb.internal.core.command | 77.8% | 70.2% | 141 | 635 |
| com.jetbrains.youtrackdb.internal.core.storage.disk | 85.2% | 73.9% | 141 | 954 |
| com.jetbrains.youtrackdb.api.gremlin.tokens.schema | 0.0% | 100.0% | 138 | 138 |
| com.jetbrains.youtrackdb.internal.core.index.engine | 90.9% | 85.1% | 132 | 1451 |
| com.jetbrains.youtrackdb.internal.core.db.record | 90.7% | 79.5% | 127 | 1371 |
| com.jetbrains.youtrackdb.internal.core.storage.collection.v2 | 91.8% | 76.5% | 127 | 1557 |
| com.jetbrains.youtrackdb.internal.common.directmemory | 71.1% | 61.1% | 123 | 425 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal | 85.9% | 71.8% | 106 | 753 |
| com.jetbrains.youtrackdb.internal.core.sql | 85.6% | 82.2% | 105 | 730 |
| com.jetbrains.youtrackdb.internal.common.concur.lock | 87.0% | 71.7% | 96 | 737 |
| com.jetbrains.youtrackdb.internal.common.serialization.types | 88.7% | 86.7% | 93 | 824 |
| com.jetbrains.youtrackdb.internal.common.profiler | 48.8% | 55.3% | 84 | 164 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations | 90.6% | 82.8% | 84 | 891 |
| com.jetbrains.youtrackdb.internal.core.record | 66.1% | 50.7% | 83 | 245 |
| com.jetbrains.youtrackdb.internal.core.security.kerberos | 28.1% | 25.0% | 82 | 114 |
| com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2 | 90.8% | 75.6% | 82 | 896 |
| com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v1 | 75.4% | 54.9% | 75 | 305 |
| com.jetbrains.youtrackdb.internal.core.serialization | 77.7% | 71.8% | 72 | 323 |
| com.jetbrains.youtrackdb.internal.core.index.engine.v1 | 87.1% | 85.4% | 68 | 526 |
| com.jetbrains.youtrackdb.internal.core.query.live | 78.3% | 72.5% | 68 | 314 |
| com.jetbrains.youtrackdb.internal.core.storage.ridbag | 90.0% | 67.9% | 67 | 668 |
| com.jetbrains.youtrackdb.internal.core.sql.query | 79.1% | 57.9% | 64 | 306 |
| com.jetbrains.youtrackdb.internal.core.config | 68.3% | 55.4% | 60 | 189 |
| com.jetbrains.youtrackdb.api | 28.8% | 50.0% | 52 | 73 |
| com.jetbrains.youtrackdb.internal.core.engine | 65.8% | 73.8% | 50 | 146 |
| com.jetbrains.youtrackdb.internal.common.log | 78.6% | 58.0% | 49 | 229 |
| com.jetbrains.youtrackdb.internal.common.util | 88.3% | 83.3% | 47 | 402 |
| com.jetbrains.youtrackdb.internal.core.metadata.sequence | 90.6% | 75.5% | 45 | 478 |
| com.jetbrains.youtrackdb.internal.common.collection.closabledictionary | 91.9% | 73.3% | 43 | 528 |
| com.jetbrains.youtrackdb.internal.core.storage.cache.chm | 92.8% | 78.2% | 42 | 585 |
| com.jetbrains.youtrackdb.internal.common.io | 88.0% | 79.6% | 42 | 351 |
| com.jetbrains.youtrackdb.internal.core.security.authenticator | 78.7% | 64.0% | 40 | 188 |
| com.jetbrains.youtrackdb.internal.core.storage.index.nkbtree.normalizers | 74.7% | 23.3% | 40 | 158 |
| com.jetbrains.youtrackdb.internal.core.storage.fs | 83.4% | 84.1% | 38 | 229 |
| com.jetbrains.youtrackdb.internal.common.serialization | 83.4% | 62.9% | 37 | 223 |
| com.jetbrains.youtrackdb.internal.core.metadata.function | 86.1% | 77.8% | 37 | 266 |
| com.jetbrains.youtrackdb.internal.core.gremlin.service | 85.1% | 78.8% | 34 | 228 |
| com.jetbrains.youtrackdb.internal.common.concur.resource | 84.5% | 80.6% | 34 | 220 |
| com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog | 89.0% | 57.4% | 33 | 301 |
| com.jetbrains.youtrackdb.internal.core.storage.cache | 90.2% | 77.5% | 33 | 337 |
| com.jetbrains.youtrackdb.internal.core.gremlin.io.graphson | 67.7% | 33.3% | 32 | 99 |
| com.jetbrains.youtrackdb.internal.core.cache | 85.2% | 72.4% | 31 | 210 |
| com.jetbrains.youtrackdb.internal.core.sql.method.misc | 92.2% | 88.0% | 28 | 360 |
| com.jetbrains.youtrackdb.internal.core.iterator | 82.4% | 87.8% | 28 | 159 |
| com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization | 84.1% | 71.3% | 27 | 170 |
| com.jetbrains.youtrackdb.internal.core.compression.impl | 75.0% | 57.4% | 26 | 104 |
| com.jetbrains.youtrackdb.internal.core.command.traverse | 92.4% | 82.3% | 26 | 342 |
| com.jetbrains.youtrackdb.api.config | 95.4% | 71.2% | 24 | 526 |
| com.jetbrains.youtrackdb.internal.core.util | 80.7% | 67.9% | 23 | 119 |
| com.jetbrains.youtrackdb.internal.core.sql.functions | 90.4% | 78.9% | 23 | 240 |
| com.jetbrains.youtrackdb.internal.common.parser | 95.6% | 90.8% | 22 | 504 |
| com.jetbrains.youtrackdb.internal.core.sql.executor.metadata | 92.8% | 79.5% | 22 | 304 |
| com.jetbrains.youtrackdb.internal.core.storage.collection | 97.4% | 81.7% | 22 | 847 |
| com.jetbrains.youtrackdb.internal.core.index.iterator | 86.0% | 89.5% | 21 | 150 |
| com.jetbrains.youtrackdb.internal.core.storage.memory | 93.5% | 83.3% | 20 | 306 |
| com.jetbrains.youtrackdb.internal.core.db.record.ridbag | 87.3% | 78.3% | 19 | 150 |
| com.jetbrains.youtrackdb.internal.core.id | 94.6% | 85.6% | 19 | 349 |
| com.jetbrains.youtrackdb.internal.common.exception | 70.0% | 100.0% | 18 | 60 |
| com.jetbrains.youtrackdb.internal.core.sql.method | 89.6% | 86.2% | 17 | 163 |
| com.jetbrains.youtrackdb.internal.core.engine.local | 73.8% | 55.6% | 17 | 65 |
| com.jetbrains.youtrackdb.internal.core.metadata.security.binary | 89.6% | 50.0% | 17 | 164 |
| com.jetbrains.youtrackdb.internal.core.db.record.record | 90.4% | 79.2% | 16 | 166 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.deque | 88.4% | 77.5% | 16 | 138 |
| com.jetbrains.youtrackdb.api.gremlin.tokens | 30.4% | 0.0% | 16 | 23 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.misc | 96.2% | 87.5% | 16 | 417 |
| com.jetbrains.youtrackdb.internal.core.sql.operator.math | 91.1% | 90.2% | 16 | 179 |
| com.jetbrains.youtrackdb.internal.common.profiler.metrics | 95.5% | 75.8% | 16 | 352 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.coll | 96.4% | 89.8% | 14 | 385 |
| com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect | 81.9% | 81.5% | 13 | 72 |
| com.jetbrains.youtrackdb.internal.common.comparator | 90.2% | 83.3% | 12 | 123 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base | 95.8% | 66.5% | 12 | 283 |
| com.jetbrains.youtrackdb.internal.core.command.script.js | 47.8% | 66.7% | 12 | 23 |
| com.jetbrains.youtrackdb.internal.core.command.script.transformer | 82.8% | 92.1% | 11 | 64 |
| com.jetbrains.youtrackdb.internal.core.gql.parser | 95.5% | 89.7% | 11 | 245 |
| com.jetbrains.youtrackdb.internal.core.gremlin.jsr223 | 0.0% | 100.0% | 11 | 11 |
| com.jetbrains.youtrackdb.internal.core.fetch.remote | 67.7% | 0.0% | 10 | 31 |
| com.jetbrains.youtrackdb.api.gremlin.embedded.schema | 0.0% | 100.0% | 10 | 10 |
| com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.filter | 69.7% | 75.0% | 10 | 33 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated | 96.0% | 73.6% | 9 | 224 |
| com.jetbrains.youtrackdb.internal.core.servlet | 35.7% | 33.3% | 9 | 14 |
| com.jetbrains.youtrackdb.internal.core.metadata | 82.7% | 75.0% | 9 | 52 |
| com.jetbrains.youtrackdb.internal.common.thread | 96.1% | 92.5% | 8 | 203 |
| com.jetbrains.youtrackdb.internal.common.stream | 90.8% | 91.7% | 8 | 87 |
| com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.map | 81.1% | 75.0% | 7 | 37 |
| com.jetbrains.youtrackdb.internal.common.profiler.monitoring | 94.4% | 90.6% | 7 | 124 |
| com.jetbrains.youtrackdb.internal.core.db.tool.importer | 96.1% | 86.8% | 7 | 180 |
| com.jetbrains.youtrackdb.internal.common.concur.collection | 88.1% | 66.7% | 7 | 59 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.operationsfreezer | 93.8% | 85.7% | 6 | 97 |
| com.jetbrains.youtrackdb.internal.common.concur | 62.5% | 100.0% | 6 | 16 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.math | 97.3% | 97.1% | 6 | 222 |
| com.jetbrains.youtrackdb.internal.core.db.config | 95.4% | 100.0% | 6 | 130 |
| com.jetbrains.youtrackdb.internal.core.gremlin.sqlcommand | 88.7% | 87.5% | 6 | 53 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.index | 98.9% | 93.6% | 5 | 471 |
| com.jetbrains.youtrackdb.internal.core.exception | 99.0% | 87.1% | 4 | 389 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer.stream | 82.6% | 100.0% | 4 | 23 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer.record | 78.6% | 0.0% | 3 | 14 |
| com.jetbrains.youtrackdb.internal.core.sql.executor.resultset | 99.5% | 91.7% | 3 | 616 |
| com.jetbrains.youtrackdb.internal.core.metadata.schema.clusterselection | 93.9% | 75.0% | 3 | 49 |
| com.jetbrains.youtrackdb.internal.core.gremlin.executor.transformer | 0.0% | 100.0% | 2 | 2 |
| com.jetbrains.youtrackdb.internal.core.storage | 98.1% | 87.5% | 2 | 108 |
| com.jetbrains.youtrackdb.internal.common.types | 97.7% | 90.9% | 2 | 86 |
| com.jetbrains.youtrackdb.internal.common.listener | 86.7% | 66.7% | 2 | 15 |
| com.jetbrains.youtrackdb.internal.core.conflict | 90.0% | 100.0% | 2 | 20 |
| com.jetbrains.youtrackdb.internal.core.storage.cache.local.aoc | 0.0% | 100.0% | 2 | 2 |
| com.jetbrains.youtrackdb.internal.common.hash | 99.1% | 100.0% | 1 | 114 |
| com.jetbrains.youtrackdb.internal.core.gql.executor | 98.8% | 96.9% | 1 | 86 |
| com.jetbrains.youtrackdb.internal.core.metadata.schema.schema | 98.3% | 100.0% | 1 | 58 |
| com.jetbrains.youtrackdb.internal.core.storage.cache.chm.readbuffer | 99.2% | 78.4% | 1 | 128 |
| com.jetbrains.youtrackdb.internal.core.storage.cache.chm.writequeue | 96.8% | 87.5% | 1 | 31 |
| com.jetbrains.youtrackdb.internal.core.gql.planner | 96.0% | 83.3% | 1 | 25 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common | 97.1% | 100.0% | 1 | 34 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl | 99.3% | 100.0% | 1 | 153 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary | 98.4% | 100.0% | 1 | 61 |
| com.jetbrains.youtrackdb.internal.core.dictionary | 100.0% | 100.0% | 0 | 9 |
| com.jetbrains.youtrackdb.internal.common.factory | 100.0% | 100.0% | 0 | 44 |
| com.jetbrains.youtrackdb.internal.core.gremlin.io.gryo | 100.0% | 100.0% | 0 | 20 |
| com.jetbrains.youtrackdb.internal.core.collate | 100.0% | 100.0% | 0 | 41 |
| com.jetbrains.youtrackdb.api.exception | 100.0% | 96.2% | 0 | 75 |
| com.jetbrains.youtrackdb.internal.core.storage.index.versionmap | 100.0% | 100.0% | 0 | 20 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.stat | 100.0% | 100.0% | 0 | 108 |
| com.jetbrains.youtrackdb.internal.core.metadata.schema.validation | 100.0% | 100.0% | 0 | 20 |
| com.jetbrains.youtrackdb.internal.core.sql.method.sequence | 100.0% | 100.0% | 0 | 39 |
| com.jetbrains.youtrackdb.internal.core.command.script.formatter | 100.0% | 97.4% | 0 | 89 |
| com.jetbrains.youtrackdb.internal.core.command.script.transformer.result | 100.0% | 100.0% | 0 | 18 |
| com.jetbrains.youtrackdb.internal.core.gql.executor.resultset | 100.0% | 96.7% | 0 | 72 |
| com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy | 100.0% | 100.0% | 0 | 15 |
| com.jetbrains.youtrackdb.internal.core.engine.memory | 100.0% | 100.0% | 0 | 12 |
| com.jetbrains.youtrackdb.internal.core.gremlin.io.binary | 100.0% | 100.0% | 0 | 79 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.text | 100.0% | 100.0% | 0 | 109 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.sequence | 100.0% | 100.0% | 0 | 16 |
| com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue | 100.0% | 100.0% | 0 | 1 |
| com.jetbrains.youtrackdb.internal.core.storage.index.engine | 100.0% | 100.0% | 0 | 26 |
| com.jetbrains.youtrackdb.internal.core.metadata.security.jwt | 100.0% | 100.0% | 0 | 10 |
| com.jetbrains.youtrackdb.internal.core.index.multivalue | 100.0% | 100.0% | 0 | 3 |
| com.jetbrains.youtrackdb.internal.core.replication | 100.0% | 100.0% | 0 | 3 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.conversion | 100.0% | 100.0% | 0 | 61 |
| com.jetbrains.youtrackdb.internal.core.storage.index.sbtree | 100.0% | 100.0% | 0 | 7 |
| com.jetbrains.youtrackdb.internal.core.index.comparator | 100.0% | 100.0% | 0 | 10 |
| com.jetbrains.youtrackdb.internal.core.gremlin.io | 100.0% | 100.0% | 0 | 30 |
| com.jetbrains.youtrackdb.internal.core.metadata.security.auth | 100.0% | 100.0% | 0 | 9 |
| com.jetbrains.youtrackdb.internal.core.type | 100.0% | 100.0% | 0 | 21 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.geo | 100.0% | 100.0% | 0 | 27 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.result | 100.0% | 100.0% | 0 | 6 |

### Recovery-Gap Residuals (Track 22c marker-rewrite filter input)

WHEN-FIXED grep against `core/src/test/java` after Step 9 returns
**116 markers** (down from 218 at Step 1). The drop reflects Step 8's
production-bug fixes (LRUCache off-by-one, EntityImpl `final`,
BinarySerializerFactory singleton, BasicCommandContext null guard,
MemoryAndLocalPaginatedEnginesInitializer overload disambiguation
— each removed its associated WHEN-FIXED pin lockstep with the fix)
plus Step 9's DRY refactors that consolidated several
`*DeadCodeTest` shape pins into single shared assertions.

The markers below pin symbols **not present** in the cluster
classification table — Track 22c's marker-rewrite filter must process
these to either expand its YTDB-issue list or fold them into
22c-defer entries. Production-bug pins are partitioned from
deletion-candidate pins; the filter must skip the production-bug
rows.

*Production-bug pin markers (NOT deletion candidates — keep as
pins for the inherited bug-fix backlog; 22c filter must skip):*

- `core/command/script/ScriptManagerTest.java:824`
  (`closeAll` vs `close(dbName)`)
- `core/metadata/security/ImmutableUserTest.java:184`
  (`populateSystemRoles` null guard)
- `core/security/SecurityManagerTest.java:261, 268, 281`
  (`SALT_CACHE` algorithm-key bug + `NumberFormatException`)
- `core/security/TokenSignImplTest.java:292`
  (`readKeyFromConfig` honoring configured key)
- `core/sql/SQLHelperParseValueScalarTest.java:348`
  (`"2000t"` DATETIME classification NFE)
- `core/sql/executor/SelectExecutionPlannerBranchTest.java:172, 400, 672`
  (`colleciton` typo + stream-exhaustion + assertion typo)
- `core/sql/method/SQLMethodRuntimeTest.java:260`
  (`SQLMethodRuntime.setParameters` cast behavior)
- `core/sql/query/BasicLegacyResultSetTest.java:371, 495, 520, 639, 756`
  (`iterator()`, `containsAll`, `retainAll`, `equals`, `add(T)` limit drop)

*Deletion-candidate pin markers missing from the cluster table
(22c filter input — expand the table, fold into 22c-defer issues,
or stage as in-22b late-table additions):*

- `core/sql/SqlRootDeadCodeTest.java:133, 159, 263`
  (`CommandExecutorSQLAbstract`, `DefaultCommandExecutorSQLFactory`)
- `core/sql/SQLEngineSpiCacheTest.java:351`
  (`DefaultCommandExecutorSQLFactory` + `DynamicSQLElementFactory`
  — same symbols as the SqlRootDeadCodeTest deletion candidates)
- `core/sql/executor/SqlExecutorDeadCodeTest.java:78, 109, 126,
  140, 160, 180, 199, 204, 213, 239, 269, 297, 334, 352`
  (`InfoExecutionPlan.{setSteps, toResult}`, `TraverseResult.depth`,
  `TraverseResult.copy()` — 13 markers + 1 javadoc anchor at line 67)
- `core/sql/query/SqlQueryDeadCodeTest.java` (11 markers)
  (`ConcurrentLegacyResultSet`, `LiveLegacyResultSet`,
  `LiveResultListener`, `LocalLiveResultListener`)
- `core/security/symmetrickey/UserSymmetricKeyConfigDeadCodeTest.java:243`
  (`UserSymmetricKeyConfig` deletion-candidate)
- `core/command/script/SQLScriptEngineTest.java:318`
  (`CommandScript.execute returns List.of()` — Track 9 22c-defer cluster)
- `core/command/script/DatabaseScriptManagerTest.java:208`
  (`DatabaseScriptManager.pooledEngines` reflection chain — pinned
  for replacement when a test-visible accessor exists)

Markers covered by the cluster classification table (22b-delete or
22c-defer) and therefore NOT residual:

- `core/query/live/LiveQueryDeadCodeTest.java` (26 markers) — Track 10
  `core/query/live` cluster, `22c-defer` (LiveQueryHook live surface
  re-investigation).
- `core/query/live/LiveQueryHookStaticApiTest.java` (24 markers) —
  same cluster.
- `core/command/script/CommandScriptDeadCodeTest.java` (19 markers) —
  Track 9 `CommandScript` cluster, `22c-defer` (extended-sibling
  YTDB-issue rationale).

## Post-Step-15 Measurement (Track 22b end-of-track)

**Measured at:** Track 22b Step 15 HEAD (`75dc2bc46f`, 2026-05-11) — orchestrator-run staged coverage build per Step 16
**Report:** `.coverage/reports/youtrackdb-core/jacoco.xml` (generated by
`./mvnw -pl core test -P coverage -DskipITs` + `./mvnw -pl core jacoco:report@jacoco-report -P coverage`,
the latter binding to the `jacoco-report` execution so the
`sql/parser/*.class`, `gql/parser/gen/*.class`, and `api/gremlin/*.class`
exclusions defined in `pom.xml` are applied.)

> **Final end-of-22b headline** per plan §Goals. Track 22b's
> deletion lockstep dropped the denominator from 94,504 → 92,385
> lines (-2,119; -944 branches) and shifted the per-package shape
> by removing 5 packages outright (Binary Token / JWT cluster,
> sbtree/singlevalue/v1, plus three smaller surfaces). Coverage
> meets the plan's amended `~82–83 % line / ~70–71 % branch`
> target on the branch axis and lands 0.6 pp below the lower line
> bound — within the documented limitation per Step 16's outcome
> decision tree. The binding PR gate is `coverage-gate.py` on the
> cumulative diff, which **PASSED** at **100.0 % line (43/43) /
> 71.4 % branch (30/42)** — the actual merge-blocking measurement
> for Track 22b.

### Aggregate Totals

| Metric | Baseline | Post-Track-7 | Post-Track-22a | **Post-Step-15** | Delta vs Baseline |
|---|---|---|---|---|---|
| **Line coverage** | 63.6% (56,269 / 88,514) | 70.6% (66,555 / 94,278) | 80.3% (75,910 / 94,504) | **81.4%** (75,176 / 92,385) | **+17.8 pp**, +18,907 covered |
| **Branch coverage** | 53.3% (23,157 / 43,458) | 61.0% (27,977 / 45,869) | 69.9% (32,326 / 46,223) | **71.1%** (32,182 / 45,279) | **+17.8 pp**, +9,025 covered |
| **Packages** | 177 | 179 | 178 | **173** | -4 (5 packages emptied by 22b deletion lockstep; one package added by 22a Step 8) |

> **Denominator note.** Line denominator moved 88,514 (baseline,
> Apr 2026) → 94,504 (post-22a, May 9 — develop rebased in mid-
> track added live code) → 92,385 (post-Step-15, May 11 — Track 22b
> deletion lockstep dropped 2,119 lines + Step 15 trim dropped a
> handful more). Branch denominator moved 43,458 → 46,223 → 45,279
> (-944 from 22b). The "+17.8 pp" headline deltas are *coverage-pp*
> deltas, not raw-line-count deltas — the absolute numerators /
> denominators in each cell are authoritative.

### Track 22b deletion-lockstep coverage impact

Track 22b is purely a **denominator-reduction** track on the
production side (deletion-only commits) plus a single 5-hunk
dead-arm trim on `FetchHelper.java`. The aggregate gain of +1.1 pp
line / +1.2 pp branch is driven by:

- **Denominator drop** from 11 cluster commits (Clusters A–K) +
  Step 15's FetchHelper trim: 94,504 → 92,385 total lines (-2,119).
  Every covered line stays covered; the percentage rises as the
  uncovered tail shrinks.
- **Step 13 test-additive numerator gain** (5 commits across
  `core/fetch/FetchHelperBranchCoverageTest`, `DepthFetchPlanTest`,
  `ScriptManager` extensions, and `Jsr223ScriptExecutor` arms) —
  raised `core.fetch` to 80.5 % / 69.0 % (was 46.6 % / 30.9 % at
  baseline) and pushed `core.command.script` from 31.4 % / 22.2 %
  (baseline) to **94.9 % / 84.9 %** at end-of-22b.
- **Step 15 dead-arm trim** trimmed five `FetchHelper` instanceof
  arms that were structurally unreachable (Java type system +
  `EntityImpl.setProperty` → `PropertyTypeInternal.getTypeByValue`
  → `EMBEDDEDLIST.convert` / `EMBEDDEDMAP.convert` chain guarantees
  no raw `T[]` array or `MultiCollectionIterator` ever lands in
  `FetchHelper`); the trim removed ~5 uncovered branches without
  changing the covered count.

### Coverage-Gate on Changed Production Lines (cumulative, end-of-22b)

The binding PR gate. Result:

```
$ python3 .github/scripts/coverage-gate.py --line-threshold 85 \
    --branch-threshold 70 --compare-branch origin/develop \
    --coverage-dir .coverage/reports/youtrackdb-core
Found 1 JaCoCo report(s)
Checking coverage for 625 changed file(s)
Line coverage: PASSED — 100.0% (43/43 lines)
Branch coverage: PASSED — 71.4% (30/42 branches)
PASSED: All coverage meets thresholds (85% line, 70% branch)
```

The 43 changed production lines / 42 changed production branches
are concentrated in the single `FetchHelper.java` file modified by
Step 15 (the rest of the 625 changed files are test-additive — they
register as "changed" because they're new but don't carry production
line / branch changes that the gate measures). The 71.4 % branch
result exceeds the first `[!]` episode's pre-trim prediction of
58–62 % branch — the conservative trim was sufficient to close the
gate even without pinning the residual `requires-deeper-analysis`
arms.

### Top remaining uncovered surfaces (Track 22c handoff candidates)

Sorted by uncovered line count. These are the largest residual
gaps; most are structurally hard (storage internals, generated
parser glue) and explicitly accepted under D4 (storage-internal
coverage allowance).

| Package | Line% | Branch% | Uncovered Lines | Total Lines |
|---|---|---|---|---|
| core.record.impl | 65.9% | 57.0% | 1283 | 3764 |
| core.storage.impl.local | 63.4% | 59.4% | 1140 | 3114 |
| core.db | 72.5% | 58.4% | 1036 | 3769 |
| core.sql.executor | 87.7% | 76.8% | 832 | 6749 |
| core.db.tool | 67.4% | 56.3% | 699 | 2144 |
| core.gremlin | 56.3% | 72.1% | 677 | 1550 |
| core.metadata.schema | 84.8% | 69.9% | 660 | 4342 |
| core.storage.cache.local | 69.9% | 56.9% | 606 | 2015 |
| core.index | 81.6% | 70.9% | 581 | 3156 |
| core.serialization.serializer.record.binary | 82.7% | 80.3% | 580 | 3351 |
| core.metadata.security | 74.6% | 57.9% | 542 | 2138 |
| core.serialization.serializer.record.string | 64.1% | 59.5% | 519 | 1444 |

`core.gremlin` 56.3 % line reflects the Cucumber-feature-test
coverage that lives in the `embedded` module rather than `core` unit
tests (per Track 22a's findings); raising it requires moving tests
out-of-module or accepting the limitation.
`core.storage.impl.local` and `core.record.impl` are D4-bounded —
WAL / page-level / record-serialisation internals are intentionally
covered at the integration-test layer.

### Full Post-Step-15 Per-Package Coverage

Sorted by uncovered lines descending; produced by
`python3 .github/scripts/coverage-analyzer.py --coverage-dir .coverage/reports/youtrackdb-core`.

| Package | Line% | Branch% | Uncovered Lines | Total Lines |
|---------|-------|---------|-----------------|-------------|
| com.jetbrains.youtrackdb.internal.core.record.impl | 65.9% | 57.0% | 1283 | 3764 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local | 63.4% | 59.4% | 1140 | 3114 |
| com.jetbrains.youtrackdb.internal.core.db | 72.5% | 58.4% | 1036 | 3769 |
| com.jetbrains.youtrackdb.internal.core.sql.executor | 87.7% | 76.8% | 832 | 6749 |
| com.jetbrains.youtrackdb.internal.core.db.tool | 67.4% | 56.3% | 699 | 2144 |
| com.jetbrains.youtrackdb.internal.core.gremlin | 56.3% | 72.1% | 677 | 1550 |
| com.jetbrains.youtrackdb.internal.core.metadata.schema | 84.8% | 69.9% | 660 | 4342 |
| com.jetbrains.youtrackdb.internal.core.storage.cache.local | 69.9% | 56.9% | 606 | 2015 |
| com.jetbrains.youtrackdb.internal.core.index | 81.6% | 70.9% | 581 | 3156 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary | 82.7% | 80.3% | 580 | 3351 |
| com.jetbrains.youtrackdb.internal.core.metadata.security | 74.6% | 57.9% | 542 | 2138 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string | 64.1% | 59.5% | 519 | 1444 |
| com.jetbrains.youtrackdb.internal.core.tx | 73.1% | 69.6% | 403 | 1498 |
| com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3 | 86.0% | 73.0% | 358 | 2563 |
| com.jetbrains.youtrackdb.internal.core.storage.config | 68.2% | 51.4% | 304 | 957 |
| com.jetbrains.youtrackdb.internal.common.collection | 72.3% | 61.5% | 286 | 1034 |
| com.jetbrains.youtrackdb.internal.core.sql.executor.match | 92.3% | 78.2% | 265 | 3453 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas | 78.9% | 63.5% | 237 | 1123 |
| com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2 | 87.2% | 69.4% | 235 | 1838 |
| com.jetbrains.youtrackdb.internal.core.security.symmetrickey | 43.2% | 38.3% | 218 | 384 |
| com.jetbrains.youtrackdb.internal.common.console | 0.0% | 0.0% | 212 | 212 |
| com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree | 90.8% | 79.9% | 210 | 2285 |
| com.jetbrains.youtrackdb.internal.common.jnr | 24.1% | 21.4% | 208 | 274 |
| com.jetbrains.youtrackdb.internal.core.sql.filter | 78.6% | 66.0% | 206 | 963 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer | 76.4% | 67.6% | 200 | 846 |
| com.jetbrains.youtrackdb.internal.core.query | 53.5% | 40.0% | 180 | 387 |
| com.jetbrains.youtrackdb.internal.core | 71.8% | 59.4% | 171 | 606 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.graph | 82.3% | 67.5% | 170 | 963 |
| com.jetbrains.youtrackdb.internal.core.sql.operator | 83.7% | 76.5% | 154 | 947 |
| com.jetbrains.youtrackdb.api.gremlin.embedded | 3.8% | 1.8% | 152 | 158 |
| com.jetbrains.youtrackdb.internal.core.schedule | 86.2% | 75.3% | 151 | 1095 |
| com.jetbrains.youtrackdb.internal.core.security | 82.0% | 71.3% | 145 | 807 |
| com.jetbrains.youtrackdb.internal.core.storage.disk | 85.2% | 73.9% | 141 | 954 |
| com.jetbrains.youtrackdb.internal.core.command | 76.8% | 70.3% | 139 | 599 |
| com.jetbrains.youtrackdb.api.gremlin.tokens.schema | 0.0% | 100.0% | 138 | 138 |
| com.jetbrains.youtrackdb.internal.core.index.engine | 90.9% | 85.1% | 132 | 1451 |
| com.jetbrains.youtrackdb.internal.core.storage.collection.v2 | 91.8% | 76.5% | 127 | 1557 |
| com.jetbrains.youtrackdb.internal.common.directmemory | 71.1% | 61.1% | 123 | 425 |
| com.jetbrains.youtrackdb.internal.core.db.record | 92.0% | 80.1% | 109 | 1371 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal | 85.9% | 71.8% | 106 | 753 |
| com.jetbrains.youtrackdb.internal.common.concur.lock | 87.0% | 71.7% | 96 | 737 |
| com.jetbrains.youtrackdb.internal.common.serialization.types | 88.7% | 86.7% | 93 | 824 |
| com.jetbrains.youtrackdb.internal.core.fetch | 80.5% | 69.0% | 87 | 446 |
| com.jetbrains.youtrackdb.internal.common.profiler | 48.8% | 55.3% | 84 | 164 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations | 90.6% | 82.8% | 84 | 891 |
| com.jetbrains.youtrackdb.internal.core.record | 66.1% | 50.7% | 83 | 245 |
| com.jetbrains.youtrackdb.internal.core.sql | 86.6% | 82.3% | 82 | 613 |
| com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2 | 90.8% | 75.6% | 82 | 896 |
| com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v1 | 75.4% | 54.9% | 75 | 305 |
| com.jetbrains.youtrackdb.internal.core.index.engine.v1 | 87.1% | 85.4% | 68 | 526 |
| com.jetbrains.youtrackdb.internal.core.storage.ridbag | 90.0% | 67.9% | 67 | 668 |
| com.jetbrains.youtrackdb.internal.core.serialization | 74.0% | 62.5% | 64 | 246 |
| com.jetbrains.youtrackdb.internal.core.sql.query | 79.1% | 57.9% | 64 | 306 |
| com.jetbrains.youtrackdb.internal.core.config | 68.3% | 55.4% | 60 | 189 |
| com.jetbrains.youtrackdb.api | 28.8% | 50.0% | 52 | 73 |
| com.jetbrains.youtrackdb.internal.core.engine | 65.8% | 73.8% | 50 | 146 |
| com.jetbrains.youtrackdb.internal.common.util | 87.8% | 83.3% | 49 | 402 |
| com.jetbrains.youtrackdb.internal.common.log | 78.6% | 58.0% | 49 | 229 |
| com.jetbrains.youtrackdb.internal.core.metadata.sequence | 90.6% | 75.5% | 45 | 478 |
| com.jetbrains.youtrackdb.internal.common.collection.closabledictionary | 91.9% | 73.3% | 43 | 528 |
| com.jetbrains.youtrackdb.internal.core.storage.cache.chm | 92.8% | 78.2% | 42 | 585 |
| com.jetbrains.youtrackdb.internal.common.io | 88.0% | 79.6% | 42 | 351 |
| com.jetbrains.youtrackdb.internal.core.security.authenticator | 78.7% | 64.0% | 40 | 188 |
| com.jetbrains.youtrackdb.internal.core.storage.fs | 83.4% | 84.1% | 38 | 229 |
| com.jetbrains.youtrackdb.internal.common.serialization | 83.4% | 62.9% | 37 | 223 |
| com.jetbrains.youtrackdb.internal.core.metadata.function | 86.1% | 77.8% | 37 | 266 |
| com.jetbrains.youtrackdb.internal.core.gremlin.service | 85.1% | 78.8% | 34 | 228 |
| com.jetbrains.youtrackdb.internal.common.concur.resource | 84.5% | 79.2% | 34 | 220 |
| com.jetbrains.youtrackdb.internal.core.storage.cache | 90.2% | 77.5% | 33 | 337 |
| com.jetbrains.youtrackdb.internal.core.gremlin.io.graphson | 67.7% | 33.3% | 32 | 99 |
| com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog | 89.4% | 59.1% | 32 | 301 |
| com.jetbrains.youtrackdb.internal.core.cache | 85.2% | 72.4% | 31 | 210 |
| com.jetbrains.youtrackdb.internal.core.sql.method.misc | 92.2% | 88.0% | 28 | 360 |
| com.jetbrains.youtrackdb.internal.core.iterator | 82.4% | 87.8% | 28 | 159 |
| com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization | 84.1% | 71.3% | 27 | 170 |
| com.jetbrains.youtrackdb.internal.core.command.traverse | 92.2% | 82.3% | 26 | 335 |
| com.jetbrains.youtrackdb.internal.core.command.script | 94.9% | 84.9% | 25 | 494 |
| com.jetbrains.youtrackdb.api.config | 95.4% | 71.2% | 24 | 526 |
| com.jetbrains.youtrackdb.internal.core.util | 80.7% | 67.9% | 23 | 119 |
| com.jetbrains.youtrackdb.internal.core.sql.functions | 90.4% | 78.9% | 23 | 240 |
| com.jetbrains.youtrackdb.internal.common.parser | 95.6% | 90.8% | 22 | 504 |
| com.jetbrains.youtrackdb.internal.core.sql.executor.metadata | 92.8% | 79.5% | 22 | 304 |
| com.jetbrains.youtrackdb.internal.core.storage.collection | 97.4% | 81.7% | 22 | 847 |
| com.jetbrains.youtrackdb.internal.core.storage.memory | 93.5% | 83.3% | 20 | 306 |
| com.jetbrains.youtrackdb.internal.core.id | 94.6% | 85.6% | 19 | 349 |
| com.jetbrains.youtrackdb.internal.core.db.record.ridbag | 88.0% | 80.0% | 18 | 150 |
| com.jetbrains.youtrackdb.internal.common.exception | 70.0% | 100.0% | 18 | 60 |
| com.jetbrains.youtrackdb.internal.core.sql.method | 89.6% | 86.2% | 17 | 163 |
| com.jetbrains.youtrackdb.internal.core.engine.local | 73.8% | 55.6% | 17 | 65 |
| com.jetbrains.youtrackdb.internal.core.db.record.record | 90.4% | 79.2% | 16 | 166 |
| com.jetbrains.youtrackdb.api.gremlin.tokens | 30.4% | 0.0% | 16 | 23 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.misc | 96.2% | 87.5% | 16 | 417 |
| com.jetbrains.youtrackdb.internal.core.sql.operator.math | 91.1% | 90.2% | 16 | 179 |
| com.jetbrains.youtrackdb.internal.common.profiler.metrics | 95.5% | 75.8% | 16 | 352 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.deque | 89.1% | 78.8% | 15 | 138 |
| com.jetbrains.youtrackdb.internal.core.query.live | 88.7% | 82.4% | 15 | 133 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.coll | 96.4% | 89.8% | 14 | 385 |
| com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect | 81.9% | 81.5% | 13 | 72 |
| com.jetbrains.youtrackdb.internal.common.comparator | 90.2% | 83.3% | 12 | 123 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base | 95.8% | 66.5% | 12 | 283 |
| com.jetbrains.youtrackdb.internal.core.command.script.js | 47.8% | 66.7% | 12 | 23 |
| com.jetbrains.youtrackdb.internal.core.index.iterator | 92.1% | 91.7% | 11 | 140 |
| com.jetbrains.youtrackdb.internal.core.gql.parser | 95.5% | 89.7% | 11 | 245 |
| com.jetbrains.youtrackdb.internal.core.gremlin.jsr223 | 0.0% | 100.0% | 11 | 11 |
| com.jetbrains.youtrackdb.api.gremlin.embedded.schema | 0.0% | 100.0% | 10 | 10 |
| com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.filter | 69.7% | 75.0% | 10 | 33 |
| com.jetbrains.youtrackdb.internal.core.command.script.transformer | 84.4% | 94.7% | 10 | 64 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated | 96.0% | 73.6% | 9 | 224 |
| com.jetbrains.youtrackdb.internal.core.servlet | 35.7% | 33.3% | 9 | 14 |
| com.jetbrains.youtrackdb.internal.core.metadata | 82.7% | 75.0% | 9 | 52 |
| com.jetbrains.youtrackdb.internal.common.thread | 96.1% | 92.5% | 8 | 203 |
| com.jetbrains.youtrackdb.internal.common.stream | 90.8% | 91.7% | 8 | 87 |
| com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.map | 81.1% | 75.0% | 7 | 37 |
| com.jetbrains.youtrackdb.internal.common.profiler.monitoring | 94.4% | 90.6% | 7 | 124 |
| com.jetbrains.youtrackdb.internal.core.db.tool.importer | 96.1% | 86.8% | 7 | 180 |
| com.jetbrains.youtrackdb.internal.common.concur.collection | 88.1% | 66.7% | 7 | 59 |
| com.jetbrains.youtrackdb.internal.core.fetch.remote | 80.6% | 25.0% | 6 | 31 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.operationsfreezer | 93.8% | 85.7% | 6 | 97 |
| com.jetbrains.youtrackdb.internal.common.concur | 62.5% | 100.0% | 6 | 16 |
| com.jetbrains.youtrackdb.internal.core.sql.functions.math | 97.3% | 97.1% | 6 | 222 |
| com.jetbrains.youtrackdb.internal.core.db.config | 95.4% | 100.0% | 6 | 130 |
| com.jetbrains.youtrackdb.internal.core.gremlin.sqlcommand | 88.7% | 87.5% | 6 | 53 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.index | 98.9% | 93.6% | 5 | 471 |
| com.jetbrains.youtrackdb.internal.core.exception | 99.0% | 87.1% | 4 | 389 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer.stream | 82.6% | 100.0% | 4 | 23 |
| com.jetbrains.youtrackdb.internal.core.storage.index.nkbtree.normalizers | 96.6% | 87.5% | 4 | 119 |
| com.jetbrains.youtrackdb.internal.core.sql.executor.resultset | 99.5% | 91.7% | 3 | 616 |
| com.jetbrains.youtrackdb.internal.core.metadata.schema.clusterselection | 93.9% | 75.0% | 3 | 49 |
| com.jetbrains.youtrackdb.internal.core.gremlin.executor.transformer | 0.0% | 100.0% | 2 | 2 |
| com.jetbrains.youtrackdb.internal.core.storage | 98.1% | 87.5% | 2 | 108 |
| com.jetbrains.youtrackdb.internal.common.types | 97.7% | 90.9% | 2 | 86 |
| com.jetbrains.youtrackdb.internal.common.listener | 86.7% | 66.7% | 2 | 15 |
| com.jetbrains.youtrackdb.internal.core.conflict | 90.0% | 100.0% | 2 | 20 |
| com.jetbrains.youtrackdb.internal.core.storage.cache.local.aoc | 0.0% | 100.0% | 2 | 2 |
| com.jetbrains.youtrackdb.internal.common.hash | 99.1% | 100.0% | 1 | 114 |
| com.jetbrains.youtrackdb.internal.core.gql.executor | 98.8% | 96.9% | 1 | 86 |
| com.jetbrains.youtrackdb.internal.core.metadata.schema.schema | 98.3% | 100.0% | 1 | 58 |
| com.jetbrains.youtrackdb.internal.core.storage.cache.chm.readbuffer | 99.2% | 78.4% | 1 | 128 |
| com.jetbrains.youtrackdb.internal.core.gql.planner | 96.0% | 83.3% | 1 | 25 |
| com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common | 97.1% | 100.0% | 1 | 34 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl | 99.3% | 100.0% | 1 | 153 |
| com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary | 98.4% | 100.0% | 1 | 61 |
