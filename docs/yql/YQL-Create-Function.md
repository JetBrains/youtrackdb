# YQL - `CREATE FUNCTION`

Creates a new function.


**Syntax**

```sql
CREATE FUNCTION <name> <code>
                [PARAMETERS [<comma-separated list of parameters' name>]]
                [IDEMPOTENT true|false]
                [LANGUAGE <language>]
```

- **`<name>`** Defines the function name.
- **`<code>`** Defines the function code.
- **`PARAMETERS`** Defines a comma-separated list of parameters bound to the execution heap. You must wrap your parameters list in square brackets [].
- **`IDEMPOTENT`** Defines whether the function can change the database status. By default, it is set to `FALSE`.
- **`LANGUAGE`** Defines the language to use. By default, it is set to `SQL`.

**Examples**

- Create a function `test()` in JavaScript, which takes no parameters:
```sql
CREATE FUNCTION test "print('\nTest!')" LANGUAGE javascript
```

- Create a function `test(a,b)` in JavaScript, which takes 2 parameters:

```sql
CREATE FUNCTION test "return a + b;" PARAMETERS [a,b] LANGUAGE javascript
```

- Create a function `allUsersButAdmin` in SQL, which takes no parameters:

```sql
CREATE FUNCTION allUsersButAdmin "SELECT FROM ouser WHERE name <> 'admin'" LANGUAGE SQL
```

>For more information, see
>
>- [YQL Commands](YQL-Commands.md)