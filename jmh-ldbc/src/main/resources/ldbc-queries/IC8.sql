MATCH {class: Person, as: p, where: (id = :personId)}
  .in('HAS_CREATOR'){as: message}
  .in('REPLY_OF'){as: comment}
  .out('HAS_CREATOR'){as: creator}
RETURN creator.id as personId, creator.firstName as firstName, creator.lastName as lastName,
  comment.creationDate as commentCreationDate,
  comment.id as commentId,
  coalesce(comment.imageFile, comment.content) as commentContent
ORDER BY commentCreationDate DESC, commentId ASC
LIMIT :limit
