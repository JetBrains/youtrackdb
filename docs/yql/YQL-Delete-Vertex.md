# YQL - `DELETE VERTEX`

Removes vertices from the database.

**Syntax**

```sql
DELETE VERTEX <vertex> [WHERE <conditions>] [LIMIT <MaxRecords>>] [BATCH <batch-size>]
```

- **`<vertex>`** Defines the vertex that you want to remove, using its Class, Record ID, or through a sub-query using the `FROM (<sub-query)` clause.
- **[`WHERE`](YQL-Where.md)** Filter condition to determine which records the command removes.
- **`LIMIT`** Defines the maximum number of records to remove.
- **`BATCH`** Defines how many records the command removes at a time, allowing you to break large transactions into smaller blocks to save on memory usage.  By default, it operates on blocks of 100.


**Example**

- Remove the vertex and disconnect all vertices that point towards it:


```sql
   DELETE VERTEX #10:231
```
  orientdb> <code class="lang-sql userinput">DELETE VERTEX #10:231</code>
  </pre>

- Remove all user accounts marked with an incoming edge on the class `BadBehaviorInForum`:

  <pre>
  orientdb> <code class='lang-sql userinput'>DELETE VERTEX Account WHERE in.@Class CONTAINS 
            'BadBehaviorInForum'</code>
  </pre>

- Remove all vertices from the class `EmailMessages` marked with the property `isSpam`:

  <pre>
  orientdb> <code class="lang-sql userinput">DELETE VERTEX EMailMessage WHERE isSpam = TRUE</code>
  </pre>

- Remove vertices of the class `Attachment`, where the vertex has an edge of the class `HasAttachment` where the property `date` is set before 1990 and the vertex `Email` connected to class `Attachment` with the condition that its property `from` is set to `bob@example.com`:

  <pre>
  orientdb> <code class="lang-sql userinput">DELETE VERTEX Attachment WHERE in[@Class = 'HasAttachment'].date 
            &lt;= "1990" AND in.out[@Class = "Email"].from = 'some...@example.com'</code>
  </pre>


- Remove vertices in blocks of one thousand:

  <pre>
  orientdb> <code class="lang-sql userinput">DELETE VERTEX v BATCH 1000</code>
  </pre>

  This feature was introduced in version 2.1.


## Quick deletion of an entire class

In the case you want to delete one or more classes of vertices and all the connected edges resides only on particular classes, you could use the `TRUNCATE CLASS` command against both vertex and edge classes by specifying the `UNSAFE` keyword. `TRUNCATE CLASS` is much faster than `DELETE VERTEX`, because it doesn't take in consideration the removal of the edges. Use `TRUNCATE CLASS` only when you are certain that there will not be broken edges on other vertices instances. Example of deleting all the instances of vertices classes `Email` and `Attachment` and the edge class that connect them `HasAttachment`:

  <pre>
  orientdb> <code class="lang-sql userinput">TRUNCATE CLASS Email UNSAFE</code>
  orientdb> <code class="lang-sql userinput">TRUNCATE CLASS HasAttachment UNSAFE</code>
  orientdb> <code class="lang-sql userinput">TRUNCATE CLASS Attachment UNSAFE</code>
  </pre>

## History

### Version 2.1

- Introduces the optional `BATCH` clause for managing batch size on the operation.


### Version 1.4

- Command begins using the Blueprints API.  When working in Java using the OGraphDatabase API, you may experience differences in how the database manages edges.  To force the command to work with the older API, change the Graph DB settings using [`ALTER DATABASE`](SQL-Alter-Database.md).

### Version 1.1

- Initial version.
