package com.jetbrains.youtrackdb.internal.core.query.collection.links;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.query.BasicResult;
import com.jetbrains.youtrackdb.internal.core.tx.Transaction;
import java.util.Set;

/// Implementation of [Set] which contains links to other records.
///
/// This interface is used in [BasicResult] instances to represent [PropertyType#LINKSET] type.
///
///
/// You can add any [Identifiable] object to the set. Internally, such objects will always be stored
/// as [RID]s.
///
/// So if you add a record to the instance of a link set and then iterate, an [RID] object will be
/// returned.
///
///
/// ```
/// var linkSet = transaction.newLinkSet();
/// var entity = transaction.newEntity();
///
/// linkSet.add(entity);
///
/// assert entity != linkSet.iterator().next();
/// assert entity.getIdentity() == linkSet.iterator().next();
/// assert !linkSet.add(entity)
/// ```
///
/// Link set cannot be instantiated directly, instead you should use factory methods either in
/// [DatabaseSession] or in [Transaction] or in [Entity] objects.
///
/// @see BasicResult#getLinkSet(String)
public interface LinkSet extends Set<Identifiable> {

}
