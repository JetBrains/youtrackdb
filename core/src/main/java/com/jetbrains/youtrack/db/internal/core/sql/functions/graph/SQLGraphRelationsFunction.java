package com.jetbrains.youtrack.db.internal.core.sql.functions.graph;

import com.jetbrains.youtrack.db.internal.core.sql.functions.IndexableSQLFunction;
import java.util.Collection;

public interface SQLGraphRelationsFunction extends IndexableSQLFunction {

  Collection<String> propertyNames();

  String indexName();
}
