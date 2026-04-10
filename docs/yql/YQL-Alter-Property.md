# YQL - `ALTER PROPERTY`

Updates attributes of an existing property in the schema.

**Syntax**

```sql
ALTER PROPERTY <class>.<property> <attribute-name> <attribute-value>
```

- **`<class>`** Defines the class to which the property belongs.
- **`<property>`** Defines the property you want to update.
- **`<attribute-name>`** Defines the attribute you want to change.
- **`<attribute-value>`** Defines the value you want to set on the attribute.


**Examples**

- Change the name of the property `age` in the class `Account` to `born`:

```sql
ALTER PROPERTY Account.age NAME "born"
```
  
- Update a property to make it mandatory:

```sql
ALTER PROPERTY Account.age MANDATORY TRUE
```
  
- Define a Regular Expression as a constraint:

```sql
ALTER PROPERTY Account.gender REGEXP "[MF]"
```
  
- Define a property as case-insensitive for comparisons:

```sql
ALTER PROPERTY Employee.name COLLATE "ci"
```
  
- Define a custom attribute on a property:

```sql
ALTER PROPERTY Foo.bar1 custom stereotype="visible"
```

- Set the default value to the current date:

```sql
ALTER PROPERTY Client.created DEFAULT "sysdate()"
```
  
- Define a unique ID that cannot be changed after creation:

```sql
ALTER PROPERTY Client.id DEFAULT "uuid()"
ALTER PROPERTY Client.id READONLY TRUE
```


## Supported Attributes

|Attribute|Type| Description                                                                                                                                                                                                                                                                                                                                                                     |
|---|---|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `LINKEDCLASS` | String | Defines the linked class name.  Use `NULL` to remove an existing value.                                                                                                                                                                                                                                                                                                         |
| `LINKEDTYPE` | String | Defines the link type.  Use `NULL` to remove an existing value.                                                                                                                                                                                                                                                                                                                 |
| `MIN` | String | Defines the minimum value as a constraint.  Use `NULL` to remove an existing constraint.  On String attributes, it defines the minimum length of the string.  On Integer attributes, it defines the minimum value for the number.  On Date attributes, the earliest date accepted.  For multi-value attributes (lists, sets and maps), it defines the fewest number of entries. |
| `MANDATORY` | Boolean | Defines whether the property requires a value.                                                                                                                                                                                                                                                                                                                                  |
| `MAX` | String | Defines the maximum value as a constraint.  Use `NULL` to remove an existing constraint.  On String attributes, it defines the greatest length of the string.  On Integer attributes, it defines the maximum value for the number.  On Date attributes, the last date accepted.  For multi-value attributes (lists, sets and maps), it defines the highest number of entries.   |
| `NAME` | String | Defines the property name.                                                                                                                                                                                                                                                                                                                                                      |
| `NOTNULL` | Boolean | Defines whether the property can have a null value.                                                                                                                                                                                                                                                                                                                             |
| `REGEXP` | String | Defines a Regular Expression as constraint.  Use `NULL` to remove an existing constraint.                                                                                                                                                                                                                                                                                       |
| `TYPE` | String | Defines a property type.  Only castable type changes are allowed (for example, `INTEGER` to `LONG`).  Incompatible changes are rejected.                                                                                                                                                                                                                                               |
| `COLLATE` | String | Sets collate to one of the defined comparison strategies.  By default, it is set to case-sensitive (`cs`).  You can also set it to case-insensitive (`ci`).                                                                                                                                                                                                                     |
| `READONLY` | Boolean | Defines whether the property value is immutable.  That is, if it is possible to change it after the first assignment.  Use with `DEFAULT` to have immutable values on creation.                                                                                                                                                                                                 |
| `CUSTOM` | String | Defines custom properties.  The syntax for custom properties is `<custom-name> = <custom-value>`, such as `stereotype = icon`. The custom name is an identifier. The value is a string, so it has to be quoted with single or double quotes.                                                                                                                                    |
| `DEFAULT` | String | Defines the default value or function.  Use `NULL` to remove an existing constraint.                                                                                                                                                                                                                                                                                            |
| `DESCRIPTION` | String | Defines a human-readable description for the property.                                                                                                                                                                                                                                                                                                                            |

When altering `NAME` or `TYPE`, this command runs a data update that may take some time, depending on the amount of data.
Do not shut the database down during this migration. When altering a property name, the old values are copied to the new property name.


>To create a property, use the [`CREATE PROPERTY`](YQL-Create-Property.md) command, to remove a property the [`DROP PROPERTY`](YQL-Drop-Property.md) command.  
>For more information on other commands, please refer to [YQL Commands](YQL-Commands.md).