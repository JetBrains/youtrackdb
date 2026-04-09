# YQL - `UPDATE EDGE`

Updates edge records in the current database.

Bear in mind that YouTrackDB can also work in schema-less mode, allowing you to create properties on the fly.
Furthermore, it works on collections and includes extensions to standard SQL for handling collections.


**Syntax**

```sql
UPDATE EDGE <edge>
  [SET|INCREMENT|ADD|REMOVE|PUT <property-name> = <field-value>[,]*]|[CONTENT|MERGE <JSON>]
  [UPSERT]
  [RETURN <returning> [<returning-expression>]]
  [WHERE <conditions>]
  [LIMIT <max-records>] [TIMEOUT <timeout>]
```

- **`<edge>`** Defines the edge that you want to update.  You can choose between:
    - *Class* Updating edges by class.
    - *Record ID* Updating edges by Record ID.
- **`SET`** Updates the property to the given value.
- **`INCREMENT`** Increments the numeric property by the given value.
- **`ADD`** Adds a value to a collection property (list, set, or map).
- **`REMOVE`** Removes an item from a collection property.
- **`PUT`** Puts an entry into a map property.
- **`CONTENT`** Replaces the entire record content with a JSON document.
- **`MERGE`** Merges the record content with a JSON document, adding or updating properties.
- **`RETURN`** Specifies what to return after running the update.  When no `RETURN` clause is specified, the command returns the number of updated records (the default).
    - `AFTER` Returns the records after the update.
- **[`WHERE`](YQL-Where.md)** Defines the filter conditions.
- **`UPSERT`** Updates a matching edge if one exists. Unlike `UPSERT` on vertex classes, this clause does **not** support inserting a new edge when no match is found, because edge creation requires explicit `FROM`/`TO` endpoints that the `UPDATE EDGE` syntax does not provide.
- **`LIMIT`** Defines the maximum number of records to update.
- **`TIMEOUT`** Defines the time to allow the update to run before it times out.


**Examples**

- Update edge properties by class:

```sql
UPDATE EDGE Friend SET foo = 'bar' WHERE since < '2020-01-01'
```

## Limitations of the `UPSERT` Clause

The `UPSERT` clause on `UPDATE EDGE` only works when a matching edge already exists. It guarantees atomicity when you use a `UNIQUE` index and perform the look-up on the index through the [`WHERE`](YQL-Where.md) condition.

```sql
UPDATE EDGE hasAssignee SET foo = 'bar' UPSERT WHERE id = 56
```

Here, you must have a unique index on `id` to guarantee uniqueness under concurrent operations. If no edge with `id = 56` exists, the command will fail.


>For more information, see
>
>- [YQL Commands](YQL-Commands.md)
