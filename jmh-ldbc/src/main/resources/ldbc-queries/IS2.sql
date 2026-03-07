/* IS2: Recent messages of a person.
   Given a Person, retrieve their last 10 messages with original post info. */
MATCH {class: Person, as: p, where: (id = :personId)}
  .in('HAS_CREATOR'){as: message}
  .out('REPLY_OF'){while: ($currentMatch.@class = 'Comment'),
                       where: (@class = 'Post'), as: originalPost}
  .out('HAS_CREATOR'){as: author}
RETURN message.id as messageId,
  coalesce(message.imageFile, message.content) as messageContent,
  message.creationDate as messageCreationDate,
  originalPost.id as originalPostId,
  author.id as originalPostAuthorId,
  author.firstName as originalPostAuthorFirstName,
  author.lastName as originalPostAuthorLastName
ORDER BY messageCreationDate DESC
LIMIT :limit
