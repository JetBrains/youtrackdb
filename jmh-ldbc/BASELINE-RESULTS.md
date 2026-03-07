# LDBC JMH Benchmark Baseline Results

**Date**: 2026-03-07
**Server**: Hetzner CCX33 (8 dedicated AMD vCPUs, 32 GB RAM, 240 GB NVMe)
**Location**: Falkenstein (fsn1)
**OS**: Ubuntu 24.04
**JDK**: OpenJDK 21.0.10+7-Ubuntu-124.04
**Dataset**: LDBC SNB SF 0.1 (~1.8M records)
**JMH**: 1.37, 3 warmup iterations (5s), 5 measurement iterations (10s), 1 fork

> **Note**: These results were collected with the **original** (pre-optimization)
> schema and queries. The `@Threads(8)` annotation was used for the multi-threaded
> suite. See [QUERY-ENGINE-OPTIMIZATIONS.md](QUERY-ENGINE-OPTIMIZATIONS.md) for
> engine-level improvements identified from EXPLAIN/PROFILE analysis.

## Multi-Threaded Suite (8 threads)

| Query | Description | ops/s | ± error | Category |
|-------|-------------|------:|--------:|----------|
| is5_messageCreator | Creator of a message | 31,444 | 8,210 | Fast |
| is4_messageContent | Content of a message | 29,125 | 815 | Fast |
| is1_personProfile | Person profile lookup | 24,767 | 430 | Fast |
| is3_personFriends | Friends of a person | 17,602 | 3,351 | Fast |
| is6_messageForum | Forum of a message | 16,753 | 303 | Fast |
| is7_messageReplies | Replies to a message | 11,457 | 79 | Fast |
| ic13_shortestPath | Shortest path (2 persons) | 7,757 | 37 | Fast |
| ic8_recentReplies | Recent replies to messages | 3,463 | 30 | Fast |
| is2_personPosts | Recent messages of person | 2,052 | 18 | Good |
| ic11_jobReferral | Friends/FoF working in country | 344 | 34 | Good |
| ic2_recentFriendMessages | Recent messages by friends | 198 | 16 | Good |
| ic4_newTopics | New tags on friends' posts | 128 | 8 | Good |
| ic1_transitiveFriends | 3-hop friends by name | 92 | 7 | Moderate |
| ic7_recentLikers | Recent likers of messages | 58 | 1 | Moderate |
| ic12_expertSearch | Friends' comments in tag class | 52 | 3 | Moderate |
| ic6_tagCoOccurrence | Tag co-occurrence on posts | 4.8 | 2.2 | Slow |
| ic9_recentFofMessages | Recent FoF messages | 4.2 | 4.3 | Slow |
| ic10_friendRecommendation | FoF recommendation by interests | 2.5 | 1.6 | Slow |
| ic3_friendsInCountries | Friends posting in countries | 1.9 | 2.4 | Slow |

> IC5 (newGroups) was not captured in MT results due to the extremely long
> single-thread execution time consuming most of the benchmark run.

## Single-Threaded Suite

| Query | Description | ops/s | ± error | Category |
|-------|-------------|------:|--------:|----------|
| is5_messageCreator | Creator of a message | 8,220 | 169 | Fast |
| is4_messageContent | Content of a message | 7,186 | 134 | Fast |
| is1_personProfile | Person profile lookup | 6,431 | 76 | Fast |
| is6_messageForum | Forum of a message | 4,052 | 64 | Fast |
| is3_personFriends | Friends of a person | 3,923 | 53 | Fast |
| is7_messageReplies | Replies to a message | 2,768 | 77 | Fast |
| ic13_shortestPath | Shortest path (2 persons) | 1,793 | 13 | Fast |
| ic8_recentReplies | Recent replies to messages | 730 | 22 | Fast |
| is2_personPosts | Recent messages of person | 460 | 20 | Good |
| ic11_jobReferral | Friends/FoF working in country | 84 | 3 | Good |
| ic2_recentFriendMessages | Recent messages by friends | 41 | 1 | Good |
| ic4_newTopics | New tags on friends' posts | 26 | 3 | Good |
| ic1_transitiveFriends | 3-hop friends by name | 19 | 1 | Moderate |
| ic7_recentLikers | Recent likers of messages | 13 | 6 | Moderate |
| ic12_expertSearch | Friends' comments in tag class | 11 | 2 | Moderate |
| ic6_tagCoOccurrence | Tag co-occurrence on posts | 1.1 | 0.8 | Slow |
| ic9_recentFofMessages | Recent FoF messages | 1.0 | 0.5 | Slow |
| ic10_friendRecommendation | FoF recommendation by interests | 0.65 | 0.64 | Slow |
| ic3_friendsInCountries | Friends posting in countries | 0.35 | 0.67 | Slow |
| ic5_newGroups | Forums joined by FoF | 0.02 | 0.11 | Pathological |

## Observations

### Performance Tiers

- **Fast (>1000 ops/s ST)**: IS1–IS7 and IC13 — simple lookups and short
  traversals. All use index entry points and O(1) edge traversals.
- **Good (20–500 ops/s ST)**: IC2, IC4, IC11 — moderate fan-out traversals
  with filtering.
- **Moderate (10–20 ops/s ST)**: IC1, IC7, IC12 — multi-hop traversals or
  correlated subqueries.
- **Slow (<2 ops/s ST)**: IC3, IC6, IC9, IC10 — large intermediate result
  sets or expensive per-row subqueries.
- **Pathological (<0.1 ops/s ST)**: IC5 — per-forum full post scan in
  correlated subquery.

### Scaling (ST → MT)

| Tier | Typical MT/ST ratio | Limiting factor |
|------|-------------------:|-----------------|
| Fast | 3.5–4.0× | Good parallelism, minimal contention |
| Good | 4.5–5.0× | Good parallelism |
| Moderate | 4.5–5.0× | Some lock contention on storage |
| Slow | 3–5× | CPU-bound computation, GC pressure |

### Bottleneck Analysis

| Query | Bottleneck | Engine Optimization Needed |
|-------|-----------|--------------------------|
| IC5 | `expand(out('CONTAINER_OF'))` scans ALL forum posts | #3 Predicate push-down |
| IC3 | Two correlated subqueries per friend, each scanning all messages | #1 Conditional aggregation, #2 Materialization |
| IC10 | Two correlated subqueries per FoF, each scanning all posts + tag sets | #1 Conditional aggregation, #2 Materialization |
| IC6 | FoF expansion × posts × tags (large Cartesian intermediate) | #4 Cost-based reordering |
| IC9 | FoF expansion + all messages (no date pushdown to index) | #9 Mid-traversal index filtering |

### Raw JSON

Full JMH results in JSON format: [baseline-results-ccx33.json](baseline-results-ccx33.json)
