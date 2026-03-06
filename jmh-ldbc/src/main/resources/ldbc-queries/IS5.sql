MATCH {class: Message, as: m, where: (id = :messageId)}
  .out('HAS_CREATOR'){as: author}
RETURN author.id as personId,
  author.firstName as firstName,
  author.lastName as lastName