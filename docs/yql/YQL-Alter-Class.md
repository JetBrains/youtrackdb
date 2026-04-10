# YQL - `ALTER CLASS`

Updates attributes on an existing class in the schema.

**Syntax**

```sql
ALTER CLASS <class> <attribute-name> <attribute-value>
```

- **`<class>`** Defines the class you want to change.
- **`<attribute-name>`** Defines the attribute you want to change. For a list of supported attributes, see the table below.
- **`<attribute-value>`** Defines the value you want to set.

**Examples**

- Set the superclasses of a class:

```sql
ALTER CLASS Employee SUPERCLASSES Person
```

- Set multiple superclasses (multiple inheritance):

```sql
ALTER CLASS Employee SUPERCLASSES Person, Serializable
```

- Update the class name from `Account` to `Seller`:

```sql
ALTER CLASS Account NAME Seller
```

- Convert the class `TheClass` to an abstract class:

```sql
ALTER CLASS TheClass ABSTRACT true
```

> For more information, see [`CREATE CLASS`](YQL-Create-Class.md) and [`DROP CLASS`](YQL-Drop-Class.md) commands.
> For more information on other commands, refer to [YQL Commands](YQL-Commands.md).


## Supported Attributes

| Attribute | Type | Description |
|---|---|---|
| `NAME` | Identifier | Changes the class name. |
| `SUPERCLASSES` | Identifier \[, Identifier\]* | Replaces the superclasses of the class. Supports multiple inheritance via a comma-separated list. |
| `STRICT_MODE` | Boolean | Enables or disables strict mode. When in strict mode, you work in schema-full mode and cannot add new properties to a record if they are not part of the class's schema definition. |
| `CUSTOM` | | Defines custom properties. Property names and values must follow the syntax `<property-name>=<value>`. The custom property name is an identifier. The value is a string, so it must be quoted with single or double quotes. |
| `ABSTRACT` | Boolean | Converts a class to an abstract class or the opposite. |
| `DESCRIPTION` | Identifier | Sets or updates the class description. |
