# YQL - `EXPLAIN`

The EXPLAIN YQL command returns the execution plan of a specific statement, without executing the statement itself.

**Syntax**

```
EXPLAIN <command>
```

- **`<command>`** Defines the command that you want to explain, e.g., a SELECT or MATCH statement

**Examples**


- Explain a query that executes on a class filtering based on an attribute:

```sql
explain select from v where name = 'a'
```

Result
```
   '[{
  executionPlan:{...},
  executionPlanAsString:
  + FETCH FROM CLASS v
    + FETCH FROM COLLECTION 9 ASC
    + FETCH FROM COLLECTION 10 ASC
    + FETCH FROM COLLECTION 11 ASC
    + FETCH FROM COLLECTION 12 ASC
    + FETCH FROM COLLECTION 13 ASC
    + FETCH FROM COLLECTION 14 ASC
    + FETCH FROM COLLECTION 15 ASC
    + FETCH FROM COLLECTION 16 ASC
    + FETCH NEW RECORDS FROM CURRENT TRANSACTION SCOPE (if any)
  + FILTER ITEMS WHERE 
    name = 'a'
  }]'
```

>For more information, see
>- [YQL Commands](YQL-Commands.md)
>- [PROFILE](YQL-Profile.md)