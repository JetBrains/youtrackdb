# YQL - `REBUILD INDEX`

Rebuilds automatic indexes.

**Syntax**

```sql
REBUILD INDEX <index>
```

- **`<index>`** Defines the index that you want to rebuild. Use `*` to rebuild all automatic indexes.

**Examples**

- Rebuild an index on the `nick` property of the class `Profile`:

```sql
REBUILD INDEX Profile.nick
```

- Rebuild all indexes:

```sql
REBUILD INDEX *
```

> For more information, see
> - [`CREATE INDEX`](YQL-Create-Index.md)
> - [`DROP INDEX`](YQL-Drop-Index.md)
> - [YQL Commands](YQL-Commands.md)