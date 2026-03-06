/* IS3: Friends of a person.
   Given a Person, retrieve all friends with friendship creation dates. */
MATCH {class: Person, as: p, where: (id = :personId)}
  .outE('KNOWS'){as: k}.inV(){as: friend}
RETURN friend.id as personId, friend.firstName as firstName,
  friend.lastName as lastName, k.creationDate as friendshipCreationDate
ORDER BY friendshipCreationDate DESC, personId ASC
