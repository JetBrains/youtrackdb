# YQL - `CREATE VERTEX`

Creates a new vertex in the database.

Vertices and edges are the main components of a graph database. YouTrackDB supports polymorphism on vertices.
The base class for a vertex is `V`.

**Syntax**

```sql
CREATE VERTEX [<class>] [SET <property> = <expression>[,]*] [CONTENT <json>]
```

- **`<class>`** Defines the class to which the vertex belongs.
- **`<property>`** Defines the property you want to set.
- **`<expression>`** Defines the expression to set for the property.
- **`<json>`** Defines a JSON document to set as the vertex content.


**Examples**
- Create a new vertex on the base class `V`:

```sql
   CREATE VERTEX
```
  

- Create a new vertex class, then create a vertex in that class:

```sql
   CREATE CLASS V1 EXTENDS V
   CREATE VERTEX V1
```  

- Create a new vertex, defining its properties:

```sql
   CREATE VERTEX SET brand = 'fiat'
```

- Create a new vertex of the class `V1`, defining its properties:

```sql
   CREATE VERTEX V1 SET brand = 'Skoda', name = 'wow'
```

- Create a vertex using JSON content:

```sql
   CREATE CLASS Employee IF NOT EXISTS EXTENDS V
   CREATE VERTEX Employee CONTENT { "name" : "Viktoria", "surname" : "Sernevich" }
```
  

For more information, see:

- [`CREATE EDGE`](YQL-Create-Edge.md)
- [YQL Commands](YQL-Commands.md)