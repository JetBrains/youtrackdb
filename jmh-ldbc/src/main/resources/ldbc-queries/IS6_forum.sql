MATCH {class: Post, as: post, where: (id = :postId)}
  .in('CONTAINER_OF'){class: Forum, as: forum}
  .out('HAS_MODERATOR'){as: moderator}
RETURN forum.id as forumId, forum.title as forumTitle,
  moderator.id as moderatorId,
  moderator.firstName as moderatorFirstName,
  moderator.lastName as moderatorLastName