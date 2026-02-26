package com.jetbrains.youtrackdb.internal.core.query.collection.links;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import java.util.List;
import java.util.RandomAccess;

/// Implementation of [List] which contains links to other records.
///
/// This interface is used in [BasicResult] instances to represent [PropertyType#LINKLIST] type.
///
///
/// You can add any [Identifiable] object to the list. Internally, such objects will always be
/// stored as [RID]s.
///
/// So if you add a record to the instance of a link list and then return it by index, an [RID]
/// object will be returned.
///
///
/// ```
/// var linkList = transaction.newLinkList();
/// var entity = transaction.newEntity();
/// linkList.add(entity);
///
/// assert entity != linkList.get(0);
/// assert entity.getIdentity() == linkList.get(0);
/// ```
/// Link list cannot be instantiated directly, instead you should use factory methods either in
/// [DatabaseSessionEmbedded] or in [Transaction] or in [Entity] objects.
///
/// @see BasicResult#getLinkList(String)
public interface LinkList extends List<Identifiable>, RandomAccess {

}
