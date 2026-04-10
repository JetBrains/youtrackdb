# YouTrackDB YQL syntax

YouTrackDB Query Language is an SQL dialect.

This page lists all the details about its syntax.

## Identifiers
An identifier is a name that identifies an entity in the YouTrackDB schema. Identifiers can refer to
- class names
- property names
- aliases
- function names
- method names
- named parameters
- variable names (LET)

An identifier is a sequence of characters starting with a letter and containing only numbers, letters, and underscores.

**Case sensitivity**

Keywords are case-insensitive. Class names, property names (fields), and values are case-sensitive.

## Reserved words

In YouTrackDB SQL, the following are reserved words:

- AFTER
- AND
- AS
- ASC
- BATCH
- BEFORE
- BETWEEN
- BREADTH_FIRST
- BY
- CLUSTER
- CONTAINS
- CONTAINSALL
- CONTAINSKEY
- CONTAINSTEXT
- CONTAINSVALUE
- CREATE
- DEFAULT
- DEFINED
- DELETE
- DEPTH_FIRST
- DESC
- DISTINCT
- EDGE
- FETCHPLAN
- FROM
- INCREMENT
- INSERT
- INSTANCEOF
- INTO
- IS
- LET
- LIKE
- LIMIT
- LOCK
- MATCH
- MATCHES
- MAXDEPTH
- NOCACHE
- NOT
- NULL
- OR
- PARALLEL
- POLYMORPHIC
- RETRY
- RETURN
- SELECT
- SKIP
- STRATEGY
- TIMEOUT
- TRAVERSE
- UNSAFE
- UNWIND
- UPDATE
- UPSERT
- VERTEX
- WAIT
- WHERE
- WHILE

## Base types

Accepted base types in YQL are:
- **integer numbers**:

Valid integers are:
```
(32bit)
1
12345678
-45

(64bit)
1L
12345678L
-45L
```

- **floating-point numbers**: single or double precision

Valid floating-point numbers are:
```
(single precision)
1.5
12345678.65432
-45.0

(double precision)
0.23D
.23D
```

- **absolute-precision decimal numbers**: like BigDecimal in Java

Use the `decimal(<number>)` function to explicitly instantiate an absolute-precision number.


- **strings**: delimited by `'` or by `"`. Single quotes, double quotes, and backslash inside strings can be escaped using a backslash.

Valid strings are:
```
"foo bar"
'foo bar'
"foo \" bar"
'foo \' bar'
'foo \\ bar'
```

- **booleans**: boolean value constants are case-insensitive.

Valid boolean values are:
```
true
false
```

`TRUE`, `True`, and so on are also valid.


- **links**: A link is a pointer to a document in the database

In YQL, a link is represented as follows:

```
#<collection-id>:<collection-position>
```

- **null**: case-insensitive (for consistency with IS NULL and IS NOT NULL conditions, which are case-insensitive)

Valid null expressions include:
```
NULL
null
Null
nUll
...
```

## Numbers

YouTrackDB can store five different types of numbers:
- Integer: 32bit signed
- Long: 64bit signed
- Float: decimal 32bit signed
- Double: decimal 64bit signed
- BigDecimal: absolute precision

**Integers** are represented in YQL as plain numbers, e.g. `123`. If the number represented exceeds the Integer maximum size (see Java `Integer.MAX_VALUE` and `Integer.MIN_VALUE`), then it's automatically converted to a Long. 

When an integer is saved to a schema-defined property of another numerical type, it is automatically converted. 

**Longs** are represented in YQL as numbers with `L` suffix, e.g. `123L` (L can be uppercase or lowercase). Plain numbers (without L suffix) that exceed the Integer range are also automatically converted to Long. If the number represented exceeds the Long maximum size (see Java `Long.MAX_VALUE` and `Long.MIN_VALUE`), then the result is `NULL`.

Integer and Long numbers can be represented in base 10 (decimal), 8 (octal) or 16 (hexadecimal):
- decimal: `["-"] ("0" | ( ("1"-"9") ("0"-"9")* ) ["l"|"L"]`, e.g. 
  - `15`, `15L`  
  - `-164` 
  - `999999999999`
- octal: `["-"] "0" ("0"-"7")+ ["l"|"L"]`, e.g. 
  - `01`, `01L` (equivalent to decimal 1) 
  - `010`, `010L` (equivalent to decimal 8)
  - `-065`, `-065L` (equivalent to decimal -53)
- hexadecimal: `["-"] "0" ("x"|"X") ("0"-"9","a"-"f","A"-"F")+ ["l"|"L"]`, e.g.
  - `0x1`, `0X1`, `0x1L` (equivalent to decimal 1)
  - `0x10` (equivalent to decimal 16)
  - `0xff`, `0xFF` (equivalent to decimal 255)
  - `-0xff`, `-0xFF` (equivalent to decimal -255)
  
