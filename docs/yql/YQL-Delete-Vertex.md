# YQL - `DELETE VERTEX`

Removes vertices from the database. When a vertex is deleted, all edges connected to it are also removed.

**Syntax**

```
DELETE VERTEX <vertex> [WHERE <conditions>] [LIMIT <MaxRecords>] [BATCH <batch-size>]
```

- **`<vertex>`** Defines the vertex to remove, using its Class, Record ID, or a sub-query with the `FROM (<sub-query>)` clause.
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

- Remove vertices of the class `Attachment` where the source vertex of the incoming `HasAttachment` edge has `sender` set to `bob@example.com`:

```sql
DELETE VERTEX Attachment WHERE inE('HasAttachment').outV().sender CONTAINS 'bob@example.com'
```

- Remove vertices in blocks of one thousand:

```sql
DELETE VERTEX Attachment BATCH 1000
```

> For more information, see
>
> - [YQL Commands](YQL-Commands.md)
