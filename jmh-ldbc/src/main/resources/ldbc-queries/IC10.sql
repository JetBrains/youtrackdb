/* IC10: Friend recommendation.
   Find friends-of-friends born in a date range, score by common interests.
   Uses conditional aggregation — sum(if(...)) — to compute the positive
   and negative tag-match scores in a single scan instead of two subqueries. */
SELECT personId, firstName, lastName,
  $scores[0].commonInterestScore as commonInterestScore,
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
LET $scores = (
  SELECT
    sum(if(set(out('HAS_TAG').name) CONTAINSANY $parent.$current.startVertex.out('HAS_INTEREST').name, 1, -1))
    as commonInterestScore
  FROM (
    SELECT expand(in('HAS_CREATOR')) FROM Person
    WHERE @rid = $parent.$current.fofVertex
  ) WHERE @class = 'Post'
)
WHERE (
  (:wrap = true AND (birthday.format('MMdd', 'UTC') >= :startMd OR birthday.format('MMdd', 'UTC') < :endMd))
  OR (:wrap = false AND birthday.format('MMdd', 'UTC') >= :startMd AND birthday.format('MMdd', 'UTC') < :endMd)
)
ORDER BY commonInterestScore DESC, personId ASC
LIMIT :limit
