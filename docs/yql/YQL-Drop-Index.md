# YQL - `DROP INDEX`

Removes an index defined in the schema.

If the index does not exist, this command throws an error unless you use the `IF EXISTS` clause.

**Syntax**

```sql
DROP INDEX <index> [ IF EXISTS ]
```

- **`<index>`** — the name of the index to drop.

**Examples**

- Remove the index on the `Id` property of the `Users` class:

```sql
DROP INDEX Users.Id
```

>For more information, see
>- [`CREATE INDEX`](YQL-Create-Index.md)
>- [SQL Commands](YQL-Commands.md)