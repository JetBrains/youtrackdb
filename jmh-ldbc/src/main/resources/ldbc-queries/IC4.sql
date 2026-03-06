/* IC4: New topics.
   Find Tags attached to friends' Posts in a time interval that were never
   used before. */
MATCH {class: Person, as: p, where: (id = :personId)}
  .out('KNOWS'){as: friend}
  .in('HAS_CREATOR'){class: Post, as: newPost,
    where: (creationDate >= :startDate AND creationDate < :endDate)}
  .out('HAS_TAG'){as: tag},
  NOT {as: friend}
    .in('HAS_CREATOR'){class: Post, as: oldPost,
      where: (creationDate < :startDate)}
    .out('HAS_TAG'){as: tag}
RETURN tag.name as tagName, count(*) as postCount
GROUP BY tag.name
ORDER BY postCount DESC, tagName ASC
LIMIT :limit
