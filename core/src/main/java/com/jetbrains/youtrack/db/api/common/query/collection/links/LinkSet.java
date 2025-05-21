package com.jetbrains.youtrack.db.api.common.query.collection.links;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import java.util.Set;

/// Implementation of [Set] which contains links to other records.
///
/// This interface is used in [com.jetbrains.youtrack.db.api.common.query.BasicResult] instances to
/// represent [com.jetbrains.youtrack.db.api.schema.PropertyType#LINKSET] type.
///
///
/// You can add any [Identifiable] object to the set. Internally, such objects will always be stored
/// as [com.jetbrains.youtrack.db.api.record.RID]s.
///
/// So if you add a record to the instance of a link set and then iterate, an
/// [com.jetbrains.youtrack.db.api.record.RID] object will be returned.
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
///```
///
/// Link set cannot be instantiated directly, instead you should use factory methods either in
/// [com.jetbrains.youtrack.db.api.DatabaseSession] or in
/// [com.jetbrains.youtrack.db.api.transaction.Transaction] or in
/// [com.jetbrains.youtrack.db.api.record.Entity] objects.
///
/// @see com.jetbrains.youtrack.db.api.common.query.BasicResult#getLinkSet(String)
public interface LinkSet extends Set<Identifiable> {

}
