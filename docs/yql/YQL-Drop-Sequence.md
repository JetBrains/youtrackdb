# YQL - `DROP SEQUENCE`

Removes a sequence. If the sequence does not exist, this command throws an error unless you use the `IF EXISTS` clause.

**Syntax**

```sql
DROP SEQUENCE <sequence> [IF EXISTS]
```

- **`<sequence>`** — Defines the name of the sequence you want to remove.
- **`IF EXISTS`** — Prevents an error if the sequence does not exist.


**Examples**

- Remove the sequence `idseq`:

```sql
DROP SEQUENCE idseq
```

- Remove a sequence only if it exists:

```sql
DROP SEQUENCE idseq IF EXISTS
```

>For more information, see
>
>- [`CREATE SEQUENCE`](YQL-Create-Sequence.md)
>- [`ALTER SEQUENCE`](YQL-Alter-Sequence.md)
>- [YQL Commands](YQL-Commands.md)