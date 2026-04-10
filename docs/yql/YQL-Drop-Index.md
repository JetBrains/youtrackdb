# YQL - `DROP INDEX`

Removes an index defined in the schema.

If the index does not exist, this command throws an error unless you use the `IF EXISTS` clause.

**Syntax**

```sql
DROP INDEX <index> [ IF EXISTS ]
DROP INDEX *
```

- **`<index>`** — the name of the index to drop.
- **`*`** — drops all indexes defined in the schema.

**Examples**

- Remove the index on the `Id` property of the `Users` class:

```sql
DROP INDEX Users.Id
```

- Remove all indexes:

```sql
DROP INDEX *
```

>For more information, see
>- [`CREATE INDEX`](YQL-Create-Index.md)
>- [YQL Commands](YQL-Commands.md)