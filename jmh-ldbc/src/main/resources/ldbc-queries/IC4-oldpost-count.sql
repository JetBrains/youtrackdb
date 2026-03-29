/* IC4 curation factor: count of friends' posts before startDate.
   This determines the NOT pattern scan cost in IC4. */
MATCH {class: Person, as: p, where: (id = :personId)}
  .out('KNOWS'){as: friend}
  .in('HAS_CREATOR'){class: Post, as: oldPost,
    where: (creationDate < :startDate)}
RETURN count(*) as cnt