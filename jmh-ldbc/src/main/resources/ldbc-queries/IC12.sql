MATCH {class: Person, as: p, where: (id = :personId)}
  .out('KNOWS'){as: friend}
  .in('HAS_CREATOR'){class: Comment, as: comment}
  .out('REPLY_OF'){class: Post, as: post}
  .out('HAS_TAG'){as: tag}
  .out('HAS_TYPE'){as: directClass}
  .out('IS_SUBCLASS_OF'){while: (true), where: (name = :tagClassName),
    as: matchedClass}
RETURN friend.id as personId, friend.firstName as firstName,
  friend.lastName as lastName,
  set(tag.name) as tagNames, count(*) as replyCount
GROUP BY friend.id, friend.firstName, friend.lastName
ORDER BY replyCount DESC, personId ASC
LIMIT :limit
