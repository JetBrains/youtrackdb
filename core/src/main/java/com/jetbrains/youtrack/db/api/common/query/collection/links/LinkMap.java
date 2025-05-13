package com.jetbrains.youtrack.db.api.common.query.collection.links;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import java.util.Map;

/// Implementation of [Map] which contains links to other records.
///
/// This interface is used in [com.jetbrains.youtrack.db.api.common.query.BasicResult] instances to
/// represent [com.jetbrains.youtrack.db.api.schema.PropertyType#LINKMAP] type.
///
///
/// You can add any [Identifiable] object to the map. Internally, such objects will always be stored
/// as [com.jetbrains.youtrack.db.api.record.RID]s.
///
/// So if you add a record to the instance of a link map and then return it by key, an
/// [com.jetbrains.youtrack.db.api.record.RID] object will be returned.
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
///```
///
/// Link map cannot be instantiated directly, instead you should use factory methods either in
/// [com.jetbrains.youtrack.db.api.DatabaseSession] or in
/// [com.jetbrains.youtrack.db.api.transaction.Transaction] or in
/// [com.jetbrains.youtrack.db.api.record.Entity] objects.
///
/// @see com.jetbrains.youtrack.db.api.common.query.BasicResult#getLinkMap(String)
public interface LinkMap extends Map<String, Identifiable> {
}
