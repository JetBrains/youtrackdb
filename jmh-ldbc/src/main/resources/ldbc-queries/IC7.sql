SELECT personId, firstName, lastName,
  $latest[0].likeCreationDate as likeCreationDate,
  $latest[0].messageId as messageId,
  $latest[0].messageContent as messageContent,
  $latest[0].messageCreationDate as messageCreationDate,
  isNew
FROM (
  SELECT DISTINCT liker as likerVertex,
    liker.id as personId,
    liker.firstName as firstName,
    liker.lastName as lastName,
    startPerson as startPerson,
    ifnull(knowsStart, true, false) as isNew
  FROM (
    MATCH {class: Person, as: startPerson, where: (id = :personId)}
      .in('HAS_CREATOR'){as: message}
      .inE('LIKES'){as: likeEdge}
      .outV(){as: liker}
      .out('KNOWS'){as: knowsStart, where: (@rid = $matched.startPerson.@rid), optional: true}
    RETURN startPerson, liker, knowsStart
  )
)
LET $latest = (
  SELECT creationDate as likeCreationDate,
    inV().id as messageId,
    coalesce(inV().imageFile, inV().content) as messageContent,
    inV().creationDate as messageCreationDate
  FROM (
    SELECT expand(outE('LIKES')) FROM Person
    WHERE @rid = $parent.$current.likerVertex
  )
  WHERE inV().out('HAS_CREATOR').@rid = $parent.$current.startPerson.@rid
  ORDER BY likeCreationDate DESC, messageId ASC
  LIMIT 1
)
ORDER BY likeCreationDate DESC, personId ASC
LIMIT :limit
