/* IC5: New groups.
   Find Forums joined by friends/friends-of-friends after a given date,
   counting Posts by those friends.

   Reverse single-chain traversal: person -> Posts (via HAS_CREATOR) ->
   Forum (via CONTAINER_OF) -> membership check (back-reference to person).
   The single chain forces the scheduler to do person->posts FIRST (cheap:
   ~100 posts per person), then check forum membership with a pre-filtered
   O(1) EdgeRidLookup on the back-reference. This avoids the forward plan's
   forum->CONTAINER_OF fan-out (~539 posts per forum x 15 forums/person). */
SELECT forumTitle, forumId, count(post) as postCount
FROM (
  SELECT DISTINCT forum.title as forumTitle,
    forum.id as forumId,
    post.@rid as post
  FROM (
    MATCH {class: Person, as: start, where: (id = :personId)}
      .out('KNOWS'){while: ($depth < 2), as: person,
        where: (@rid <> $matched.start.@rid)}
      .in('HAS_CREATOR'){class: Post, as: post}
      .in('CONTAINER_OF'){class: Forum, as: forum}
      .outE('HAS_MEMBER'){as: membership, where: (joinDate >= :minDate)}
      .inV(){as: personCheck, where: (@rid = $matched.person.@rid)}
    RETURN forum, post
  )
)
GROUP BY forumTitle, forumId
ORDER BY postCount DESC, forumId ASC
LIMIT :limit
