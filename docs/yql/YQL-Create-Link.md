# YQL - `CREATE LINK`

Creates links between records of two classes by matching property values. For each record in the source class, the command looks up the corresponding record in the destination class where the destination property equals the source property value, then replaces the raw value with a record-level link (RID reference).

The link property must already exist on the target class with the correct type before running this command. For non-inverse links, the source class must have a property of type `LINK` with the specified name. For inverse links, the destination class must have a property of the specified link type (e.g., `LINKSET`) with the specified name.

**Syntax**

```sql
CREATE LINK <link-name> TYPE <link-type>
  FROM <source-class>.<source-property>
  TO <destination-class>.<destination-property>
  [INVERSE]
```

- **`<link-name>`** Defines the property name for the link.
- **`<link-type>`** Defines the type for the link. Use `LINK` for one-to-one relationships. For inverse relationships, you can specify `LINKSET` or `LINKLIST` for one-to-many relationships.
- **`<source-class>`** Defines the class to link from.
- **`<source-property>`** Defines the property to link from. Can also be a record attribute such as `@rid`.
- **`<destination-class>`** Defines the class to link to.
- **`<destination-property>`** Defines the property to link to. Can also be a record attribute such as `@rid`.
- **`INVERSE`** When specified, creates the link on the destination class pointing back to source records, instead of on the source class pointing to destination records.

**Examples**

- Create a link from each `Comments` record to the matching `Posts` record. The `Comments` class must already have a `post` property of type `LINK`:

```sql
CREATE LINK post TYPE LINK FROM Comments.PostId TO Posts.Id
```

- Create an inverse link so that each `Posts` record has a `LINKSET` of its `Comments`. The `Posts` class must already have a `comments` property of type `LINKSET`:

```sql
CREATE LINK comments TYPE LINKSET FROM Comments.PostId TO Posts.Id INVERSE
```

## See Also

- [YQL Commands](YQL-Commands.md)
