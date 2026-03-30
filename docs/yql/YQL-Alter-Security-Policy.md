# YQL - `ALTER SECURITY POLICY`

Alters an existing security policy.

A security policy is a set of YQL predicates, associated with basic operations (e.g., CREATE, READ, and so on), that are evaluated for each record to determine whether the operation is allowed.

**Syntax**

```sql
ALTER SECURITY POLICY <name>
  SET ( <operation> = (<sqlPredicate>) ) [, ( <operation> = (<sqlPredicate>) )]*
  | REMOVE <operation> [, <operation>]*

<operation> := CREATE | READ | BEFORE UPDATE | AFTER UPDATE | DELETE | EXECUTE
```
- **`<name>`** — the security policy name. It is used in the GRANT statement to bind it to a role and a resource.
- **`<sqlPredicate>`** — a valid YQL predicate.

At least one of `SET` or `REMOVE` is required. Both can appear in the same statement.

**Examples**

- Change CREATE and READ predicates for a security policy:

```sql
ALTER SECURITY POLICY foo SET CREATE = (name = 'foo'), READ = (TRUE)
```

- Remove CREATE and READ predicates for a security policy:

```sql
ALTER SECURITY POLICY foo REMOVE CREATE, READ
```

>See also
>- [YQL Commands](YQL-Commands.md).
>- [CREATE SECURITY POLICY](YQL-Create-Security-Policy.md).

