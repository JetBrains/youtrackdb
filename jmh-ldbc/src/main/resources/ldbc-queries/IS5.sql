/* IS5: Creator of a message.
   Given a Message, retrieve its author. */
MATCH {class: Message, as: m, where: (id = :messageId)}
  .out('HAS_CREATOR'){as: author}
RETURN author.id as personId,
  author.firstName as firstName,
  author.lastName as lastName
