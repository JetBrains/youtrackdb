# Gremlin Order By and Missing Properties

YouTrackDB changes one Apache TinkerPop behavior on purpose. A global-scope Gremlin
`order()` step now keeps a record that lacks the ordered property. It sorts that record
the way the YouTrackDB Query Language (YQL) sorts a missing column.

## What changed

Portable Apache TinkerPop treats a `by(...)` modulator as a filter. The modulator is a
traversal. An element without the property produces no value, so the traverser is dropped
before any comparison runs. `order().by("age")` therefore acts as a filter on `age`.

YQL never drops such a row. `SELECT FROM Person ORDER BY age` returns every person and
sorts a missing `age` as a null key.

YouTrackDB now applies the YQL rule to a global-scope Gremlin order.

Take three people. Alice is 30. Bob is 25. Nobody has no `age` property.

```groovy
g.V().hasLabel("Person").order().by("age").values("name")
```

Portable Apache TinkerPop returns two rows.

```
Bob
Alice
```

YouTrackDB returns three rows.

```
Nobody
Bob
Alice
```

The null key sorts where YQL puts it. An ascending order puts the null key first. A
descending order puts it last. The two engines agree, so one query returns the same order
through Gremlin and through YQL.

A following step reads the kept record too. `order().by("age").count()` counts three
records, not two.

## What did not change

The change covers a GLOBAL-scope order modulator only. Every other modulator keeps its
filtering behavior.

- `order(Scope.local)` still drops an entry that lacks the key.
- `select("a").by("age")` still drops a record that lacks the key.
- `values("age")` still emits nothing for a record that lacks the key.
- `group().by("age")` and `groupCount().by("age")` still form no null bucket.

This produces one intentional difference between two spellings of a sort:

```groovy
g.V().hasLabel("Person").order().by("age")              // keeps the ageless person
g.V().hasLabel("Person").fold().order(Scope.local).by("age")  // drops the ageless entry
```

The two spellings differ by one argument. The difference is deliberate. Local scope orders
the entries inside one collection. A missing key kept there would change the size of a
collection inside a row rather than the set of rows. YQL has no analogue for that, and the
YouTrackDB Gremlin-to-MATCH translator never handles a local-scope order.

## Restoring the portable behavior

Three routes carry the value, and each accepts a boolean. Set any of them to `false` to
restore portable Apache TinkerPop filtering.

The routes rank in this order, highest first.

1. The per-traversal override, which decides for one traversal source. It has no default.
   When a traversal passes nothing, the answer comes from a lower route.
2. The database configuration, which decides for every traversal on that database.
3. The global setting, whose own default is `true`, the including behavior described above.

### The deployment-wide setting

The setting key is `youtrackdb.query.gremlin.orderIncludesMissingKey`. The enum constant is
`GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY`. Set the value to `false`
to restore portable filtering for every traversal.

```java
GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY.setValue(false);
```

That call takes effect on an open database. The next traversal reads the new value.

A deployment can also set the value without recompiling. YouTrackDB reads the key from a
Java system property at startup.

```
-Dyoutrackdb.query.gremlin.orderIncludesMissingKey=false
```

### The per-traversal override

The public constant `YTDBQueryConfigParam.orderIncludesMissingKey` overrides the setting
for one traversal source. The constant is part of the public API.

```java
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBQueryConfigParam;

var g = graph.traversal().with(YTDBQueryConfigParam.orderIncludesMissingKey, false);
var names = g.V().hasLabel("Person").order().by("age").values("name").toList();
```

The override wins over the deployment-wide setting. It works in both directions. A
deployment that turns the setting off can still ask one traversal for the including
behavior by passing `true`.

The override is read for each traversal, so a running database needs no restart to change
the answer for a single query.

### The per-database configuration

A database opened with the key in its own configuration keeps that value. Neither the
global setting nor the system property can change it afterwards. Pass the key when the
graph is opened.

```java
var configuration = new BaseConfiguration();
configuration.setProperty("youtrackdb.query.gremlin.orderIncludesMissingKey", false);
var graph = GraphFactory.open(configuration);
```

This route outranks the two above it, so a deployment that sets it must change it in the
same place. The YouTrackDB test suites use this route to run the Apache TinkerPop
conformance scenarios on portable semantics.

## Known limitation

One shape loses rows under the including default. The limitation stands in the version that
ships this page. A later version repairs it, and this page states the repair when it lands.
Read this section before you run an ordered query on production data.

A query loses duplicate rows when all three of the following hold:

- Several source records reach the same target record over a hop, which is a fan-in.
- The order key carries an index.
- The order applies to the fan-in target.

```groovy
g.V().hasLabel("Person").as("src").out("knows").as("dst").hasLabel("Person").order().by("id")
```

With two people knowing the same two targets, this traversal returns two rows instead of
four. Cause: the order key carries no presence filter under the including default. The planner
then roots an ordered index scan on the target and visits each target once.

Three workarounds exist today. Order by a property that carries no index. Move the order
off the fan-in target. Set the per-traversal override to `false` for that query.

Only duplicate rows are lost, and only on the translated path. A record that lacks the
ordered key survives this plan and sorts as a null key. The index behind the plan stores no
entry for that record, and the plan returns it anyway. A query without a hop is unaffected. A query whose order key
carries no index is unaffected. A query under the portable opt-out is unaffected.

## Conformance suites

The Apache TinkerPop conformance suites run with the portable opt-out, so they keep
measuring portable order semantics and no upstream scenario is edited. Project-owned tests
cover the shipped default.
