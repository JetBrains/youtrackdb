# YQL - `PROFILE`

The PROFILE YQL command returns information about query execution planning and statistics for a specific statement.
The statement is actually executed to provide the execution stats.

The result is the execution plan of the query (like for [EXPLAIN](YQL-Explain.md)) with additional information about
execution time spent on each step, in microseconds.

**Syntax**

```sql
PROFILE <command>
```

- **`<command>`** Defines the command that you want to profile, e.g., a SELECT statement.

**Examples**


```sql
PROFILE SELECT sum(Amount), OrderDate 
FROM Orders 
WHERE OrderDate > date('2012-12-09', 'yyyy-MM-dd')
GROUP BY OrderDate
```
Result:

```
+ FETCH FROM INDEX Orders.OrderDate (1.445μs)
  OrderDate > date('2012-12-09', 'yyyy-MM-dd')
+ EXTRACT VALUE FROM INDEX ENTRY
+ FILTER ITEMS BY CLASS 
  Orders
+ CALCULATE PROJECTIONS (5.065μs)
  Amount AS _$$$OALIAS$$_1, OrderDate
+ CALCULATE AGGREGATE PROJECTIONS (3.182μs)
  sum(_$$$OALIAS$$_1) AS _$$$OALIAS$$_0, OrderDate
  GROUP BY OrderDate
+ CALCULATE PROJECTIONS (1.116μs)
  _$$$OALIAS$$_0 AS `sum(Amount)`, OrderDate
```

You can see the `(1.445μs)` at the end of the first line. It means that fetching from index `Orders.OrderDate` took 1.445 microseconds.

>For more information, see
>- [YQL Commands](YQL-Commands.md)
>- [EXPLAIN](YQL-Explain.md)