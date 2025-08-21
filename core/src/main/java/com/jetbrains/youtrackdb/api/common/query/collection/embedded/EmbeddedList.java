package com.jetbrains.youtrackdb.api.common.query.collection.embedded;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.common.query.BasicResult;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.api.transaction.Transaction;
import java.util.List;
import java.util.RandomAccess;

/// Implementation of [List] which contains embedded objects, i.e., objects stored directly inside
/// the record itself.
///
/// This interface is used in [BasicResult] instances to represent the [PropertyType#EMBEDDEDLIST]
/// type.
///
/// Embedded list cannot be instantiated directly, instead you should use factory methods either in
/// [DatabaseSession] or in [Transaction] or in [Entity] objects.
///
/// If an embedded list is associated with [Entity] it cannot contain links to another record. This
/// restriction is forced because a database always ensures links consistency.
///
/// If an embedded list is associated with [Entity] it can contain only types expressed in
/// [PropertyType] if it used to keep results of queries, it can also contain links and also
/// [Result] objects.
///
/// @see BasicResult#getEmbeddedList(String)
public interface EmbeddedList<T> extends List<T>, RandomAccess {

}
