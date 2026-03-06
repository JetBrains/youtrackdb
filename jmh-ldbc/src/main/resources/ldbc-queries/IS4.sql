SELECT coalesce(imageFile, content) as messageContent, creationDate
FROM V WHERE id = :messageId
