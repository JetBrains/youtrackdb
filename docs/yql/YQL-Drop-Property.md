# YQL - `DROP PROPERTY`

Removes a property from the schema. It does not remove the property values in the records; it only changes the schema information. Records continue to have the property values, if any.

**Syntax**

```sql
DROP PROPERTY <class>.<property> [IF EXISTS] [FORCE]
```

- **`<class>`** — Defines the class where the property exists.
- **`<property>`** — Defines the property you want to remove.
- **`IF EXISTS`** — Prevents an error if the property does not exist.
- **`FORCE`** — Forces removal of the property along with any indexes defined on it. Without this keyword, the command throws an exception if indexes exist on the property.

**Examples**

- Remove the `name` property from the class `User`:

```sql
DROP PROPERTY User.name
```

>For more information, see
>- [`CREATE PROPERTY`](YQL-Create-Property.md)
>- [YQL Commands](YQL-Commands.md)
