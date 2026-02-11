package com.jetbrains.youtrackdb.internal.core.query.collection.embedded;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.query.BasicResult;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.tx.Transaction;
import java.util.Set;


/// Implementation of [Set] which contains embedded objects, i.e., objects stored directly inside
/// the record itself.
///
/// This interface is used in [BasicResult] instances to represent the [#
/// com.jetbrains.youtrackdb.api.schema.PropertyType#EMBEDDEDSET] type.
///
/// Embedded set cannot be instantiated directly, instead you should use factory methods either in
/// [DatabaseSessionEmbedded] or in [Transaction] or in [Entity] objects.
///
/// If an embedded set is associated with [Entity] it cannot contain links to another record. This
/// restriction is forced because a database always ensures links consistency.
///
/// If an embedded set is associated with [Entity] it can contain only types expressed in
/// [PropertyType] if it used to keep results of queries, it can also contain links and also
/// [Result] objects.
///
/// @see BasicResult#getEmbeddedSet(String)
public interface EmbeddedSet<T> extends Set<T> {

}
