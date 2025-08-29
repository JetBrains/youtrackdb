package com.jetbrains.youtrackdb.api.common.query.collection.links;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.common.query.BasicResult;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.api.transaction.Transaction;
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
///```
/// Link list cannot be instantiated directly, instead you should use factory methods either in
/// [DatabaseSession] or in
/// [Transaction] or in
/// [Entity] objects.
///
/// @see BasicResult#getLinkList(String)
public interface LinkList extends List<Identifiable>, RandomAccess {

}
