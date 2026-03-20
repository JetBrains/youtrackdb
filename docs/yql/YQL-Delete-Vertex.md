# YQL - `DELETE VERTEX`

Removes vertices from the database.

**Syntax**

```sql
   DELETE VERTEX <vertex> [RETURN BEFORE] [WHERE <conditions>] [LIMIT <MaxRecords>] [BATCH <batch-size>]
```

- **`<vertex>`** Defines the vertex that you want to remove, using its Class, Record ID, or through a sub-query using the `FROM (<sub-query>)` clause.
- **`RETURN BEFORE`** Returns the vertex record as it was before deletion.
- **[`WHERE`](YQL-Where.md)** Filter condition to determine which records the command removes.
- **`LIMIT`** Defines the maximum number of records to remove.
- **`BATCH`** Defines how many records the command removes at a time, allowing you to break large transactions into smaller blocks to save on memory usage. By default, it operates on blocks of 100.

**Examples**

- Remove the vertex and disconnect all edges connected to it:

```sql
   DELETE VERTEX #10:231
```

- Remove all user accounts that have an incoming edge of class `BadBehaviorInForum`:

```sql
   DELETE VERTEX Account WHERE inE().@Class CONTAINS 'BadBehaviorInForum'
```

- Remove all vertices from the class `EMailMessage` marked with the property `isSpam`:

```sql
   DELETE VERTEX EMailMessage WHERE isSpam = TRUE
```

- Remove vertices of the class `Attachment` where the incoming edge of class `HasAttachment` has a `date` before 1990, and the source vertex (of class `Email`) has `from` set to `bob@example.com`:

```sql
   DELETE VERTEX Attachment WHERE inE()[@Class = 'HasAttachment'].date < "1990" AND inE().out[@Class = "Email"].from = 'bob@example.com'
```

- Remove vertices in blocks of one thousand:

```sql
   DELETE VERTEX v BATCH 1000
```