**Float** numbers are represented in YQL as `[-][<number>].<number>`, e.g. valid Float values are `1.5`, `-1567.0`, `.556767`. If the number represented exceeds the Float maximum size (see Java `Float.MAX_VALUE` and `Float.MIN_VALUE`), then it's automatically converted to a Double. 

**Double** numbers are represented in YQL as `[-][<number>].<number>D` (D can be uppercase or lowercase), e.g. valid Double values are `1.5d`, `-1567.0D`, `.556767D`. If the number represented exceeds the Double maximum size (see Java `Double.MAX_VALUE` and `Double.MIN_VALUE`), then the result is `NULL`.


Float and Double numbers can be represented as decimal, decimal with exponent, hexadecimal, and hexadecimal with exponent.
Here is the full syntax:

```

FLOATING_POINT_LITERAL: ["-"] ( <DECIMAL_FLOATING_POINT_LITERAL> | <HEXADECIMAL_FLOATING_POINT_LITERAL> )

DECIMAL_FLOATING_POINT_LITERAL:
      (["0"-"9"])+ "." (["0"-"9"])* (<DECIMAL_EXPONENT>)? (["f","F","d","D"])?
      | "." (["0"-"9"])+ (<DECIMAL_EXPONENT>)? (["f","F","d","D"])?
      | (["0"-"9"])+ <DECIMAL_EXPONENT> (["f","F","d","D"])?
      | (["0"-"9"])+ (<DECIMAL_EXPONENT>)? ["f","F","d","D"]

DECIMAL_EXPONENT: ["e","E"] (["+","-"])? (["0"-"9"])+ 

HEXADECIMAL_FLOATING_POINT_LITERAL:
        "0" ["x", "X"] (["0"-"9","a"-"f","A"-"F"])+ (".")? <HEXADECIMAL_EXPONENT> (["f","F","d","D"])?
      | "0" ["x", "X"] (["0"-"9","a"-"f","A"-"F"])* "." (["0"-"9","a"-"f","A"-"F"])+ <HEXADECIMAL_EXPONENT> (["f","F","d","D"])?

HEXADECIMAL_EXPONENT: ["p","P"] (["+","-"])? (["0"-"9"])+ 
```

E.g. 
- base 10 
  - `0.5` 
  - `0.5f`, `0.5F`, `2f` (Note: this is NOT hexadecimal)
  - `0.5d`, `0.5D`, `2D` (Note: this is NOT hexadecimal)
  - `3.21e2d` equivalent to `3.21 * 10^2 = 321`
- base 16
  - `0x3p4d` equivalent to `3 * 2^4 = 48`  
  - `0x3.5p4d` equivalent to `3.5(base 16) * 2^4`

**BigDecimal** in YouTrackDB is represented as a Java BigDecimal. 
The instantiation of BigDecimal can be done explicitly, using the `decimal(<number> | <string>)` function, e.g. `decimal(124.4)` or `decimal("124.4")`


### Mathematical operations

Mathematical operations with numbers follow these rules:
- Operations are calculated from left to right, following the operator precedence. 
- When an operation involves two numbers of different type, both are converted to the higher precision type between the two. 

E.g. 

```
15 + 20L = 15L + 20L     // the 15 is converted to 15L

15L + 20 = 15L + 20L     // the 20 is converted to 20L

15 + 20.3 = 15.0 + 20.3     // the 15 is converted to 15.0

15.0 + 20.3D = 15.0D + 20.3D     // the 15.0 is converted to 15.0D
```

The overflow follows Java rules.

The conversion of a number to BigDecimal can be done explicitly, using the `decimal()` function, e.g. `decimal(124.4)` or `decimal("124.4")`



## Collections

YouTrackDB supports two types of collections:
- **Lists**: ordered, allow duplicates
- **Sets**: not ordered, no duplicates
 
The YQL notation allows creating `Lists` with square bracket notation, e.g.
```
[1, 3, 2, 2, 4]
```

A `List` can be converted to a `Set` using the `.asSet()` method:

```
[1, 3, 2, 2, 4].asSet() = [1, 3, 2, 4] /*  the order of the elements in the resulting set is not guaranteed */
```

## Binary data
YouTrackDB can store binary data (byte arrays) in entity properties. There is no native representation of binary data in YQL syntax. To insert/update a binary field, you have to use the `decode(<base64string>, "base64")` function.

To obtain the base64 string representation of a byte array, you can use the function `encode(<byteArray>, "base64")`.

## Expressions

Expressions can be used as:

- single projections
- operands in a condition
- items in a GROUP BY 
- items in an ORDER BY
- right argument of a LET assignment

