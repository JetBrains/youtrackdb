# YQL - `CREATE SECURITY POLICY`

Creates a security policy that can be bound to one or more security resources, for one or more roles.

A security resource is a set of YQL predicates, associated with basic operations (e.g., CREATE, READ, and so on), that are evaluated for each record to determine whether the operation is allowed.

**Syntax**

```sql
CREATE SECURITY POLICY <name>
  [SET
    ( [CREATE | READ | BEFORE UPDATE | AFTER UPDATE | DELETE | EXECUTE] = (<yqlPredicate>) )*
  ]
```
- **`<name>`** The security policy name. It is used in the GRANT statement to bind it to a role and a resource.
- **`<yqlPredicate>`** A valid YQL predicate.

**Examples**

- Create an empty policy

```sql
CREATE SECURITY POLICY foo
```
  
- Create a security policy with all the predicates defined:

```sql
CREATE SECURITY POLICY foo SET CREATE = (name = 'foo'), READ = (TRUE), BEFORE UPDATE = (name = 'foo'), AFTER UPDATE = (name = 'foo'), DELETE = (name = 'foo'), EXECUTE = (name = 'foo')
```


>For more information, see
>- [YQL Commands](YQL-Commands.md).
