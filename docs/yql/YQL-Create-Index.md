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
MATCH
  {class: File, as: out}
  .outE('Has'){where: (started >= '2014-01-01 00:00:00.000'
    AND ended < '2015-01-01 00:00:00.000')}
  .inV(){class: File, as: in}
RETURN out
```
- Using the above index, retrieve all the 2014 edges and return the children files:

```sql
MATCH
  {class: File, as: out}
  .outE('Has'){where: (started >= '2014-01-01 00:00:00.000'
    AND ended < '2015-01-01 00:00:00.000')}
  .inV(){class: File, as: in}
RETURN in
```

- Create an index that includes null values.

  By default, indexes ignore null values. Queries against null values that use an index return no entries. To index null values, set `{ ignoreNullValues: false }` as metadata.

```sql
CREATE INDEX addresses ON Employee (address) NOTUNIQUE METADATA { ignoreNullValues : false }
```

## Key size and shortened keys

An index key is the value stored in an index to find a record.
The `BTREE_MAX_KEY_SIZE` setting limits its size in bytes.
The default is 30% of the database page size.
The limit is recorded with the database when the database is created.

A single-field index indexes one field.
If its string or binary key is too large, the index stores a fixed prefix instead.
A prefix is the beginning of a value.

The prefix byte budget, called P here, comes from the recorded limit.
P is the recorded limit minus a fixed overhead.
For string indexes, the overhead is 20 bytes for unique indexes and 24 bytes for non-unique indexes.
For binary indexes, it is 22 bytes for unique indexes and 26 bytes for non-unique indexes.

String size is the UTF-8 byte length after collation.
Collation is the rule that converts values for index comparison.

String prefixes always end at a complete character.
The original value in the record does not change.
The database logs one warning per index when it first shortens a key while open.

A composite index indexes more than one field.
Composite keys are not shortened.
Other value types, such as numbers, dates and links, are not shortened.
An oversized key in these cases throws `TooBigIndexKeyException`.
A database with no positive recorded limit does not shorten keys.
It also throws `TooBigIndexKeyException` for oversized keys.

Shortened keys have these limits:

- Results ordered by the index can be in the wrong order when values share a prefix.
  This also affects `ORDER BY` with `LIMIT`.
- Counts answered by the index alone can include extra entries.
- Direct index calls return the stored prefix, not the original value.
  They can also return extra entries.
- In direct index calls, an exclusive lower range bound of at least about P minus 3 bytes
  becomes inclusive.
  It can return an entry equal to the bound.
  An exclusive bound normally excludes the bound itself.
- A unique index rejects two different values that share a stored prefix.
  It throws `RecordDuplicatedException`, even though the full values differ.
  Unique indexes always reject duplicate keys, regardless of `mergeKeys` metadata.
- Normal Structured Query Language (SQL) equality and range filters stay exact.
  The query checks the full value in each record again.

Keep values within P when exact index ordering or index-only counts matter.
For example, a non-unique string index with a recorded limit of 100 bytes has P = 76 bytes.
Two different 77-byte strings can share one stored prefix even though both fit within 100 bytes.

> For more information, see
>- [`DROP INDEX`](YQL-Drop-Index.md)
>- [YQL Commands](YQL-Commands.md)
