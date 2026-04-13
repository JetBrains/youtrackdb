/* IC3: Friends in countries.
   Find friends/friends-of-friends who posted in both given countries
   within a time period.
   Uses branching MATCH: one branch filters person's country, another
   traverses messages — replaces correlated LET subquery, eliminates
   ~3500 per-friend SQL execution cycles. */
SELECT personId, firstName, lastName, xCount, yCount,
  (xCount + yCount) as totalCount
FROM (
  SELECT personId, firstName, lastName,
    sum(if(msgCountry = :countryX, 1, 0)) as xCount,
    sum(if(msgCountry = :countryY, 1, 0)) as yCount
  FROM (
    MATCH {class: Person, as: start, where: (id = :personId)}
      .out('KNOWS'){while: ($depth < 2), as: person,
        where: (@rid <> $matched.start.@rid)},
      {as: person}.out('IS_LOCATED_IN').out('IS_PART_OF')
        {where: (name NOT IN [:countryX, :countryY])},
      {as: person}.in('HAS_CREATOR'){as: message,
        where: (creationDate >= :startDate AND creationDate < :endDate)}
      .out('IS_LOCATED_IN'){as: msgCountry,
        where: (name IN [:countryX, :countryY])}
    RETURN person.id as personId, person.firstName as firstName,
      person.lastName as lastName, msgCountry.name as msgCountry
  )
  GROUP BY personId, firstName, lastName
)
WHERE xCount > 0 AND yCount > 0
ORDER BY totalCount DESC, personId ASC
LIMIT :limit
