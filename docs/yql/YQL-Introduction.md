# Introduction

When it comes to query languages, SQL is the most widely recognized standard. The majority of developers have experience and are comfortable with SQL. For this reason, YouTrackDB uses YQL as its query language and adds some extensions to enable graph functionality.
There are a few differences between the standard SQL syntax and that supported by YouTrackDB, but for the most part, it should feel very natural. The differences are covered in the [YouTrackDB YQL dialect](#youtrackdb-yql-dialect) section of this page.

If you are looking for the most efficient way to traverse a graph, we **strongly** suggest using the [MATCH statement](YQL-Match.md).

Many YQL commands share the [WHERE condition](YQL-Where.md). Keywords and class names in YouTrackDB YQL are case-insensitive. Field names and values are case-sensitive. In the following examples, keywords are in uppercase but this is not strictly required.


For example, if you have a class `MyClass` with a field named `id`, then the following YQL statements are equivalent:

```sql
SELECT FROM MyClass WHERE id = 1
select from myclass where id = 1
```

The following is NOT equivalent. Notice that the field name 'ID' is not the same as 'id'.

```sql
SELECT FROM MyClass WHERE ID = 1
```

## Automatic usage of indexes

YouTrackDB allows you to execute queries against any field, indexed or not-indexed. The YQL engine automatically recognizes if any indexes can be used to speed up execution.

## Extra resources
- [YQL syntax](YQL-Syntax.md)
- [YQL projections](YQL-Projections.md)
- [YQL conditions](YQL-Where.md)
 - [Where clause](YQL-Where.md)
 - [Operators](YQL-Where.md#operators)
 - [Functions](YQL-Where.md#functions)
- [MATCH statement](YQL-Match.md) for traversing graphs

## YouTrackDB YQL dialect

YouTrackDB supports YQL as a query language with some differences compared with SQL. 
To learn more, refer to [YouTrackDB YQL Syntax](YQL-Syntax.md).

## No JOINs
The most important difference between YouTrackDB and a Relational Database is that relationships are represented by `LINKS` and edges instead of JOINs.

For this reason, the classic JOIN syntax is not supported. YouTrackDB uses the "dot (`.`) notation" to navigate `LINKS`. Example 1: in SQL, you might create a join such as:
```sql
SELECT *
FROM Employee A, City B
WHERE A.city = B.id
AND B.name = 'Rome'
```
In YouTrackDB, an equivalent operation would be:
```sql
SELECT * FROM Employee WHERE city.name = 'Rome'
```
This is much more straightforward and powerful! If you use multiple JOINs, the YouTrackDB YQL equivalent will be an even larger benefit. Example 2: in SQL, you might create a join such as:
```sql
SELECT *
FROM Employee A, City B, Country C,
WHERE A.city = B.id
AND B.country = C.id
AND C.name = 'Italy'
```
In YouTrackDB, an equivalent operation would be:
```sql
SELECT * FROM Employee WHERE city.country.name = 'Italy'
```

## Projections
In SQL, projections are mandatory and you can use the star character `*` to include all of the fields. With YouTrackDB, this type of projection is optional. Example: In SQL to select all of the columns of Customer you would write:
```sql
SELECT * FROM Customer
```
In YouTrackDB, the `*` is optional:
```sql
SELECT FROM Customer
```

See [YQL projections](YQL-Projections.md).

## DISTINCT

In YouTrackDB you can use DISTINCT keyword exactly as in a relational database:
```sql
SELECT DISTINCT name FROM City
```

## HAVING

YouTrackDB does not support the `HAVING` keyword, but with a nested query it's easy to obtain the same result. Example in SQL:
```sql
SELECT city, sum(salary) AS salary
FROM Employee
GROUP BY city
HAVING salary > 1000
```

This groups all of the salaries by city and extracts the result of aggregates with the total salary greater than 1,000 dollars. In YouTrackDB the `HAVING` conditions go in a select statement in the predicate:

```sql
SELECT FROM ( SELECT city, SUM(salary) AS salary FROM Employee GROUP BY city ) WHERE salary > 1000
```

## Select from multiple targets

YouTrackDB allows only one class (classes are equivalent to tables in this discussion) as opposed to SQL, which allows for many tables as the target.  If you want to select from 2 classes, you have to execute 2 sub queries and join them with the `UNIONALL` function:
```sql
SELECT FROM E, V
```
In YouTrackDB, you can accomplish this with a few variable definitions and by using the `expand` function to the union:
```sql
SELECT EXPAND( $c ) LET $a = ( SELECT FROM E ), $b = ( SELECT FROM V ), $c = UNIONALL( $a, $b )
```
