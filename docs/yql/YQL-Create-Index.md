# YQL - `CREATE INDEX`

Creates a new index. Indexes can be:
- **Unique**: Does not allow duplicates.
- **Not Unique**: Allows duplicates.


**Syntax**

```sql
CREATE INDEX <name>
[ IF NOT EXISTS ]
[ ON <class> (<property>[, <property>]*) ]
<index-type>
[ ENGINE <engine> ]
[ METADATA {<json>} ]
```
- **`<name>`** Defines the logical name for the index.
- **`IF NOT EXISTS`** If specified, the command is silently ignored when the index already exists (instead of failing with an error).
- **`<class>`** Defines the class to create an automatic index for. The class must already exist.
- **`<property>`** Defines the property you want to automatically index. The property must already exist.
- **`ENGINE`** Defines the index engine to use.
- **`METADATA`** Defines additional metadata through JSON.

>If the property is one of the Map types, such as `LINKMAP` or `EMBEDDEDMAP`, you can specify the keys or values to use in index generation, using the `BY KEY` or `BY VALUE` clauses.

- **`<index-type>`** Defines the index type you want to use: `UNIQUE`, `NOTUNIQUE`, `FULLTEXT`, or `SPATIAL`.

To create an automatic index bound to the schema property, use the `ON` clause.
To create an index, the schema must already exist in your database.

**Examples**

- Create an automatic index bound to the new property `id` in the class `User`:

```sql
CREATE PROPERTY User.id INTEGER
CREATE INDEX User.id UNIQUE
```

- Create a series of automatic indexes for the `thumbs` property in the class `Movie`:
```sql
CREATE INDEX thumbsAuthor ON Movie (thumbs) UNIQUE
CREATE INDEX thumbsKey ON Movie (thumbs BY KEY) UNIQUE
CREATE INDEX thumbsValue ON Movie (thumbs BY VALUE) UNIQUE
```

- Create a series of properties and on them create a composite index:

```sql
CREATE PROPERTY Book.author STRING
CREATE PROPERTY Book.title STRING
CREATE PROPERTY Book.publicationYears EMBEDDEDLIST INTEGER
CREATE INDEX books ON Book (author, title, publicationYears) UNIQUE
```


- Create an index on an edge's date range:
```sql
CREATE CLASS File EXTENDS V
CREATE CLASS Has EXTENDS E
CREATE PROPERTY Has.started DATETIME
CREATE PROPERTY Has.ended DATETIME
CREATE INDEX Has.started_ended ON Has (started, ended) NOTUNIQUE
```

>Indexes on edge classes are commonly used with historical graphs to store the begin and end date range of validity, as in the example above.

- Using the above index, retrieve all the edges that existed in the year 2014:

```sql
SELECT FROM Has WHERE started >= '2014-01-01 00:00:00.000' AND ended < '2015-01-01 00:00:00.000'
```

- Using the above index, retrieve all edges that existed in 2014 and return the parent file:

```sql
MATCH {class: File, as: out}.outE('Has'){where: (started >= '2014-01-01 00:00:00.000' AND ended < '2015-01-01 00:00:00.000')}.inV(){class: File, as: in}
  RETURN out
```
- Using the above index, retrieve all the 2014 edges and return the children files:

```sql
MATCH {class: File, as: out}.outE('Has'){where: (started >= '2014-01-01 00:00:00.000' AND ended < '2015-01-01 00:00:00.000')}.inV(){class: File, as: in}
  RETURN in
```

- Create an index that includes null values.

  By default, indexes ignore null values. Queries against null values that use an index return no entries. To index null values, set `{ ignoreNullValues: false }` as metadata.

```sql
CREATE INDEX addresses ON Employee (address) NOTUNIQUE METADATA { ignoreNullValues : false }
```

> For more information, see
>- [`DROP INDEX`](YQL-Drop-Index.md)
>- [YQL Commands](YQL-Commands.md)
