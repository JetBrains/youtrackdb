/* IS6: Forum of a message.
   Given a Message, retrieve the containing Forum and its moderator.
   Note: uses where:(@class='Forum') instead of class:Forum because the MATCH engine
   does not resolve {class:X} correctly after a while/where recursive traversal step. */
MATCH {class: Message, as: msg, where: (id = :messageId)}
  .out('REPLY_OF'){while: ($currentMatch.@class = 'Comment'),
                       where: (@class = 'Post'), as: post}
  .in('CONTAINER_OF'){where: (@class = 'Forum'), as: forum}
  .out('HAS_MODERATOR'){as: moderator}
RETURN forum.id as forumId, forum.title as forumTitle,
  moderator.id as moderatorId,
  moderator.firstName as moderatorFirstName,
  moderator.lastName as moderatorLastName
