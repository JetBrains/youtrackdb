package com.jetbrains.youtrack.db.api.common.query.collection.embedded;

import java.util.Map;

/// Implementation of [Map] which contains embedded objects, i.e., objects stored directly inside
/// the record itself.
///
/// This interface is used in [com.jetbrains.youtrack.db.api.common.query.BasicResult] instances to
/// represent [com.jetbrains.youtrack.db.api.schema.PropertyType#EMBEDDEDMAP] type.
///
/// Embedded map supports only string keys.
///
/// Embedded map cannot be instantiated directly, instead you should use factory methods either in
/// [com.jetbrains.youtrack.db.api.DatabaseSession] or in
/// [com.jetbrains.youtrack.db.api.transaction.Transaction] or in
/// [com.jetbrains.youtrack.db.api.record.Entity] objects.
///
/// If an embedded map is associated with [com.jetbrains.youtrack.db.api.record.Entity] it cannot
/// contain links to another record. This restriction is forced because a database always ensures
/// links consistency.
///
/// If an embedded map is associated with [com.jetbrains.youtrack.db.api.record.Entity] it can
/// contain only types expressed in [com.jetbrains.youtrack.db.api.schema.PropertyType] if it used
/// to keep results of queries, it can also contain links and also
/// [com.jetbrains.youtrack.db.api.query.Result] objects.
///
/// @see com.jetbrains.youtrack.db.api.common.query.BasicResult#getEmbeddedMap(String)
public interface EmbeddedMap<T> extends Map<String, T> {

}
