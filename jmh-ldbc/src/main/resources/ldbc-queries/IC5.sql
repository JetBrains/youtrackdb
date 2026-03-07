/* IC5: New groups.
   Find Forums joined by friends/friends-of-friends after a given date,
   counting Posts by those friends. */
SELECT forumTitle, forumId, sum(postCount) as postCount
FROM (
  SELECT forumTitle, forumId,
    $postCount[0].cnt as postCount
  FROM (
    SELECT DISTINCT person as personVertex,
      forum as forumVertex,
      forum.id as forumId,
      forum.title as forumTitle
    FROM (
      MATCH {class: Person, as: start, where: (id = :personId)}
        .out('KNOWS'){while: ($depth < 2), as: person,
          where: (@rid <> $matched.start.@rid)}
        .inE('HAS_MEMBER'){as: membership, where: (joinDate >= :minDate)}
        .outV(){class: Forum, as: forum}
      RETURN person, forum
    )
  )
  LET $postCount = (
    SELECT count(*) as cnt
    FROM (
      SELECT expand(out('CONTAINER_OF')) FROM Forum
      WHERE @rid = $parent.$current.forumVertex
    )
    WHERE out('HAS_CREATOR').@rid = $parent.$current.personVertex
  )
)
GROUP BY forumTitle, forumId
ORDER BY postCount DESC, forumId ASC
LIMIT :limit
