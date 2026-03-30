
# YQL - `CREATE PROPERTY`

Creates a new property in the schema. It requires that the class for the property already exists in the database.

**Syntax**

```sql
CREATE PROPERTY
<class>.<property> <type>
[<link-type>|<link-class>]
( <property constraint> [, <property-constraint>]* )
```

- **`<class>`** Defines the class for the new property.
- **`<property>`** Defines the logical name for the property.
- **`<type>`** Defines the property data type. For supported types, see the table below.
- **`<link-type>`** Defines the contained type for container property data types. For supported link types, see the table below.
- **`<link-class>`** Defines the contained class for container property data types. For supported link types, see the table below.
- **`<property-constraint>`** See [`ALTER PROPERTY`](YQL-Alter-Property.md) `<attribute-name> [ <attribute-value> ]`

>When you create a property, YouTrackDB checks the existing data for compatibility with the specified type. If persistent data contains incompatible values, the property creation fails. It applies no other constraints on the persistent data.

**Examples**

- Create the property `name` of the string type in the class `User`:

```sql
CREATE PROPERTY User.name STRING
```
  
- Create a property formed from a list of strings called `tags` in the class `Profile`:

```sql
CREATE PROPERTY Profile.tags EMBEDDEDLIST STRING
```

- Create the property `name` of the string type in the class `User`, mandatory, with minimum and maximum length:

```sql
CREATE PROPERTY User.name STRING (MANDATORY TRUE, MIN 5, MAX 25)
```


>For more information, see
>
>- [`DROP PROPERTY`](YQL-Drop-Property.md)
>- [YQL Commands](YQL-Commands.md)


## Supported Types

YouTrackDB supports the following data types for standard properties:

| | | | | |
|---|---|---|---|---|
| `BOOLEAN` | `SHORT` | `DATE` | `DATETIME` | `BYTE`|
| `INTEGER` | `LONG` | `STRING` | `LINK` | `DECIMAL` |
| `DOUBLE` | `FLOAT` | `BINARY` |  | |

It supports the following data types for container properties.

||||
|---|---|---|
| `EMBEDDEDLIST` | `EMBEDDEDSET` | `EMBEDDEDMAP` |
| `LINKLIST` | `LINKSET` | `LINKMAP` |

For these data types, you can optionally define the contained type and class. The supported link types are the same as the standard property data types above.