# Release Notes

Behavioral changes that affect an existing deployment. Read the entry for every version
between your current version and your target version.

## Unreleased

### Gremlin order by keeps a record that lacks the ordered property

A global-scope Gremlin `order()` step used to drop a record that carries no value for the
ordered property. That drop acted as a filter. It now keeps the record and sorts it as a
null key, the way the YouTrackDB Query Language (YQL) sorts a missing column.

**Who is affected.** Any query of the form `order().by(key)` over records where the key is
absent on some of them. Such a query returns MORE ROWS after the upgrade. A count after an
order returns a larger number. A `limit` after an order can return different records.

**Why the change.** The Gremlin result and the YQL result for the same sort disagreed. One
dropped the record and the other kept it. The two now agree.

**Nothing else changed.** A local-scope order, a `select` modulator, a `values` step, a
`group` modulator and a `dedup` modulator all keep their current filtering behavior.

**How to restore the old behavior.** Set
`youtrackdb.query.gremlin.orderIncludesMissingKey` to `false`. A deployment can set the
value without recompiling, through a Java system property at startup.

```
-Dyoutrackdb.query.gremlin.orderIncludesMissingKey=false
```

One traversal source can also opt out on its own, through the public constant
`YTDBQueryConfigParam.orderIncludesMissingKey`.

```java
graph.traversal().with(YTDBQueryConfigParam.orderIncludesMissingKey, false);
```

**Known limitation.** Under the including default, an ordered query can lose duplicate
rows. Two conditions must hold. The order key carries an index, and the order applies to
the target of a fan-in hop. A later change in the same pull request repairs it. The
[Gremlin order by guide](gremlin-order-by.md) states the conditions and the workarounds.

**No detection tool exists.** Nothing reports which of your queries change their result.
Review every query that orders by a property which some records do not carry.
