# YQL - `CREATE SEQUENCE`

Creates a new sequence.

**Syntax**

```
CREATE SEQUENCE <sequence> [IF NOT EXISTS] TYPE <CACHED|ORDERED>
  [START <start>] [INCREMENT <increment>] [CACHE <cache>]
  [LIMIT <limit>] [CYCLE <TRUE|FALSE>] [ASC | DESC]
```
- **`<sequence>`** Logical name for the sequence to create.
- **`IF NOT EXISTS`** Creates the sequence only if it does not already exist. If omitted and the sequence exists, an error is thrown.
- **`TYPE`** Defines the sequence type. Supported types are:
    - `CACHED` Caches N items on each node to improve performance when you require many calls to the `.next()` method. Bear in mind, this may create holes in numeration.
    - `ORDERED` Draws a new value with each call to the `.next()` method.
- **`START`** Defines the initial value of the sequence.
- **`INCREMENT`** Defines the increment for each call of the `.next()` method.
- **`CACHE`** Defines the number of values to pre-cache, in the event that you use the `CACHED` sequence type.
- **`CYCLE`** Defines whether the sequence restarts from the `START` value after the `LIMIT` value is reached. Default value is `FALSE`.
- **`LIMIT`** Defines the limit value the sequence can reach. After the limit value is reached, cyclic sequences restart from the `START` value, while non-cyclic sequences throw an error indicating the limit has been reached.
- **`ASC | DESC`** Defines the order of the sequence. `ASC` means the next sequence value is `currentValue + incrementValue`, while `DESC` means the next sequence value is `currentValue - incrementValue` (assuming the limit has not been reached). Default value is `ASC`.


**Examples**

- Create a new sequence to handle id numbers:

```sql
CREATE SEQUENCE idseq TYPE ORDERED
```

- Use the new sequence to insert id values:

```sql
CREATE VERTEX Account SET id = sequence('idseq').next()
```
 
>For more information, see
>
>- [`ALTER SEQUENCE`](YQL-Alter-Sequence.md)
>- [DROP SEQUENCE](YQL-Drop-Sequence.md)
>- [YQL commands](YQL-Commands.md).
