# YQL - Filtering

The `WHERE` condition is shared among many YQL commands.

## Syntax

`[<item>] <operator> <item>`

## Items

An `item` can be:

| **What**                            | **Description**                                                                                              |**Example**|
|-------------------------------------|--------------------------------------------------------------------------------------------------------------|-----------|
| property                            | Record property                                                                                              |where *price* > 1000000|
| property&lt;indexes&gt;             | Record property access by index or key.                                                                      |where tags[name='Hi'] or tags[0-3] IN ('Hello') and employees IS NOT NULL|
| record attribute                    | Record attribute name with @ as prefix                                                                       |where *@class* = 'Profile'|
| any()                               | Represents any property of the record. The condition is true if ANY of the properties matches the condition. |where *any()* like 'L%'|
| all()                               | Represents all the properties of the record. The condition is true if ALL the properties match the condition.|where *all()* is null|
| [functions](YQL-Functions.md)       | Any [function](YQL-Functions.md) among the available ones                                                    |where distance(x, y, 52.20472, 0.14056 ) <= 30|
| [$variable](YQL-Where.md#variables) | Context variable prefixed with $                                                                             |where $depth <= 3|


### Record attributes


|Name| Description                                                                                                                                                                                                                                                                                                                              |Example|
|--------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------|
|@this| returns the record itself                                                                                                                                                                                                                                                                                                               |`select @this.toJSON() from Account`|
|@rid| returns the Record ID in the form #&lt;collection:position&gt;. *NOTE: using @rid in a WHERE condition slows down queries. It is much better to use the Record ID as the target. For example, instead of `SELECT FROM Profile WHERE @rid = #10:44`, use `SELECT FROM #10:44`.* |**@rid** = #11:0|
|@class| returns the class name                                                                                                                                                                                                                                                      |**@class** = 'Profile'|
|@version| returns the record version as integer. Version starts from 0. Can't be null                                                                                                                                                                                                                                                              |**@version** > 0|

## Operators

### Conditional Operators

|Apply to|Operator| Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                | Example                                                                           |
|--------|---------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------|
|any|=| Equal to                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   | name **=** 'Luke'                                                                 |
|string|like| Similar to equals, but allows the wildcard '%' that means 'any'                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            | name **like** 'Luk%'                                                              |
|any|<| Less than                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  | age **<** 40                                                                      |
|any|<=| Less than or equal to                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      | age **<=** 40                                                                     |
|any|>| Greater than                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | age **>** 40                                                                      |
|any|>=| Greater than or equal to                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   | age **>=** 40                                                                     |
|any|<>| Not equals (same of !=)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    | age **<>** 40                                                                     |
|any|BETWEEN| The value is between a range. It's equivalent to &lt;field&gt; &gt;= &lt;from-value&gt; AND &lt;field&gt; &lt;= &lt;to-value&gt;                                                                                                                                                                                                                                                                                                                                                                                                                                           | price BETWEEN 10 and 30                                                           |
|any|IS| Used to test if a value is NULL                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            | children **is** null                                                              |
|record, string (as class name)|INSTANCEOF| Used to check if the record extends a class                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                | @this **instanceof** 'Customer' or @class **instanceof** 'Provider'               |
|collection|IN| Contains any of the elements listed                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        | name **in** ['European','Asiatic']                                                |
|collection|CONTAINS| True if the collection contains at least one element that satisfies the next condition. Condition can be a single item: in this case the behavior is like the IN operator                                                                                                                                                                                                                                                                                                                                                                                                   | children **contains** (name = 'Luke') - map.values() **contains** (name = 'Luke') |
|collection|CONTAINSALL| True if all the elements of the collection satisfy the next condition                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      | children *containsAll* (name = 'Luke')                                            |
|collection|CONTAINSANY| True if any of the elements of the collection satisfy the next condition                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      | children *containsAny* (name = 'Luke')                                            |
|map|CONTAINSKEY| True if the map contains at least one key equal to the requested. You can also use map.keys() CONTAINS in place of it                                                                                                                                                                                                                                                                                                                                                                                                                                                     | connections *containsKey* 'Luke'                                                  |
|map|CONTAINSVALUE| True if the map contains at least one value equal to the requested. You can also use map.values() CONTAINS in place of it                                                                                                                                                                                                                                                                                                                                                                                                                                                 | connections *containsValue* `#10:3`                                                 |
|string|CONTAINSTEXT| Checks whether the string contains the given substring.                                                                                                                                                                                                                                                                                                                                                                                                                                                  | text *containsText* 'vika'                                                        |
|string|MATCHES| Matches the string using a [Regular Expression](https://www.regular-expressions.info/)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     | text matches '.*[A-Z0-9.%+-]+@[A-Z0-9.-]+[.][A-Z]{2,4}.*'                         |


### Logical Operators

|Operator|Description|Example|
|--------|---------------|-----------|
|AND|True if both the conditions are true|name = 'Luke' **and** surname like 'Sky%'|
|OR|True if at least one of the conditions is true|name = 'Luke' **or** surname like 'Sky%'|
|NOT|True if the condition is false. NOT requires parentheses around the condition to negate.|**not** ( name = 'Luke')|


### Mathematics Operators


|Apply to|Operator       |Description|Example            |
|--------|---------------|-----------|-------------------|
|Numbers|+|Plus|age + 34|
|Numbers|-|Minus|salary - 34|
|Numbers|\*|Multiply|factor \* 1.3|
|Numbers|/|Divide|total / 12|
|Numbers|%|Mod|total % 3|

YouTrackDB supports the `eval()` function to execute complex operations. Example:
```sql
select eval( "amount * 120 / 100 - discount" ) as finalPrice from Order
```

### Methods

Also called "Property Operators", these are [documented on a separate page](YQL-Methods.md).

## Functions

All the [YQL functions are documented on a separate page](YQL-Functions.md).

## Variables

YouTrackDB supports variables managed in the context of the command/query. 
By default, some variables are created. Below is the table with the available variables:

|Name    |Description    | Command(s)             |
|--------|---------------|------------------------|
|$parent|Get the parent context from a sub-query. Example: `select from V let $type = ( traverse * from $parent.$current.children )`| [SELECT](YQL-Query.md) |
|$current|Current record to use in sub-queries to refer from the parent's variable| [SELECT](YQL-Query.md) |

To set custom variables, use the [LET](YQL-Query.md#let-block) keyword.