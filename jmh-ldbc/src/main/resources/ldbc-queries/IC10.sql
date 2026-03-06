SELECT personId, firstName, lastName,
  ($posScore[0].cnt - $negScore[0].cnt) as commonInterestScore,
  gender, birthday, cityName
FROM (
  MATCH {class: Person, as: start, where: (id = :personId)}
    .out('KNOWS'){as: directFriend}
    .out('KNOWS'){as: fof,
      where: ($currentMatch <> $matched.start
        AND $currentMatch NOT IN $matched.start.out('KNOWS')
      )}
    .out('IS_LOCATED_IN'){as: city}
  RETURN DISTINCT fof.id as personId, fof.firstName as firstName,
    fof.lastName as lastName, fof.gender as gender,
    fof.birthday as birthday,
    city.name as cityName,
    fof as fofVertex,
    start as startVertex
)
LET $posScore = (
  SELECT count(*) as cnt FROM (
    SELECT expand(in('HAS_CREATOR')) FROM Person
    WHERE @rid = $parent.$current.fofVertex
  ) WHERE @class = 'Post'
    AND set(out('HAS_TAG').name) CONTAINSANY $parent.$current.startVertex.out('HAS_INTEREST').name
),
$negScore = (
  SELECT count(*) as cnt FROM (
    SELECT expand(in('HAS_CREATOR')) FROM Person
    WHERE @rid = $parent.$current.fofVertex
  ) WHERE @class = 'Post'
    AND NOT (set(out('HAS_TAG').name) CONTAINSANY $parent.$current.startVertex.out('HAS_INTEREST').name)
)
WHERE (
  (:wrap = true AND (birthday.format('MMdd', 'UTC') >= :startMd OR birthday.format('MMdd', 'UTC') < :endMd))
  OR (:wrap = false AND birthday.format('MMdd', 'UTC') >= :startMd AND birthday.format('MMdd', 'UTC') < :endMd)
)
ORDER BY commonInterestScore DESC, personId ASC
LIMIT :limit
