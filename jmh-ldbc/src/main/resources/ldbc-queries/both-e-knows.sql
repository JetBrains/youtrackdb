/* BothE-KNOWS: Recent connections of a person (bidirectional).

   Given a Person and a minimum date, find all persons connected to them via
   KNOWS edges (in either direction) where the connection was formed on or after
   that date. Reports each KNOWS edge once regardless of which endpoint initiated
   the connection.

   In the LDBC dataset KNOWS is stored bidirectionally (A→B and B→A edges both
   exist with the same creationDate). Using bothE traverses both the out_KNOWS
   and in_KNOWS link bags in a single step. With the KNOWS.creationDate index,
   the MATCH planner builds a RID set from the index and intersects it against
   both link bags via PreFilterableChainedIterable — avoiding loading edge records
   that fall outside the date window.

   A production query would add DISTINCT to deduplicate the two edge directions;
   this benchmark intentionally omits it to maximise the number of edges touched
   and make the pre-filter benefit easier to measure.

   Parameters:
     :personId  — LDBC Person.id
     :minDate   — lower bound for KNOWS.creationDate (inclusive)
     :limit     — max rows returned */
MATCH {class: Person, as: p, where: (id = :personId)}
  .bothE('KNOWS'){as: k, where: (creationDate >= :minDate)}
  .inV(){as: reachable}
RETURN reachable.id as personId, reachable.firstName as firstName,
  reachable.lastName as lastName, k.creationDate as since
ORDER BY since DESC, personId ASC
LIMIT :limit
