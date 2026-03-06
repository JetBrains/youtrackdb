MATCH {class: Message, as: msg, where: (id = :messageId)}
  .out('REPLY_OF'){while: ($currentMatch.@class = 'Comment'),
                       where: (@class = 'Post'), as: post}
RETURN post.id as postId