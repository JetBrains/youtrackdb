/* IC2: Recent messages by friends.
   Find most recent Messages from a Person's friends before a given date. */
MATCH {class: Person, as: p, where: (id = :personId)}
  .out('KNOWS'){as: friend}
  .in('HAS_CREATOR'){as: msg, where: (creationDate < :maxDate)}
RETURN friend.id as personId, friend.firstName as firstName, friend.lastName as lastName,
  msg.id as messageId,
  coalesce(msg.imageFile, msg.content) as messageContent,
  msg.creationDate as messageCreationDate
ORDER BY messageCreationDate DESC, messageId ASC
LIMIT :limit
