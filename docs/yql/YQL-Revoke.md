# YQL - `REVOKE`

Changes permissions of a role, revoking access to one or more resources.  To give access to a resource to the role, see the [`GRANT`](YQL-Grant.md) command.

**Syntax**

```sql
REVOKE [ <permission> | POLICY ] ON <resource> FROM <role>
```
- **`<permission>`** Defines the permission you want to revoke from the role.
- **`<resource>`** Defines the resource on which you want to revoke the permissions.
- **`<role>`** Defines the role from which you want to revoke the permissions.

**Examples**

- Revoke a security policy previously assigned to the `backoffice` role on the Person class:

```sql
 REVOKE POLICY ON database.class.Person FROM backoffice
```


>For more information, see
>- [YQL Commands](YQL-Commands.md).


## Supported Permissions

Using this command, you can revoke the following permissions from a role.

| Permission | Description                                                                                                                                          |
|---|------------------------------------------------------------------------------------------------------------------------------------------------------|
| `NONE` | Revokes no permissions on the resource.                                                                                                              |
| `CREATE` | Revokes create permissions on the resource, such as the [`CREATE CLASS`](YQL-Create-Class.md) command. |
| `READ` | Revokes read permissions on the resource, such as the [`SELECT`](YQL-Query.md) query.                                                                |
| `UPDATE` | Revokes update permissions on the resource, such as the [`UPDATE`](YQL-Update.md) or [`UPDATE EDGE`](YQL-Update.md) commands.                        |
| `DELETE` | Revokes delete permissions on the resource, such as the [`DROP INDEX`](YQL-Drop-Index.md) or [`DROP SEQUENCE`](YQL-Drop-Sequence.md) commands.       |
| `ALL` | Revokes all permissions on the resource.                                                                                                             |


## Supported Resources

Using this command, you can revoke permissions on the following resources.

| Resource | Description                                                                                                                                                                                                                                               |
|---|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `database` | Revokes access on the current database.                                                                                                                                                                                                                   |
| `database.class.<class>` | Revokes access on records contained in the indicated class.  Use `*` to indicate all classes.                                                                                                                                                             |
| `database.class.<class>.<property>` | Intended only for security policies. Revokes policies assigned to a specific class property for a role.  Use `*` to indicate all classes and/or all properties.                                                                                           |
| `database.command.<command>` | Revokes the ability to execute the given command.  Use `CREATE` for [`CREATE VERTEX`](YQL-Create-Vertex.md), `READ` for [`SELECT`](YQL-Query.md), `UPDATE` for [`UPDATE`](YQL-Update.md) and `DELETE VERTEX` for [`DELETE VERTEX`](YQL-Delete-Vertex.md). |