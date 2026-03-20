# YQL - `DELETE EDGE`

Removes edges from the database.

**Syntax**

```sql
DELETE EDGE
  ( <rid>
    |
    [<rid> (, <rid>)*]
    |
    ( [ FROM (<rid> | <select_statement> ) ] [ TO ( <rid> | <select_statement> ) ] )
    |
    [<class>]
  )
  [WHERE <conditions>]
  [LIMIT <MaxRecords>]
  [BATCH <batch-size>]
```

- **`FROM`** Defines the starting-point vertex of the edge to delete.
- **`TO`** Defines the ending-point vertex of the edge to delete.
- **`WHERE`** Defines the filtering conditions.
- **`LIMIT`** Defines the maximum number of edges to delete.
- **`BATCH`** Defines the block size for the operation, allowing you to break large transactions down into smaller units to reduce resource demands.


**Examples**

- Delete an edge by its RID:

```sql
   DELETE EDGE #22:38482
```

- Delete edges by RIDs:

```sql
   DELETE EDGE [#22:38482,#23:232,#33:2332]
```

- Delete edges between two vertices, filtering by a property condition:

```sql
   DELETE EDGE FROM #11:101 TO #11:117 WHERE date >= "2012-01-15"
```

- Delete edges filtering by the edge class:

```sql
   DELETE EDGE FROM #11:101 TO #11:117 WHERE @class = 'Owns' AND comment LIKE '%forbidden%'
```

- Delete edges filtering by the edge class and date:

```sql
   DELETE EDGE Owns WHERE date < "2011-11"
```

- Delete edges where `inV().price` applies a condition to the destination vertex:

```sql
   DELETE EDGE Owns WHERE date < "2011-11" AND inV().price >= 202.43
```

- Delete edges in blocks of one thousand per transaction:

```sql
   DELETE EDGE Owns WHERE date < "2011-11" BATCH 1000
```

- Delete edges matching a sub-query:

```sql
   DELETE EDGE E WHERE @rid IN (SELECT @rid FROM E)
```

>For more information, see
>
>- [YQL Commands](YQL-Commands.md)


## Use Cases

### Deleting Edges from a Sub-query

Consider a situation where you have an edge with a Record ID of `#11:0` that you want to delete. In attempting to do so, you run the following query:

```sql
   DELETE EDGE FROM (SELECT FROM #11:0)
```

This does **not** delete the edge — it fails because the `FROM` clause expects a vertex, but `SELECT FROM #11:0` returns an edge record. To delete edges using sub-queries, use the `WHERE @rid IN` syntax instead:

```sql
   DELETE EDGE E WHERE @rid IN (SELECT FROM #11:0)
```

This removes the edge from your database.
