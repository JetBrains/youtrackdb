SELECT personId, firstName, lastName,
  $xCount[0].cnt as xCount, $yCount[0].cnt as yCount,
  ($xCount[0].cnt + $yCount[0].cnt) as totalCount
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
LET $xCount = (
  SELECT count(*) as cnt FROM (
    SELECT expand(in('HAS_CREATOR')) FROM Person
    WHERE @rid = $parent.$current.personVertex
  ) WHERE creationDate >= :startDate AND creationDate < :endDate
    AND out('IS_LOCATED_IN').name CONTAINS :countryX
),
$yCount = (
  SELECT count(*) as cnt FROM (
    SELECT expand(in('HAS_CREATOR')) FROM Person
    WHERE @rid = $parent.$current.personVertex
  ) WHERE creationDate >= :startDate AND creationDate < :endDate
    AND out('IS_LOCATED_IN').name CONTAINS :countryY
)
WHERE $xCount[0].cnt > 0 AND $yCount[0].cnt > 0
ORDER BY totalCount DESC, personId ASC
LIMIT :limit
