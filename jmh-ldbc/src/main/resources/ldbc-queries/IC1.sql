/* IC1: Transitive friends with a certain name.
   Find Persons with a given first name connected within 3 hops via KNOWS. */
SELECT personId, lastName, distance, birthday, creationDate, gender,
  browserUsed, locationIP, emails, languages, cityName,
  $universities as universities, $companies as companies
FROM (
  SELECT personId, lastName, min(distance) as distance, birthday, creationDate, gender,
    browserUsed, locationIP, emails, languages, cityName, friendVertex
  FROM (
    MATCH {class: Person, as: start, where: (id = :personId)}
      .out('KNOWS'){while: ($depth < 3), as: friend,
        where: (firstName = :firstName AND @rid <> $matched.start.@rid),
        depthAlias: dist}
      .out('IS_LOCATED_IN'){as: city}
    RETURN friend.id as personId, friend.lastName as lastName,
      dist as distance,
      friend.birthday as birthday, friend.creationDate as creationDate,
      friend.gender as gender,
      friend.browserUsed as browserUsed, friend.locationIP as locationIP,
      friend.emails as emails, friend.languages as languages,
      city.name as cityName,
      friend as friendVertex
  )
  GROUP BY personId, lastName, birthday, creationDate, gender,
    browserUsed, locationIP, emails, languages, cityName, friendVertex
)
LET $universities = (
  SELECT classYear, inV().name as uniName,
    first(inV().out('IS_LOCATED_IN').name) as uniCityName
  FROM (
    SELECT expand(outE('STUDY_AT')) FROM Person WHERE @rid = $parent.$current.friendVertex
  )
),
$companies = (
  SELECT workFrom as workFromYear, inV().name as compName,
    first(inV().out('IS_LOCATED_IN').name) as compCountryName
  FROM (
    SELECT expand(outE('WORK_AT')) FROM Person WHERE @rid = $parent.$current.friendVertex
  )
)
ORDER BY distance ASC, lastName ASC, personId ASC
LIMIT :limit
