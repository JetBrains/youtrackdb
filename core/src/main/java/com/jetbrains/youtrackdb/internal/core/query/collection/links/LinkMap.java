package com.jetbrains.youtrackdb.internal.core.query.collection.links;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.query.BasicResult;
import com.jetbrains.youtrackdb.internal.core.tx.Transaction;
import java.util.Map;

/// Implementation of [Map] which contains links to other records.
///
/// This interface is used in [BasicResult] instances to represent [PropertyType#LINKMAP] type.
///
///
/// You can add any [Identifiable] object to the map. Internally, such objects will always be stored
/// as [RID]s.
///
/// So if you add a record to the instance of a link map and then return it by key, an [RID] object
/// will be returned.
///
///
/// ```
/// var linkMap = transaction.newLinkMap();
/// var entity = transaction.newEntity();
///
/// linkMap.put("key", entity);
///
/// assert entity != linkMap.get("key");
/// assert entity.getIdentity() == linkMap.get("key");
/// ```
///
/// Link map cannot be instantiated directly, instead you should use factory methods either in
/// [DatabaseSession] or in [Transaction] or in [Entity] objects.
///
/// @see BasicResult#getLinkMap(String)
public interface LinkMap extends Map<String, Identifiable> {

}
