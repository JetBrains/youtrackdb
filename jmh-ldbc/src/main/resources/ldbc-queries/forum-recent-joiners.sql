/* Forum recent joiners: hub-shape bothE pre-filter benchmark.

   Targets Forums with thousands of HAS_MEMBER edges so the YTDB-646
   index-assisted pre-filter on HAS_MEMBER.joinDate has a meaningfully
   large link bag to skip loads from. The complementary `both-e-knows`
   benchmark covers the small-bag case (Person→KNOWS averages ~100 edges,
   where pre-filter overhead matches savings and no speedup is measurable).

   Parameters:
     :forumId  — popular Forum.id (top-100 by HAS_MEMBER bag size)
     :minDate  — lower bound for HAS_MEMBER.joinDate (inclusive, selective)
     :limit    — max rows returned */
MATCH {class: Forum, as: f, where: (id = :forumId)}
  .bothE(HAS_MEMBER){as: m, where: (joinDate >= :minDate)}
  .inV(){as: person}
RETURN person.id as personId, m.joinDate as joined
ORDER BY joined DESC, personId ASC
LIMIT :limit
