/* IC7: Recent likers.
   Find most recent likes on a Person's Messages.
   Carries like metadata from the initial MATCH traversal and picks the latest
   per liker via ORDER BY + first(), eliminating the per-liker correlated subquery. */
SELECT personId, firstName, lastName,
  first(likeCreationDate) as likeCreationDate,
  first(messageId) as messageId,
  first(messageContent) as messageContent,
  first(messageCreationDate) as messageCreationDate,
  isNew
FROM (
  SELECT liker.id as personId,
    liker.firstName as firstName,
    liker.lastName as lastName,
    likeEdge.creationDate as likeCreationDate,
    message.id as messageId,
    coalesce(message.imageFile, message.content) as messageContent,
    message.creationDate as messageCreationDate,
    ifnull(knowsStart, true, false) as isNew
  FROM (
    MATCH {class: Person, as: startPerson, where: (id = :personId)}
      .in('HAS_CREATOR'){as: message}
      .inE('LIKES'){as: likeEdge}
      .outV(){as: liker}
      .out('KNOWS'){as: knowsStart, where: (@rid = $matched.startPerson.@rid), optional: true}
    RETURN liker, message, likeEdge, knowsStart
  )
  ORDER BY likeCreationDate DESC, messageId ASC
)
GROUP BY personId, firstName, lastName, isNew
ORDER BY likeCreationDate DESC, personId ASC
LIMIT :limit
