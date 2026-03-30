# YQL - `CREATE LINK`

Creates a link between two simple values.

**Syntax**

```sql
CREATE LINK <link> TYPE <link-type> FROM <source-class>.<source-property> TO <destination-class>.<destination-property> [INVERSE]
```

- **`<link>`** Defines the property for the link.
- **`<link-type>`** Defines the type for the link. In the event of an inverse relationship, you can specify `LINKSET` or `LINKLIST` for 1-*n* relationships.
- **`<source-class>`** Defines the class to link from.
- **`<source-property>`** Defines the property to link from.
- **`<destination-class>`** Defines the class to link to.
- **`<destination-property>`** Defines the property to link to.
- **`INVERSE`** Defines whether to create a connection in the opposite direction.

**Example**

- Create an inverse link between the classes `Comments` and `Posts`:

```sql
CREATE LINK comments TYPE LINKSET FROM Comments.PostId TO Posts.Id INVERSE
```

>For more information, see
>
>- [YQL Commands](YQL-Commands.md)