Valid expressions are:
- `<base type value>` (string, number, boolean)
- `<field name>`
- `<@attribute name>`
- `<function invocation>`
- `<expression> <binary operator> <expression>`: for operator precedence, see below table.
- `<unary operator> <expression>` 
- `( <expression> )`: expression between parenthesis, for precedences
- `( <query> )`: query between parenthesis
- `[ <expression> (, <expression>)* ]`: a list, an ordered collection that allows duplicates, e.g. `["a", "b", "c"]`
- `<expression> <modifier> ( <modifier> )*`: a chain of modifiers (see below)
- `<expression> IS NULL`: check for null value of an expression
- `<expression> IS NOT NULL`: check for non null value of an expression

### Modifiers

A modifier can be:
- a dot-separated field chain, e.g. `foo.bar`. Dot notation is used to navigate relationships and entity properties, e.g.
  ```
   john.address.city.name = "London"
  ```
  
- a method invocation, e.g. `foo.size()`.

  Method invocations can be chained, e.g. `foo.toLowerCase().substring(2, 4)`
  
- a square bracket filter, e.g. `foo[1]` or `foo[name = 'John']`


### Square bracket filters

Square brackets can be used to filter collections or maps. 

`property[ ( <expression> | <range> | <condition> ) ]`

Based on what is between brackets, the square bracket filtering has different effects:

- `<expression>`: If the expression returns an Integer or Long value (i), the result of the square bracket filtering
is the i-th element of the collection/map. If the result of the expression (K) is not a number, the filtering returns the value corresponding to the key K in the map field. If the field is not a collection/map, the square bracket filtering returns `null`.
The result of this filtering is ALWAYS a single value.
- `<range>`: A range is something like `M..N` or `M...N` where M and N are integer/long numbers, e.g. `fieldName[2..5]`. The result of range filtering is a collection that is a subset of the original field value, containing all the items from position M (included) to position N (excluded for `..`, included for `...`). E.g. if `fieldName = ['a', 'b', 'c', 'd', 'e']`, `fieldName[1..3] = ['b', 'c']`, `fieldName[1...3] = ['b', 'c', 'd']`. Ranges start from `0`. The result of this filtering is ALWAYS a list (ordered collection, allowing duplicates). If the original collection was ordered, then the result will preserve the order.
- `<condition>`: A normal SQL condition, that is applied to each element in the `fieldName` collection. The result is a sub-collection that contains only items that match the condition. E.g. `fieldName = [{foo = 1},{foo = 2},{foo = 5},{foo = 8}]`, `fieldName[foo > 4] = [{foo = 5},{foo = 8}]`. The result of this filtering is ALWAYS a list (ordered collection, allowing duplicates). If the original collection was ordered, then the result will preserve the order.


## Conditions

A condition is an expression that returns a boolean value.

An expression that returns something different from a boolean value is always evaluated to `false`.

## Comparison Operators

- **`=`  (equals)**: If used in an expression, it is the boolean equals (e.g. `select from Foo where name = 'John'`). If used in a SET section of INSERT/UPDATE statements or on a LET statement, it represents a variable assignment (e.g. `insert into Foo set name = 'John'`).
- **`!=` (not equals)**: inequality operator.
- **`<>` (not equals)**: same as `!=`
- **`>`  (greater than)**
- **`>=` (greater or equal)**
- **`<`  (less than)**
- **`<=` (less or equal)**

## Math Operators

- **`+`  (plus)**: addition if both operands are numbers, string concatenation (with string conversion) if one of the operands is not a number. The order of calculation (and conversion) is from left to right, e.g. `'a' + 1 + 2 = 'a12'`, `1 + 2 + 'a' = '3a'`. It can also be used as a unary operator (no effect).
- **`-`  (minus)**: subtraction between numbers. Non-number operands are evaluated to zero. If the right operand is null, the left operand is returned unchanged, e.g. `1 - null = 1`. If the left operand is null, the result is null. Minus can also be used as a unary operator, to invert the sign of a number.
- **`*`  (multiplication)**: multiplication between numbers. If one of the operands is null, the multiplication will evaluate to null. 
- **`/`  (division)**: division between numbers. If one of the operands is null, the division will evaluate to null. Integer division by zero throws an exception. Floating-point division by zero returns `Infinity` (or `NaN` for `0.0 / 0.0`).
- **`%`  (modulo)**: modulo between numbers. If one of the operands is null, the modulo will evaluate to null.
- **`>>`  (bitwise right shift)**: shifts bits of the left operand to the right by a number of positions equal to the right operand, e.g. `8 >> 2 = 2`. Both operands have to be Integer or Long values, otherwise the result will be null.  
- **`>>>`  (unsigned bitwise right shift)**: shifts bits of the left operand to the right by a number of positions equal to the right operand, and fills with `0` on the left regardless of sign. This differs from `>>`, which fills with the sign bit. Both operands have to be Integer or Long values, otherwise the result will be null.
- **`<<`  (bitwise left shift)**: shifts bits of the left operand to the left by a number of positions equal to the right operand, e.g. `2 << 2 = 8`. Both operands have to be Integer or Long values, otherwise the result will be null.
- **`&`  (bitwise AND)** executes a bitwise AND operation. Both operands have to be Integer or Long values, otherwise the result will be null.
- **`|`  (bitwise OR)** executes a bitwise OR operation. Both operands have to be Integer or Long values, otherwise the result will be null.
- **`^`  (bitwise XOR)** executes a bitwise XOR operation. Both operands have to be Integer or Long values, otherwise the result will be null.
- **`||`**: array concatenation (see below for details)

