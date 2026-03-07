/* IS4: Content of a message.
   Given a Message (Post/Comment), retrieve its content and creation date. */
SELECT coalesce(imageFile, content) as messageContent, creationDate
FROM Message WHERE id = :messageId
