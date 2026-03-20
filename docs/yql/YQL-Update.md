# YQL - `UPDATE`

Update one or more records in the current database.  
Remember: YouTrackDB can work in schema-less mode, so you can create any properties on-the-fly. 
Furthermore, the command also supports extensions to work on collections.

**Syntax**:

```sql
UPDATE <class>|<recordID>
  [SET|REMOVE <property-name> = <property-value>[,]*]|[CONTENT|MERGE <JSON>]
  [UPSERT]
  [RETURN <returning> [<returning-expression>]]
  [WHERE <conditions>]
  [LIMIT <max-records>] [TIMEOUT <timeout>]
```

- **`SET`** Defines the properties to update.
- **`REMOVE`** Removes an item in collection and map properties.
- **`CONTENT`** Replaces the record content with a JSON document.
- **`MERGE`** Merges the record content with a JSON document.
- **`UPSERT`** Updates a record if it exists or inserts a new record if it doesn't.  This avoids the need to execute two commands, (one for each condition, inserting and updating).
  `UPSERT` requires a [`WHERE`](SQL-Where.md) clause and a class target.  There are further limitations on `UPSERT`, explained below.
- **`RETURN`** Specifies an expression to return instead of the record and what to do with the result-set returned by the expression.  The available return operators are:
    - `COUNT` Returns the number of updated records.  This is the default return operator.
    - `AFTER` Return the records after the update.
- [`WHERE`](SQL-Where.md)
- `LIMIT` Defines the maximum number of records to update.
- `TIMEOUT` Defines the time you want to allow the update run before it times out.

>**NOTE**: The Record ID must have a `#` prefix.  For instance, `#12:3`.

**Examples**:

- Update to change the value of a field:

```sql
    UPDATE Profile SET nick = 'Andrii' WHERE nick IS NULL
```
  Updated 2 record(s) in 0.008000 sec(s).

- Update to remove a property from all records:

```sql
  UPDATE Profile REMOVE nick
```
  
- Update to remove a value from a collection, if you know the exact value that you want to remove:

  Remove an element from a link list or set:

```sql
    UPDATE Account REMOVE address = #12:0
```
  
  Remove an element from a list or set of strings:

```sql
  UPDATE Account REMOVE addresses = 'Foo'
```

- Update to remove a value, filtering on value attributes.

  Remove addresses based in the city of Kyiv:

```sql
  UPDATE Account REMOVE addresses = addresses[city = 'Kyiv']
```
  
- Update to remove a value, filtering based on position in the collection.

```sql
  UPDATE Account REMOVE addresses = addresses[1]
```
 
  This remove the second element from a list, (position numbers start from `0`, so `addresses[1]` is the second elelment).

- Update to remove a value from a map

```sql
   UPDATE Account REMOVE addresses = 'Andrii'
```

- Update the first twenty records that satisfy a condition:

```sql
   UPDATE Profile SET nick = 'Andrii' WHERE nick IS NULL LIMIT 20
```
  
- Update a record or insert if it doesn't already exist:

```sql
UPDATE Profile SET nick = 'Andrii' UPSERT WHERE nick = 'Andrii'
```

- Updates using the `RETURN` keyword:

```sql
   UPDATE ♯7:0 SET gender='male' RETURN AFTER @rid
   UPDATE ♯7:0 SET gender='male' RETURN AFTER @version
   UPDATE ♯7:0 SET gender='male' RETURN AFTER @this
   UPDATE ♯7:0 SET gender='male' RETURN AFTER $current.exclude("really_big_field")
```

In the event that a single property is returned, YouTrackDB wraps the result-set in a Map storing the value in under the key `result`. 

For more information on SQL syntax, see [`SELECT`](YQL-Query.md).

## Limitations of the `UPSERT` Clause

The `UPSERT` clause only guarantees atomicity when you use a `UNIQUE` index and perform the look-up on the index through the [`WHERE`](YQL-Where.md) condition.

```sql
  UPDATE Client SET id = 23 UPSERT WHERE id = 23
```

Here, you must have a unique index on `Client.id` to guarantee uniqueness on concurrent operations.