### Math operator precedence


| type                  |   Operators     |
|-----------------------|-----------------|
| multiplicative        | `*` `/` `%`     |
| additive	            |   `+` `-`       |
| shift	                | `<<` `>>` `>>>` |
| bitwise AND	        |   `&`           |
| bitwise exclusive OR	|  `^`            |
| bitwise inclusive OR	|   <code>&#124;</code>        |
| array concatenation	|   <code>&#124;&#124;</code>        |

## Math + Assign operators

These operators can be used in UPDATE statements to update values in-place. The semantics are the same as the operation plus the assignment,
e.g. `a += 2` is just a shortcut for `a = a + 2`.

- **`+=`  (add and assign)**: adds right operand to left operand and assigns the value to the left operand. Returns the final value of the left operand. If one of the operands is not a number, then this operator acts as a `concatenate string values and assign`
- **`-=`  (subtract and assign)**: subtracts right operand from left operand and assigns the value to the left operand. Returns the final value of the left operand
- **`*=`  (multiply and assign)**: multiplies left operand and right operand and assigns the value to the left operand. Returns the final value of the left operand
- **`/=`  (divide and assign)**: divides left operand by right operand and assigns the value to the left operand. Returns the final value of the left operand
- **`%=`  (modulo and assign)**: calculates left operand modulo right operand and assigns the value to the left operand. Returns the final value of the left operand

## Array concatenation

The `||` operator concatenates two arrays.

```
[1, 2, 3] || [4, 5] = [1, 2, 3, 4, 5]
```

If one of the operands is not an array, then it's converted to an array of one element before the concatenation operation is executed.

```
[1, 2, 3] || 4 = [1, 2, 3, 4]

1 || [2, 3, 4] = [1, 2, 3, 4]

1 || 2 || 3 || 4 = [1, 2, 3, 4]
```

To add an array, you have to wrap the array element in another array:

```
[[1, 2], [3, 4]] || [5, 6] = [[1, 2], [3, 4], 5, 6]

[[1, 2], [3, 4]] || [[5, 6]] = [[1, 2], [3, 4], [5, 6]]
```

The result of an array concatenation is always a List (ordered and with duplicates). The order of the elements in the list is the same as the order in the elements in the source arrays, in the order they appear in the original expression.

To transform the result of an array concatenation into a Set (remove duplicates), just use the `.asSet()` method.

```
[1, 2] || [2, 3] = [1, 2, 2, 3]

([1, 2] || [2, 3]).asSet() = [1, 2, 3] 
```

**Specific behavior of NULL**

A null value has no effect when applied to a `||` operation, e.g.

```
[1, 2] || null = [1, 2]

null || [1, 2] = [1, 2]
```

To add null values to a collection, you have to explicitly wrap them in another collection, e.g.

```
[1, 2] || [null] = [1, 2, null]
```



## Boolean Operators

- **`AND`**: logical AND
- **`OR`**: logical OR
- **`NOT`**: logical NOT
- **`CONTAINS`**: checks if the left collection contains the right element. The left argument has to be a collection, otherwise it returns FALSE. It's NOT the check of collection intersections, so `['a', 'b', 'c'] CONTAINS ['a', 'b']` will return FALSE, while `['a', 'b', 'c'] CONTAINS 'a'` will return TRUE. 
- **`IN`**: the same as CONTAINS, but with inverted operands.
- **`CONTAINSKEY`**: for maps, the same as for CONTAINS, but checks on the map keys
- **`CONTAINSVALUE`**: for maps, the same as for CONTAINS, but checks on the map values
- **`LIKE`**: for strings, checks if a string matches a pattern. `%` is used as a wildcard, e.g. `'foobar' LIKE '%ooba%'`.
- **`IS DEFINED`** (unary): returns TRUE if a field is defined in an entity.
- **`IS NOT DEFINED`** (unary): returns TRUE if a field is not defined in an entity.
- **`BETWEEN - AND`** (ternary): returns TRUE if a value is between two values, e.g. `5 BETWEEN 1 AND 10`.
- **`MATCHES`**: checks if a string matches a regular expression.
- **`INSTANCEOF`**: checks the type of a value, the right operand has to be a String representing a class name, e.g. `father INSTANCEOF 'Person'`.
