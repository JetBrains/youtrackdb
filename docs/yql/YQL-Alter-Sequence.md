# YQL - `ALTER SEQUENCE`

Changes the sequence. Using this command, you can change all sequence options except for the sequence type.

**Syntax**

```sql
ALTER SEQUENCE <sequence> [START <start-point>] [INCREMENT <increment>] [CACHE <cache>] [CYCLE TRUE|FALSE] [LIMIT <limit_value>] [ASC|DESC] [NOLIMIT]
```

- **`<sequence>`** Defines the sequence you want to change.
- **`START`** Defines the initial sequence value.
- **`INCREMENT`** Defines the increment value applied when calling `.next()`.
- **`CACHE`** Defines the number of values to cache, if the sequence is of the type `CACHED`.
- **`CYCLE`** Defines whether the sequence restarts from the `START` value after the `LIMIT` value is reached. Default value is `FALSE`.
- **`LIMIT`** Defines the limit value the sequence can reach. After the limit value is reached, cyclic sequences restart from the `START` value, while non-cyclic sequences throw an exception indicating that the limit has been reached.
- **`ASC | DESC`** Defines the order of the sequence. `ASC` defines that the next sequence value will be `currentValue + incrementValue`, while `DESC` defines that the next sequence value will be `currentValue - incrementValue` (assuming that the limit is not reached). Default value is `ASC`.
- **`NOLIMIT`** Cancels a previously defined `LIMIT` value.

**Examples**

- Alter a sequence, resetting the start value to `1000`:

```sql
ALTER SEQUENCE idseq START 1000 CYCLE TRUE
```

> For more information, see:
>
>- [`CREATE SEQUENCE`](YQL-Create-Sequence.md)
>- [`DROP SEQUENCE`](YQL-Drop-Sequence.md)
>- [YQL Commands](YQL-Commands.md)