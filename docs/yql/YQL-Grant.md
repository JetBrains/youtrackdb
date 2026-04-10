# YQL - `GRANT`

Changes the permission of a role, granting it access to a resource.  To remove access to a resource from a role, see the [`REVOKE`](YQL-Revoke.md) command.

**Syntax**

```sql
GRANT [ <permission> | POLICY <policyName> ] ON <resource> TO <role>
```

- **`<permission>`** Defines the permission you want to grant to the role.
- **`<policyName>`** Defines the name of a security policy.
- **`<resource>`** Defines the resource on which you want to grant the permissions.
- **`<role>`** Defines the role to which you want to grant the permissions.

**Examples**

- Bind a security policy called `policy1` to Person class records, for the role `backoffice`:

```sql
GRANT POLICY policy1 ON database.class.Person TO backoffice
```

>For more information, see
>- [`REVOKE`](YQL-Revoke.md)
>- [YQL Commands](YQL-Commands.md)


## Supported Permissions

Using this command, you can grant the following permissions to a role.

| Permission | Description                                                                                                                                   |
|---|-----------------------------------------------------------------------------------------------------------------------------------------------|
| `NONE` | Grants no permissions on the resource.                                                                                                        |
| `CREATE` | Grants create permissions on the resource, such as the [`CREATE CLASS`](YQL-Create-Class.md) command.                                             |
| `READ` | Grants read permissions on the resource, such as the [`SELECT`](YQL-Query.md) query.                                                          |
| `UPDATE` | Grants update permissions on the resource, such as the [`UPDATE`](YQL-Update.md) or [`UPDATE EDGE`](YQL-Update-Edge.md) commands.               |
| `DELETE` | Grants delete permissions on the resource, such as the [`DROP INDEX`](YQL-Drop-Index.md) or [`DROP SEQUENCE`](YQL-Drop-Sequence.md) commands. |
| `ALL` | Grants all permissions on the resource.                                                                                                       |


## Supported Resources

Using this command, you can grant permissions on the following resources.

| Resource | Description                                                                                                                                                                                                                                       |
|---|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `database` | Grants access to the current database.                                                                                                                                                                                                            |
| `database.class.<class>` | Grants access on records contained in the indicated class.  Use `*` to indicate all classes.                                                                                                                                                      |
| `database.class.<class>.<property>` | Grants access on a single property in the indicated class (this is intended only for security policies).                                                                                                                                          |
| `database.command.<command>` | Grants the ability to execute the given command.  Use `CREATE` for [`CREATE VERTEX`](YQL-Create-Vertex.md), `READ` for [`SELECT`](YQL-Query.md), `UPDATE` for [`UPDATE`](YQL-Update.md), and `DELETE VERTEX` for [`DELETE VERTEX`](YQL-Delete-Vertex.md). |


Policy assignment is supported for records only, so you can assign security policies to `class` and `property` resources.