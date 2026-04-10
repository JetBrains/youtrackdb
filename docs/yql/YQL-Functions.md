# YQL - Functions

## Bundled functions

### Functions by category

| Graph                                            | Math                                        | Collections                                                          | Misc                                              |
|--------------------------------------------------|---------------------------------------------|----------------------------------------------------------------------|----------------------------------------------------|
| [out()](YQL-Functions.md#out)                    | [eval()](YQL-Functions.md#eval)             | [set()](YQL-Functions.md#set)                                        | [date()](YQL-Functions.md#date)                    |
| [in()](YQL-Functions.md#in)                      | [min()](YQL-Functions.md#min)               | [map()](YQL-Functions.md#map)                                        | [sysdate()](YQL-Functions.md#sysdate)              |
| [both()](YQL-Functions.md#both)                  | [max()](YQL-Functions.md#max)               | [list()](YQL-Functions.md#list)                                      | [format()](YQL-Functions.md#format)                |
| [outE()](YQL-Functions.md#oute)                  | [sum()](YQL-Functions.md#sum)               | [difference()](YQL-Functions.md#difference)                          | [distance()](YQL-Functions.md#distance)            |
| [inE()](YQL-Functions.md#ine)                    | [abs()](YQL-Functions.md#abs)               | [first()](YQL-Functions.md#first)                                    | [ifnull()](YQL-Functions.md#ifnull)                |
| [bothE()](YQL-Functions.md#bothe)                | [decimal()](YQL-Functions.md#decimal)       | [intersect()](YQL-Functions.md#intersect)                            | [coalesce()](YQL-Functions.md#coalesce)            |
| [outV()](YQL-Functions.md#outv)                  | [avg()](YQL-Functions.md#avg)               | [distinct()](YQL-Functions.md#distinct)                              | [uuid()](YQL-Functions.md#uuid)                    |
| [inV()](YQL-Functions.md#inv)                    | [count()](YQL-Functions.md#count)           | [expand()](YQL-Functions.md#expand)                                  | [if()](YQL-Functions.md#if)                        |
| [shortestPath()](YQL-Functions.md#shortestpath)  | [mode()](YQL-Functions.md#mode)             | [unionall()](YQL-Functions.md#unionall)                              | [strcmpci()](YQL-Functions.md#strcmpci)            |
| [dijkstra()](YQL-Functions.md#dijkstra)          | [median()](YQL-Functions.md#median)         | [last()](YQL-Functions.md#last)                                      |                                                    |
| [astar()](YQL-Functions.md#astar)                | [percentile()](YQL-Functions.md#percentile) | [symmetricDifference()](YQL-Functions.md#symmetricdifference)        |                                                    |
| [bothV()](YQL-Functions.md#bothv)                | [variance()](YQL-Functions.md#variance)     |                                                                      |                                                    |
|                                                  | [stddev()](YQL-Functions.md#stddev)         |                                                                      |                                                    |

YQL Functions are all the functions bundled with YouTrackDB SQL engine. See also [YQL Methods](YQL-Methods.md).

YQL Functions can work in 2 ways depending on whether they receive 1 or more parameters:

## Aggregated mode

When only one parameter is passed, the function aggregates the result in only one record. The classic example is the `sum()` function:
```sql
SELECT SUM(salary) FROM employee
```
This will always return one record: the sum of salary fields across every employee record.

## Inline mode

When two or more parameters are passed:
```sql
SELECT SUM(salary, extra, benefits) AS total FROM employee
```
This will return the sum of the field "salary", "extra" and "benefits" as "total".

If you need to use a function inline when you only have one parameter, add "null" as the second parameter:

```sql
SELECT sum(salary, null) AS totalSalary FROM Employee
```
In the above example, the `sum()` function doesn't aggregate everything into a single record, but rather returns one record per `Employee`,
where `totalSalary` is the value of the `salary` field for that record.

## Function Reference

### out()

Get the adjacent outgoing vertices starting from the current record as Vertex.

Syntax: ```out([<label-1>][,<label-n>]*)```

#### Example

Get all the outgoing vertices:
```sql
SELECT out() FROM V
```

Get all the outgoing vertices connected with edges with label (class) "Eats" and "Favorited" from all the Restaurant vertices in Kyiv:

```sql
SELECT out('Eats','Favorited') FROM Restaurant WHERE city = 'Kyiv'
```
---
### in()

Get the adjacent incoming vertices starting from the current record as Vertex.

Syntax:
```
in([<label-1>][,<label-n>]*)
```


#### Example

Get all the incoming vertices from all the V vertices:

```sql
SELECT in() FROM V
```

Get all the incoming vertices connected with edges with label (class) "Friend" and "Brother":
```sql
SELECT in('Friend','Brother') FROM V
```
---
### both()

Get the adjacent outgoing and incoming vertices starting from the current record as Vertex.

Syntax:
```
both([<label1>][,<label-n>]*)
```

#### Example

Get all the incoming and outgoing vertices from vertex with rid #13:33:

```sql
SELECT both() FROM #13:33
```

Get all the incoming and outgoing vertices connected by edges with label (class) "Friend" and "Brother":

```sql
SELECT both('Friend','Brother') FROM V
```
---
### outE()

Get the adjacent outgoing edges starting from the current record as Vertex.

Syntax:

```
outE([<label1>][,<label-n>]*)
```


#### Example

Get all the outgoing edges from all the vertices:
```sql
SELECT outE() FROM V
```

Get all the outgoing edges of type "Eats" from all the SocialNetworkProfile vertices:
```sql
SELECT outE('Eats') FROM SocialNetworkProfile
```
---
### inE()

Get the adjacent incoming edges starting from the current record as Vertex.

Syntax:
```
inE([<label1>][,<label-n>]*)
```

#### Example

Get all the incoming edges from all the vertices:

```sql
SELECT inE() FROM V
```

Get all the incoming edges of type "Eats" from the Restaurant 'Bella Napoli':
```sql
SELECT inE('Eats') FROM Restaurant WHERE name = 'Bella Napoli'
```
---
### bothE()

Get the adjacent outgoing and incoming edges starting from the current record as Vertex.

Syntax: ```bothE([<label1>][,<label-n>]*)```


#### Example

Get both incoming and outgoing edges from all the vertices:
```sql
SELECT bothE() FROM V
```

Get all the incoming and outgoing edges of type "Friend" from the Profiles with nickname 'Vika'

```sql
SELECT bothE('Friend') FROM Profile WHERE nickname = 'Vika'
```

---
### bothV()

Get the adjacent outgoing and incoming vertices starting from the current record as Edge.

Syntax: ```bothV()```


#### Example

Get both incoming and outgoing vertices from all the edges:
```sql
SELECT bothV() FROM E
```

### outV()

Get outgoing vertices starting from the current record as Edge.

Syntax:
```
outV()
```

#### Example

Get outgoing vertices from all edges
```sql
SELECT outV() FROM E
```

### inV()

Get incoming vertices starting from the current record as Edge.

Syntax:
```
inV()
```


#### Example

Get incoming vertices from all edges
```sql
SELECT inV() FROM E
```

### eval()

Syntax: ```eval('<expression>')```

Evaluates the expression between quotes (or double quotes).


#### Example

```sql
SELECT eval('price * 120 / 100 - discount') AS finalPrice FROM Order
```

### coalesce()

Returns the first property/value not null parameter. If no property/value is not null, returns null.

Syntax:
```
coalesce(<property|value> [, <property-n|value-n>]*)
```


#### Example

```sql
SELECT coalesce(amount, amount2, amount3) FROM Account
```

### if()

Syntax:
```
if(<expression>, <result-if-true>, <result-if-false>)
```

Evaluates a condition (first parameters) and returns the second parameter if the condition is true, and the third parameter otherwise.

#### Example:
```
SELECT if(eval("name = 'John'"), "My name is John", "My name is not John") FROM Person
```


### ifnull()

Returns the passed property/value (or optional parameter *return_value_if_not_null*) if property/value is not null, otherwise it returns *return_value_if_null*.

Syntax:
```sql
ifnull(<property/value>, <return_value_if_null>)
```

#### Example

```sql
SELECT ifnull(salary, 0) FROM Account
```

---
### expand()


This function has two meanings:

- When used on a collection property, it unwinds the collection in the field &lt;property&gt; and uses it as the result.
- When used on a link (RID) field, it expands the record pointed to by that link.

Syntax: ```expand(<property>)```

The preferred operator to unwind collections is [UNWIND](YQL-Query.md#unwinding). Expand usage for this use case will probably be deprecated in future releases.

#### Example

On collections:
```sql
SELECT EXPAND( addresses ) FROM Account
```

On RIDs:
```sql
SELECT EXPAND( address ) FROM Account
```

---
### first()

Retrieves only the first item of multi-value properties (arrays, collections and maps). For non multi-value types just returns the value.

Syntax: ```first(<field>)```


#### Example

```sql
SELECT first(addresses) FROM Account
```
---
### last()

Retrieves only the last item of multi-value properties (arrays, collections and maps). For non multi-value types just returns the value.

Syntax: ```last(<field>)```


#### Example

```sql
SELECT last( addresses ) FROM Account
```
---
### count()

Counts the records that match the query condition. If \* is not used as a property, then the record will be counted only if the property content is not null.

Syntax: ```count(<property>)```


#### Example

```sql
SELECT COUNT(*) FROM Account
```
---
### min()

Returns the minimum value. If invoked with more than one parameter, the function doesn't aggregate but returns the minimum value between all the arguments.

Syntax: ```min(<property> [, <property-n>]* )```


#### Example

Returns the minimum salary of all the Account records:
```sql
SELECT min(salary) FROM Account
```
Returns the minimum value between 'salary1', 'salary2' and 'salary3' fields.
```sql
SELECT min(salary1, salary2, salary3) FROM Account
```
---
### max()

Returns the maximum value. If invoked with more than one parameter, the function doesn't aggregate, but returns the maximum value between all the arguments.

Syntax: ```max(<property> [, <property-n>]* )```

#### Example

Returns the maximum salary of all the Account records:
```sql
SELECT max(salary) FROM Account
```

Returns the maximum value between 'salary1', 'salary2' and 'salary3' properties.
```sql
SELECT max(salary1, salary2, salary3) FROM Account
```

---
### abs()

Returns the absolute value. It works with Integer, Long, Short, Double, Float, BigInteger, BigDecimal, and null values.

Syntax: ```abs(<property>)```

#### Example

```sql
SELECT abs(score) FROM Account
SELECT abs(-2332) FROM Account
SELECT abs(999) FROM Account
```

---
### avg()

Returns the average value.

Syntax: ```avg(<property>)```

#### Example

```sql
SELECT avg(salary) FROM Account
```

---
### sum()

Syntax: ```sum(<property>)```

Returns the sum of all the values returned.

#### Example

```sql
SELECT sum(salary) FROM Account
```
---
### date()

Returns a date formatting a string. &lt;date-as-string&gt; is the date in string format, and &lt;format&gt; 
is the date format following these [rules](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/text/SimpleDateFormat.html). 
If no format is specified, then the default database format is used.

Syntax: ```date( <date-as-string> [, <format>] [, <timezone>] )```

#### Example

```sql
SELECT FROM Account WHERE created <= date('2012-07-02', 'yyyy-MM-dd')
```
---
### sysdate()

Returns the current date time. If executed with no parameters, it returns a Date object, otherwise a string with the requested format/timezone.

Syntax: ```sysdate( [<format>] [,<timezone>] )```

#### Example

```sql
SELECT sysdate('dd-MM-yyyy') FROM Account
```
---
### format()

Formats a value using the Java String.format() conventions. 

Syntax: ```format( <format> [,<arg1>] [,<arg-n>]*)```

#### Example

```sql
SELECT format("%d - Mr. %s %s (%s)", id, name, surname, address) FROM Account
```
---


### decimal()

Converts a number or a string to an absolute-precision decimal number.

Syntax: ```decimal( <number> | <string> )```

#### Example

```sql
SELECT decimal('99.999999999999999999') FROM Account
```
---


### astar()

The A* algorithm describes how to find the cheapest path from one node to another node in a directed weighted graph with a heuristic function.

The first parameter is the source record. The second parameter is the destination record. The third parameter is the name of the property that
represents 'weight', and the fourth represents the map of options.

If the property is not defined in the edge or is null, the distance between vertices is 0.

Syntax: ```astar(<sourceVertex>, <destinationVertex>, <weightEdgeFieldName>, [<options>]) ```

options:
```
{
  direction:"OUT", //the edge direction (OUT, IN, BOTH)
  edgeTypeNames:[],  
  vertexAxisNames:[], 
  tieBreaker:true,
  maxDepth:99999,
  dFactor:1.0,
  customHeuristicFormula:'custom_Function_Name_here'  // (MANHATTAN, MAXAXIS, DIAGONAL, EUCLIDEAN, EUCLIDEANNOSQR, CUSTOM)
}
```
#### Example

```sql
SELECT astar($current, #8:10, 'weight') FROM V
```
---
### dijkstra()

Returns the cheapest path between two vertices using the [Dijkstra algorithm](https://en.wikipedia.org/wiki/Dijkstra%27s_algorithm)
where the **weightEdgePropertyName** parameter is the property containing the weight. Direction can be OUT (default), IN or BOTH.

Syntax: ```dijkstra(<sourceVertex>, <destinationVertex>, <weightEdgePropertyName> [, <direction>])```

#### Example

```sql
SELECT dijkstra($current, #8:10, 'weight') FROM V
```
---
### shortestPath()

Returns the shortest path between two vertices. Direction can be OUT (default), IN or BOTH.


Syntax: ```shortestPath( <sourceVertex>, <destinationVertex> [, <direction> [, <edgeClassName> [, <additionalParams>]]])```

Where:
- `sourceVertex` is the source vertex where to start the path
- `destinationVertex` is the destination vertex where the path ends
- `direction`, optional, is the direction of traversing. By default is "BOTH" (in+out). Supported values are "BOTH" (incoming and outgoing), "OUT" (outgoing) and "IN" (incoming)
- `edgeClassName`, optional, is the edge class to traverse. By default all edges are crossed. This can also be a list of edge class names (eg. `["edgeType1", "edgeType2"]`)
- `additionalParams` optional, here you can pass a map of additional parameters (Map<String, Object> in Java, JSON from SQL). Currently allowed parameters are
    - 'maxDepth': integer, maximum depth for paths (ignore path longer that 'maxDepth')

#### Example on finding the shortest path between vertices #8:32 and #8:10

```sql
SELECT shortestPath(#8:32, #8:10)
```

#### Example on finding the shortest path between vertices #8:32 and #8:10 only crossing outgoing edges

```sql
SELECT shortestPath(#8:32, #8:10, 'OUT')
```

#### Example on finding the shortest path between vertices #8:32 and #8:10 only crossing incoming edges of type 'Friend'
```sql
SELECT shortestPath(#8:32, #8:10, 'IN', 'Friend')
```

#### Example on finding the shortest path between vertices #8:32 and #8:10 only crossing incoming edges of type 'Friend' or 'Colleague'
```sql
SELECT shortestPath(#8:32, #8:10, 'IN', ['Friend', 'Colleague'])
```

#### Example on finding the shortest path between vertices #8:32 and #8:10, long at most five hops

```sql
SELECT shortestPath(#8:32, #8:10, null, null, {"maxDepth": 5})
```


---
### distance()

Syntax: ```distance( <x-property>, <y-property>, <x-value>, <y-value> )```

Returns the distance between two points in the globe using the Haversine algorithm. Coordinates must be as degrees.


#### Example

```sql
SELECT FROM POI WHERE distance(x, y, 52.20472, 0.14056 ) <= 30
```
---
### distinct()

Syntax: ```distinct(<property>)```

Retrieves only unique data entries depending on the field you have specified as argument.
The main difference compared to standard SQL DISTINCT is that with YouTrackDB, a function with parentheses and only one field can be specified.

#### Example

```sql
SELECT distinct(name) FROM City
```
---
### unionall()

Syntax: ```unionall(<property> [,<property-n>]*)```

Works as aggregate or inline. 
If only one argument is passed then aggregates, otherwise executes and returns a UNION of all the collections received as parameters. Also works with no collection values.

#### Example

```sql
SELECT unionall(friends) FROM profile
```

```sql
SELECT unionall(inEdges, outEdges) FROM V WHERE label = 'test'
```
---
### intersect()

Syntax: ```intersect(<property> [,<property-n>]*)```

Works as aggregate or inline. If only one argument is passed then it aggregates, otherwise executes and returns the INTERSECTION of the collections received as parameters.

#### Example

```sql
SELECT intersect(friends) FROM profile WHERE jobTitle = 'programmer'
```

```sql
SELECT intersect(inEdges, outEdges) FROM V
```
---
### difference()

Syntax: ```difference(<field> [,<field-n>]*)```

Works in inline mode only. It requires two or more arguments and returns the DIFFERENCE between the collections received as parameters. Aggregation mode (single argument) is not supported.

#### Example

```sql
SELECT difference(inEdges, outEdges) FROM V
```
---

### symmetricDifference()

Syntax: ```symmetricDifference(<property> [,<property-n>]*)```

Works as aggregate or inline. If only one argument is passed then it aggregates, otherwise executes and returns the SYMMETRIC DIFFERENCE between the collections received as parameters.

#### Example

```sql
SELECT symmetricDifference(tags) FROM book
```

```sql
SELECT symmetricDifference(inEdges, outEdges) FROM V
```

---

### set()

Adds a value to a set, creating it on first use. If ```<value>``` is a collection, then it is merged with the set, otherwise ```<value>``` is added to the set.

Syntax: ```set(<property>)```


#### Example

```sql
SELECT name, set(roles.name) AS roles FROM User
```
---
### list()

Adds a value to a list, creating it on first use. If ```<value>``` is a collection, then it is merged with the list, otherwise ```<value>``` is added to the list.

Syntax: ```list(<property>)```

#### Example

```sql
SELECT name, list(roles.name) AS roles FROM User
```
---
### map()

Adds a value to a map, creating it on first use. If ```<value>``` is a map, then it is merged with the map,
otherwise the pair ```<key>``` and ```<value>``` is added to the map as a new entry.

Syntax: ```map(<key>, <value>)```


#### Example

```sql
SELECT map(name, roles.name) FROM User
```

---
### mode()

Returns the values that occur with the greatest frequency. Nulls are ignored in the calculation.

Syntax: ```mode(<property>)```

#### Example

```sql
SELECT mode(salary) FROM Account
```
---
### median()

Returns the middle value or an interpolated value that represent the middle value after the values are sorted. Nulls are ignored in the calculation.

Syntax: ```median(<property>)```

#### Example

```sql
SELECT median(salary) FROM Account
```
---
### percentile()

Returns the nth percentiles (the values that cut off the first n percent of the field values when it is sorted in ascending order). Nulls are ignored in the calculation.

Syntax: ```percentile(<property> [, <quantile-n>]*)```

The quantiles have to be in the range 0-1

#### Examples

```sql
SELECT percentile(salary, 0.95) FROM Account
SELECT percentile(salary, 0.25, 0.75) AS IQR FROM Account
```
---
### variance()

Returns the middle variance: the average of the squared differences from the mean. Nulls are ignored in the calculation.

Syntax: ```variance(<property>)```


#### Example

```sql
SELECT variance(salary) FROM Account
```
---
### stddev()

Returns the standard deviation: the measure of how spread out values are. Nulls are ignored in the calculation.

Syntax: ```stddev(<property>)```

#### Example

```sql
SELECT stddev(salary) FROM Account
```
---
### uuid()

Generates a UUID as a 128-bit value using the Leach-Salz variant.

Syntax: ```uuid()```

#### Example

Insert a new record with an automatic generated id:

```sql
INSERT INTO Account SET id = UUID()
```
---
### strcmpci()

Compares two strings ignoring case. Return value is -1 if first string ignoring case is less than second,
0 if strings ignoring case are equals, 1 if second string ignoring case is less than first one.
Before comparison both strings are transformed to lowercase and then compared.

Syntax: ```strcmpci(<first_string>, <second_string>)```

#### Example

Select all records where state name ignoring case is equal to "washington"

```sql
SELECT * from State where strcmpci("washington", name) = 0
```

---