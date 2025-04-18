package com.jetbrains.youtrack.db.api.common.query.collection.links;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import java.util.List;
import java.util.RandomAccess;

public interface LinkList extends List<Identifiable>, RandomAccess {
}
