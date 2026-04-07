# YQL - `CREATE USER`

Creates a user in the current database, using the specified password and an optional role. When the role is unspecified, it defaults to `writer`.

**Syntax**

```sql
CREATE USER <user> IDENTIFIED BY <password> [ROLE <role> | ROLE [<role1>, <role2>, ...]]
```

- **`<user>`** — Defines the logical name of the user you want to create.
- **`<password>`** — Defines the password to use for this user.
- **`ROLE`** — Defines the role you want to set for the user. For multiple roles, use the bracket syntax: `['author', 'writer']`.

**Examples**

- Create a new admin user called `Foo` with the password `bar`:

```sql
CREATE USER Foo IDENTIFIED BY bar ROLE admin
```

- Create a new user called `Bar` with the password `foo`:

```sql
CREATE USER Bar IDENTIFIED BY Foo
```
  
>For more information, see
>
>- [`DROP USER`](YQL-Drop-User.md)
>- [YQL Commands](YQL-Commands.md)