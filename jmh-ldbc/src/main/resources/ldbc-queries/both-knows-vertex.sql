/* Both-KNOWS (vertex): Named friends of a person via vertex-to-vertex both().

   Given a Person and a firstName, find all persons connected via KNOWS in
   EITHER direction whose firstName matches. Unlike the bothE variant (which
   traverses edge records), this uses the vertex-to-vertex both('KNOWS') step,
   which — after YTDB-646 — resolves to VertexEntityImpl.getVertices(BOTH) and
   builds a single PreFilterableChainedIterable spanning the out_KNOWS and
   in_KNOWS link bags.

   In the LDBC dataset KNOWS is stored bidirectionally, so a Person's out_KNOWS
   and in_KNOWS bags are BOTH populated — this is the two-direction shape that
   actually exercises the chained iterable (single-direction shapes collapse to
   one bag and take the pre-existing single-bag path). KNOWS is a symmetric edge
   (out=Person, in=Person), so the planner infers the target alias class Person;
   with the Person.firstName index the MATCH engine intersects BOTH link bags
   against the index RID set in memory before loading any neighbor vertex.

   This benchmark exists specifically so a regression in the chained vertex
   path (e.g. pre-filter delegation broken across the two sub-iterables, or the
   BG1/PF2 empty-direction fallback reintroduced) becomes measurable rather than
   silently degrading to unfiltered iteration.

   Parameters:
     :personId  — LDBC Person.id (the hub whose KNOWS neighbors we traverse)
     :firstName — target-vertex firstName filter (indexed)
     :limit     — max rows returned */
MATCH {class: Person, as: p, where: (id = :personId)}
  .both('KNOWS'){as: friend, where: (firstName = :firstName)}
RETURN friend.id as personId, friend.firstName as firstName,
  friend.lastName as lastName
ORDER BY personId ASC
LIMIT :limit
