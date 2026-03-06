/* IC9: Recent messages by friends or friends of friends.
   Find most recent Messages created by friends/FoF before a given date. */
SELECT personId, firstName, lastName,
  messageId, messageContent, messageCreationDate
FROM (
  MATCH {class: Person, as: start, where: (id = :personId)}
    .out('KNOWS'){while: ($depth < 2), as: person,
      where: (@rid <> $matched.start.@rid)}
    .in('HAS_CREATOR'){as: msg, where: (creationDate < :maxDate)}
  RETURN DISTINCT
    person.id as personId,
    person.firstName as firstName,
    person.lastName as lastName,
    msg.id as messageId,
    coalesce(msg.imageFile, msg.content) as messageContent,
    msg.creationDate as messageCreationDate
)
ORDER BY messageCreationDate DESC, messageId ASC
LIMIT :limit
