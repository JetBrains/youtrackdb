# YQL Methods

YQL Methods are similar to [YQL functions](YQL-Functions.md) but they apply to values.
In the Object-Oriented paradigm, they are called "methods", as they are functions related to a class. So what's the difference between a function and a method?

This is a [YQL function](YQL-Functions.md):
```sql
SELECT sum(salary) FROM employee
```

This is a YQL method:
```sql
SELECT salary.asString() FROM employee
```

As you can see, the method is executed against a property/value. Methods can receive parameters, like functions. You can chain multiple methods in sequence.

>**Note**: methods are case-insensitive.

## Bundled methods

### Methods by category
| Conversions                               | String manipulation                         | Collections                             | Misc                                  |
|-------------------------------------------|---------------------------------------------|-----------------------------------------|---------------------------------------|
| [convert()](YQL-Methods.md#convert)       | [append()](YQL-Methods.md#append)           | [\[\]](YQL-Methods.md#operator)         | [exclude()](YQL-Methods.md#exclude)   |
| [asBoolean()](YQL-Methods.md#asboolean)   | [charAt()](YQL-Methods.md#charat)           | [size()](YQL-Methods.md#size)           | [include()](YQL-Methods.md#include)   |
| [asDate()](YQL-Methods.md#asdate)         | [indexOf()](YQL-Methods.md#indexof)         | [remove()](YQL-Methods.md#remove)       | [javaType()](YQL-Methods.md#javatype) |
| [asDatetime()](YQL-Methods.md#asdatetime) | [left()](YQL-Methods.md#left)               | [removeAll()](YQL-Methods.md#removeall) | [type()](YQL-Methods.md#type)         |
| [asDecimal()](YQL-Methods.md#asdecimal)   | [right()](YQL-Methods.md#right)             | [keys()](YQL-Methods.md#keys)           |                                       |
| [asFloat()](YQL-Methods.md#asfloat)       | [prefix()](YQL-Methods.md#prefix)           | [values()](YQL-Methods.md#values)       |
| [asInteger()](YQL-Methods.md#asinteger)   | [trim()](YQL-Methods.md#trim)               |
| [asList()](YQL-Methods.md#aslist)         | [replace()](YQL-Methods.md#replace)         |
| [asLong()](YQL-Methods.md#aslong)         | [length()](YQL-Methods.md#length)           |
| [asMap()](YQL-Methods.md#asmap)           | [subString()](YQL-Methods.md#substring)     |
| [asSet()](YQL-Methods.md#asset)           | [toLowerCase()](YQL-Methods.md#tolowercase) |
| [asString()](YQL-Methods.md#asstring)     | [toUpperCase()](YQL-Methods.md#touppercase) |
| [normalize()](YQL-Methods.md#normalize)   | [hash()](YQL-Methods.md#hash)               |
|                                           | [format()](YQL-Methods.md#format)           |


### Methods by name
|                                             |                                         |                                         |                                       |                                           |                                             |
|---------------------------------------------|-----------------------------------------|-----------------------------------------|---------------------------------------|-------------------------------------------|---------------------------------------------|
| [\[\]](YQL-Methods.md#operator)             | [append()](YQL-Methods.md#append)       | [asBoolean()](YQL-Methods.md#asboolean) | [asDate()](YQL-Methods.md#asdate)     | [asDatetime()](YQL-Methods.md#asdatetime) | [asDecimal()](YQL-Methods.md#asdecimal)     |
| [asFloat()](YQL-Methods.md#asfloat)         | [asInteger()](YQL-Methods.md#asinteger) | [asList()](YQL-Methods.md#aslist)       | [asLong()](YQL-Methods.md#aslong)     | [asMap()](YQL-Methods.md#asmap)           | [asSet()](YQL-Methods.md#asset)             |
| [asString()](YQL-Methods.md#asstring)       | [charAt()](YQL-Methods.md#charat)       | [convert()](YQL-Methods.md#convert)     | [exclude()](YQL-Methods.md#exclude)   | [format()](YQL-Methods.md#format)         | [hash()](YQL-Methods.md#hash)               |
| [include()](YQL-Methods.md#include)         | [indexOf()](YQL-Methods.md#indexof)     | [javaType()](YQL-Methods.md#javatype)   | [keys()](YQL-Methods.md#keys)         | [left()](YQL-Methods.md#left)             | [length()](YQL-Methods.md#length)           |
| [normalize()](YQL-Methods.md#normalize)     | [prefix()](YQL-Methods.md#prefix)       | [remove()](YQL-Methods.md#remove)       | [removeAll()](YQL-Methods.md#removeall) | [replace()](YQL-Methods.md#replace)     | [right()](YQL-Methods.md#right)             |
| [size()](YQL-Methods.md#size)               | [subString()](YQL-Methods.md#substring) | [toLowerCase()](YQL-Methods.md#tolowercase) | [toUpperCase()](YQL-Methods.md#touppercase) | [trim()](YQL-Methods.md#trim)       | [type()](YQL-Methods.md#type)               |
| [values()](YQL-Methods.md#values)           |

### `[]` {#operator}
Execute an expression against an item. An item can be a multi-value object like a map, a list, an array, or a document. 
For records and maps, the item must be a string. For lists and arrays, the index is a number.

Syntax: ```<value>[<expression>]```

Applies to the following types:
- record,
- map,
- list,
- array

#### Examples

Get the item with key "phone" in a map:
```sql
SELECT FROM Profile WHERE '+39' IN contacts[phone].left(3)
```

Get the first 10 tags of posts:
```sql
SELECT tags[0-9] FROM Posts
```

---

### .append()
Appends a string to another one.

Syntax: ```<value>.append(<value>)```

Applies to the following types:
- string

#### Examples

```sql
SELECT name.append(' ').append(surname) FROM Employee
```

---

### .asBoolean()
Transforms the field into a Boolean type. If the origin type is a string, then "true" and "false" are checked. If it's a number, then 1 means TRUE while 0 means FALSE.

Syntax: ```<value>.asBoolean()```

Applies to the following types:
- string,
- short,
- int,
- long

#### Examples

```sql
SELECT FROM Users WHERE online.asBoolean() = true
```

---

### .asDate()
Transforms the field into a Date type.

Syntax: ```<value>.asDate()```

Applies to the following types:
- string,
- long

#### Examples

Time is stored as a long type measuring milliseconds since the Unix epoch. Returns all the records where the date is before the year 2010:

```sql
SELECT FROM Log WHERE time.asDate() < '01-01-2010'
```

---

### .asDateTime()
Transforms the field into a Date type, also parsing the time information.

Syntax: ```<value>.asDateTime()```

Applies to the following types:
- string,
- long

#### Examples

Time is stored as a long type measuring milliseconds since the Unix epoch. Returns all the records where the datetime is before the year 2010:

```sql
SELECT FROM Log WHERE time.asDateTime() < '01-01-2010 00:00:00'
```

---

### .asDecimal()
Transforms the field into a Decimal type. Use the Decimal type when handling currencies.

Syntax: ```<value>.asDecimal()```

Applies to the following types:
- any

#### Examples

```sql
SELECT salary.asDecimal() FROM Employee
```

---

### .asFloat()
Transforms the field into a float type.

Syntax: ```<value>.asFloat()```

Applies to the following types:
- any

#### Examples

```sql
SELECT FROM Instrument WHERE ray.asFloat() > 3.14
```

---

### .asInteger()
Transforms the field into an integer type.

Syntax: ```<value>.asInteger()```

Applies to the following types:
- any

#### Examples

Converts the first 3 characters of the 'value' field into an integer:
```sql
SELECT value.left(3).asInteger() FROM Log
```

---

### .asList()
Transforms the value into a List. If it's a single item, a new list is created.

Syntax: ```<value>.asList()```

Applies to the following types:
- any

#### Examples

```sql
SELECT tags.asList() FROM Friend
```

---

### .asLong()
Transforms the field into a Long type.

Syntax: ```<value>.asLong()```

Applies to the following types:
- any

#### Examples

```sql
SELECT date.asLong() FROM Log
```

---

### .asMap()
Transforms the value into a Map where even items are the keys and odd items are values.

Syntax: ```<value>.asMap()```

Applies to the following types:
- collections

#### Examples

```sql
SELECT tags.asMap() FROM Friend
```

---

### .asSet()
Transforms the value into a Set. If it's a single item, a new set is created. Sets don't allow duplicates.

Syntax: ```<value>.asSet()```

Applies to the following types:
- any

#### Examples

```sql
SELECT tags.asSet() FROM Friend
```

---

### .asString()
Transforms the field into a string type.

Syntax: ```<value>.asString()```

Applies to the following types:
- any

#### Examples

Get all the employees with decimal salaries:
```sql
SELECT FROM Employee WHERE salary.asString().indexOf('.') > -1
```

---

### .charAt()
Returns the character of the string contained in the position 'position'. 'position' starts from 0 to string length.

Syntax: ```<value>.charAt(<position>)```

Applies to the following types:
- string

#### Examples

Get the first character of the users' name:
```sql
SELECT FROM User WHERE name.charAt( 0 ) = 'L'
```

---

### .convert()
Convert a value to another type.

Syntax: ```<value>.convert(<type>)```

Applies to the following types:
- any

#### Examples

```sql
SELECT dob.convert( 'date' ) FROM User
```

---

### .exclude()
Excludes some properties in the resulting Map.

Syntax: ```<value>.exclude(<property-name>[,]*)```

Applies to the following types:
- record

#### Examples

```sql
SELECT EXPAND( @this.exclude( 'password' ) ) FROM User
```


Example to exclude all the outgoing and incoming edges:

```sql
SELECT EXPAND( @this.exclude( 'out_*', 'in_*' ) ) FROM V
```

---

### .format()
Returns the value formatted using the common "printf" syntax.
For the complete reference, see the [Java Formatter JavaDoc](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Formatter.html#syntax).

Syntax: ```<value>.format(<format>)```

Applies to the following types:
- any

#### Examples
Formats salaries as number with 11 digits filling with 0 at left:

```sql
SELECT salary.format("%011d") FROM Employee
```

---

### .hash()

Returns the hash of the field. Supports all the algorithms available in the JVM.

Syntax: ```<value>.hash([<algorithm>])```

Applies to the following types:
- string

#### Example

Get the SHA-512 of the field "password" in the class User:

```sql
SELECT password.hash('SHA-512') FROM User
```

---

### .include()
Include only some properties in the resulting Map.

Syntax: ```<value>.include(<property-name>[,]*)```

Applies to the following types:
- record

#### Examples

```sql
SELECT EXPAND( @this.include( 'name' ) ) FROM User
```

Example to include only the `name` and `email` fields:

```sql
SELECT EXPAND( @this.include( 'name', 'email' ) ) FROM User
```

---

### .indexOf()
Returns the position of the 'string-to-search' inside the value.
It returns -1 if no occurrences are found. 'begin-position' is the optional position where to start, otherwise the beginning of the string is taken (=0).

Syntax: ```<value>.indexOf(<string-to-search> [, <begin-position>])```

Applies to the following types:
- string

#### Examples
Returns all the UK numbers:
```sql
SELECT FROM Contact WHERE phone.indexOf('+44') > -1
```

---

### .javaType()
Returns the corresponding Java Type.

Syntax: ```<value>.javaType()```

Applies to the following types:
- any

#### Examples
Prints the Java type used to store dates:
```sql
SELECT date.javaType() FROM Events
```

---

### .keys()
Returns the map's keys as a separate set. Useful in conjunction with the IN, CONTAINS, and CONTAINSALL operators.

Syntax: ```<value>.keys()```

Applies to the following types:
- maps,
- records

#### Examples
```sql
SELECT FROM Actor WHERE 'Luke' IN map.keys()
```

---

### .left()
Returns a substring from the beginning of the string, taking 'length' characters.

Syntax: ```<value>.left(<length>)```

Applies to the following types:
- string

#### Examples
```sql
SELECT FROM Actors WHERE name.left( 4 ) = 'Luke'
```

---

### .length()
Returns the length of the string. If the string is null 0 will be returned.

Syntax: ```<value>.length()```

Applies to the following types:
- string

#### Examples
```sql
SELECT FROM Providers WHERE name.length() > 0
```

---


### .normalize()
Form can be NFC, NFD, NFKC, or NFKD. Default is NFC. The pattern-matching parameter, if not defined, defaults to `\\p{InCombiningDiacriticalMarks}+`.
For more information, refer to the [Unicode Standard](http://www.unicode.org/reports/tr15/tr15-23.html).

Syntax: ```<value>.normalize( [<form>] [,<pattern-matching>] )```

Applies to the following types:
- string

#### Examples
```sql
SELECT FROM V WHERE name.normalize() AND name.normalize('NFD')
```

---

### .prefix()
Prefixes a string to another one.

Syntax: ```<value>.prefix('<string>')```

Applies to the following types:
- string

#### Examples
```sql
SELECT name.prefix('Mr. ') FROM Profile
```

---

### .remove()
Removes the first occurrence of the passed items.

Syntax: ```<value>.remove(<item>*)```

Applies to the following types:
- collection

#### Examples

```sql
SELECT out().in().remove( @this ) FROM V
```

---

### .removeAll()
Removes all the occurrences of the passed items.

Syntax: ```<value>.removeAll(<item>*)```

Applies to the following types:
- collection

#### Examples

```sql
SELECT out().in().removeAll( @this ) FROM V
```

---

### .replace()
Replace a string with another one.

Syntax: ```<value>.replace(<to-find>, <to-replace>)```

Applies to the following types:
- string

#### Examples

```sql
SELECT name.replace('Mr.', 'Ms.') FROM User
```

---

### .right()
Returns a substring of 'length' characters from the end of the string.

Syntax: ```<value>.right(<length>)```

Applies to the following types:
- string

#### Examples

Returns all the vertices where the name ends by "ke".
```sql
SELECT FROM V WHERE name.right( 2 ) = 'ke'
```

---

### .size()
Returns the size of the collection.

Syntax: ```<value>.size()```

Applies to the following types:
- collection

#### Examples

Returns all the items in a tree with children:
```sql
SELECT FROM TreeItem WHERE children.size() > 0
```

---

### .subString()
Returns a substring starting from 'begin' index up to 'end' index (not included).

Syntax: ```<value>.subString(<begin> [,<end>] )```

Applies to the following types:
- string

#### Examples

Get all the items where the name begins with an "L":
```sql
SELECT name.substring( 0, 1 ) = 'L' FROM StockItems
```

Substring of `YouTrackDB`
```sql
SELECT "YouTrackDB".substring(0,8)
```
returns `YouTrack`

---

### .trim()
Returns the original string with whitespace removed from the beginning and the end.

Syntax: ```<value>.trim()```

Applies to the following types:
- string

#### Examples
```sql
SELECT name.trim() == 'Luke' FROM Actors
```

---

### .toLowerCase()
Returns the string in lower case.

Syntax: ```<value>.toLowerCase()```

Applies to the following types:
- string

#### Examples
```sql
SELECT name.toLowerCase() == 'luke' FROM Actors
```

---

### .toUpperCase()
Returns the string in upper case.

Syntax: ```<value>.toUpperCase()```

Applies to the following types:
- string

#### Examples
```sql
SELECT name.toUpperCase() == 'LUKE' FROM Actors
```

---

### .type()
Returns the value's YouTrackDB Type.

Syntax: ```<value>.type()```

Applies to the following types:
- any

#### Examples
Prints the type used to store dates:
```sql
SELECT date.type() FROM Events
```

---

### .values()
Returns the map's values as a separate collection. Useful in conjunction with the IN, CONTAINS, and CONTAINSALL operators.

Syntax: ```<value>.values()```

Applies to the following types:
- maps,
- records


#### Examples
```sql
SELECT FROM Clients WHERE map.values() CONTAINSALL ( name is not null)
```
---