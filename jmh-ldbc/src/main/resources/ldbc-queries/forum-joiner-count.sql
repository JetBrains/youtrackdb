/* Forum joiner-count: count-only bothE pre-filter benchmark.

   Counts how many members joined a Forum since a given date, via
   bothE('HAS_MEMBER') with a joinDate filter. This is a clean measurement
   of the pre-filter benefit because there is no downstream work:
     - no .inV() → no Person vertex loads
     - no ORDER BY → no materialization of surviving edges for sorting
     - no projection of edge properties → no per-edge attribute reads
   The only cost is the traversal + filter itself, so the ratio
   "edges loaded without pre-filter / edges loaded with pre-filter" maps
   directly to the speedup.

   Uses the same top-100 hub Forum pool as forum-recent-joiners, with a
   narrower (99th-percentile) lower-bound date so only ~1% of edges pass
   — making the savings from skipping index-filtered-out edge loads
   maximally visible.

   Semantically sensible query: "how many people joined this Forum since
   date X" is a natural admin-dashboard / activity-report metric.

   Parameters:
     :forumId  — popular Forum.id (top-100 by HAS_MEMBER bag size)
     :minDate  — narrow lower bound (99th percentile of curated dates) */
MATCH {class: Forum, as: f, where: (id = :forumId)}
  .bothE('HAS_MEMBER'){as: m, where: (joinDate >= :minDate)}
RETURN count(*) as joinerCount
