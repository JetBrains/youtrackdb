# YQL - `CREATE CLASS`

Creates a new class in the schema.

**Syntax**

```sql
CREATE CLASS <class>
[ IF NOT EXISTS ]
[EXTENDS <super-class>] [ABSTRACT]
```

- **`<class>`** — defines the name of the class you want to create. The first character must be a letter; subsequent characters can be alphanumeric or underscores.
- **`IF NOT EXISTS`** — if specified, the statement is ignored when the class already exists (instead of failing with an error).
- **`<super-class>`** — defines the superclass you want to extend with this class.
- **`ABSTRACT`** — defines the class as abstract. You cannot create instances of an abstract class.

A class must be a child of `V` (Vertex) or `E` (Edge).

**Examples**

- Create the class `Account`:

```sql
CREATE CLASS Account EXTENDS V
```
  
- Create the class `Car` to extend `Vehicle`:

```sql
CREATE CLASS Car EXTENDS Vehicle
```

- Create the class `Person` as an abstract class:

```sql
CREATE CLASS Person EXTENDS V ABSTRACT
```

>For more information, see
>
>- [`ALTER CLASS`](YQL-Alter-Class.md)
>- [`DROP CLASS`](YQL-Drop-Class.md)
>- [YQL Commands](YQL-Commands.md)
