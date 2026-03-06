MATCH {class: Message, as: msg, where: (id = :messageId)}
  .out('HAS_CREATOR'){as: author},
  {as: msg}
  .in('REPLY_OF'){as: reply}
  .out('HAS_CREATOR'){as: replyAuthor}
  .out('KNOWS'){as: knowsCheck, where: (@rid = $matched.author.@rid), optional: true}
RETURN reply.id as commentId,
  coalesce(reply.imageFile, reply.content) as commentContent,
  reply.creationDate as commentCreationDate,
  replyAuthor.id as replyAuthorId,
  replyAuthor.firstName as replyAuthorFirstName,
  replyAuthor.lastName as replyAuthorLastName,
  ifnull(knowsCheck, false, true) as replyAuthorKnowsOriginalMessageAuthor
ORDER BY commentCreationDate DESC, replyAuthorId ASC