/* IC3: Friends in countries.
   Find friends/friends-of-friends who posted in both given countries
   within a time period.
   Uses conditional aggregation — sum(if(...)) — to count messages for
   both countries in a single scan instead of two separate subqueries. */
SELECT personId, firstName, lastName,
  $counts[0].xCount as xCount, $counts[0].yCount as yCount,
  ($counts[0].xCount + $counts[0].yCount) as totalCount
FROM (
  MATCH {class: Person, as: start, where: (id = :personId)}
    .out('KNOWS'){while: ($depth < 2), as: person,
      where: (@rid <> $matched.start.@rid)}
    .out('IS_LOCATED_IN'){as: personCity}
    .out('IS_PART_OF'){as: personCountry,
      where: (name NOT IN [:countryX, :countryY])}
  RETURN person.id as personId, person.firstName as firstName,
    person.lastName as lastName, person as personVertex
)
LET $counts = (
  SELECT
    sum(if(out('IS_LOCATED_IN').name CONTAINS :countryX, 1, 0)) as xCount,
    sum(if(out('IS_LOCATED_IN').name CONTAINS :countryY, 1, 0)) as yCount
  FROM (
    SELECT expand(in('HAS_CREATOR')) FROM Person
    WHERE @rid = $parent.$current.personVertex
  ) WHERE creationDate >= :startDate AND creationDate < :endDate
)
WHERE $counts[0].xCount > 0 AND $counts[0].yCount > 0
ORDER BY totalCount DESC, personId ASC
LIMIT :limit
