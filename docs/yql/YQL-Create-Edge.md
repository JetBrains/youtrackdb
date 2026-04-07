# YQL - `CREATE EDGE`

Creates a new edge in the database.

**Syntax**

```sql
CREATE EDGE [<class>] [UPSERT] FROM <rid>|(<query>)|[<rid>]* TO <rid>|(<query>)|[<rid>]*
                      [SET <property> = <expression>[,]*]
                      [BATCH <batch-size>]
```

- **`<class>`** Defines the class name for the edge.  Use the default edge class `E` if you don't want to use subtypes.
- **`UPSERT`** allows skipping the creation of edges that already exist between two vertices (i.e., a unique edge for a pair of vertices).
This works only if the edge class has a UNIQUE index on `out, in` fields; otherwise, the statement fails.
- **`BATCH`** Defines the size of the batches when breaking the command into smaller blocks.
This helps avoid memory issues when the number of vertices is too high.  By default, it is set to `100`.

Edges and vertices form the main components of a graph database.  YouTrackDB supports polymorphism on edges.
The base class for an edge is `E`.

**Examples**

- Create an edge of the class `E` between two vertices:

```sql
   CREATE EDGE FROM #10:3 TO #11:4
```
  
- Create a new edge type and an edge of the new type:

```sql
   CREATE CLASS E1 EXTENDS E
   CREATE EDGE E1 FROM #10:3 TO #11:4
```
  

- Create an edge and define its properties:

```sql
   CREATE EDGE FROM #10:3 TO #11:4 SET brand = 'Skoda'
```
  

- Create an edge of the type `E1` and define its properties:

```sql
   CREATE EDGE E1 FROM #10:3 TO #11:4 SET brand = 'Skoda', name = 'wow'
```
  
- Create edges of the type `Watched` between all action movies in the database and the user Andrii, using subqueries:


```sql
   CREATE EDGE Watched FROM (SELECT FROM account WHERE name = 'Andrii') TO (SELECT FROM movies WHERE type.name = 'action')
```
  
>For more information, see
>
>- [`CREATE VERTEX`](YQL-Create-Vertex.md)

