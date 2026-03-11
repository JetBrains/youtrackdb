/* IC5: New groups.
   Find Forums joined by friends/friends-of-friends after a given date,
   counting Posts by those friends.

   Extends the MATCH pattern to traverse Forum -> CONTAINER_OF -> Post ->
   HAS_CREATOR -> Person (matching the friend), avoiding the correlated
   LET subquery that caused an N+1 scan over all forum posts. */
SELECT forumTitle, forumId, count(post) as postCount
FROM (
  SELECT DISTINCT forum.title as forumTitle,
    forum.id as forumId,
    post.@rid as post
  FROM (
    MATCH {class: Person, as: start, where: (id = :personId)}
      .out('KNOWS'){while: ($depth < 2), as: person,
        where: (@rid <> $matched.start.@rid)}
      .inE('HAS_MEMBER'){as: membership, where: (joinDate >= :minDate)}
      .outV(){class: Forum, as: forum}
      .out('CONTAINER_OF'){as: post}
      .out('HAS_CREATOR'){as: creator,
        where: (@rid = $matched.person.@rid)}
    RETURN forum, post
  )
)
GROUP BY forumTitle, forumId
ORDER BY postCount DESC, forumId ASC
LIMIT :limit
