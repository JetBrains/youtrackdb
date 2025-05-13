package com.jetbrains.youtrack.db.api.common.query.collection.links;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import java.util.List;
import java.util.RandomAccess;

/// Implementation of [List] which contains links to other records.
///
/// This interface is used in [com.jetbrains.youtrack.db.api.common.query.BasicResult] instances to
/// represent [com.jetbrains.youtrack.db.api.schema.PropertyType#LINKLIST] type.
///
///
/// You can add any [Identifiable] object to the list. Internally, such objects will always be
/// stored as [com.jetbrains.youtrack.db.api.record.RID]s.
///
/// So if you add a record to the instance of a link list and then return it by index, an
/// [com.jetbrains.youtrack.db.api.record.RID] object will be returned.
///
///
/// ```
/// var linkList = transaction.newLinkList();
/// var entity = transaction.newEntity();
/// linkList.add(entity);
///
/// assert entity != linkList.get(0);
/// assert entity.getIdentity() == linkList.get(0);
///```
/// Link list cannot be instantiated directly, instead you should use factory methods either in
/// [com.jetbrains.youtrack.db.api.DatabaseSession] or in
/// [com.jetbrains.youtrack.db.api.transaction.Transaction] or in
/// [com.jetbrains.youtrack.db.api.record.Entity] objects.
///
/// @see com.jetbrains.youtrack.db.api.common.query.BasicResult#getLinkList(String)
public interface LinkList extends List<Identifiable>, RandomAccess {
}
