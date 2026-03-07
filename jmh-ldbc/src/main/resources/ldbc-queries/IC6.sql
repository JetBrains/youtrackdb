/* IC6: Tag co-occurrence.
   Find Tags that co-occur with a given Tag on friends' Posts. */
SELECT tagName, count(postId) as postCount
FROM (
  MATCH {class: Person, as: start, where: (id = :personId)}
      .out('KNOWS'){while: ($depth < 2), as: person,
        where: (@rid <> $matched.start.@rid)}
      .in('HAS_CREATOR'){class: Post, as: post}
      .out('HAS_TAG'){as: tag, where: (name <> :tagName)},
    {as: post}.out('HAS_TAG'){where: (name = :tagName)}
  RETURN DISTINCT post.id as postId, tag.name as tagName
)
GROUP BY tagName
ORDER BY postCount DESC, tagName COLLATE default
LIMIT :limit
