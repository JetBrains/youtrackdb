# YQL - `DROP CLASS`

Removes a class from the schema.

**Syntax**

```sql
DROP CLASS <class> [IF EXISTS] [UNSAFE]
```

- **`<class>`** — Defines the class you want to remove.
- **`IF EXISTS`** — Prevents an error if the class does not exist.
- **`UNSAFE`** — Forces removal even if the class contains vertices or edges. Without this keyword, the command refuses to drop a class that still has records, to avoid broken edges in the database.

>**NOTE**: Bear in mind that the schema must remain coherent. For instance, avoid removing classes that are superclasses to others.

**Examples**

- Remove the class `Account`:

```sql
DROP CLASS Account
```

>For more information, see
>- [`CREATE CLASS`](YQL-Create-Class.md)
>- [`ALTER CLASS`](YQL-Alter-Class.md)
>- [YQL Commands](YQL-Commands.md)