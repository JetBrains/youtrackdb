package com.jetbrains.youtrack.db.internal.core.sql.functions.graph;

import java.util.Collection;
import javax.annotation.Nullable;

/// Interface that indicates functions that are used to navigate through graph relations.
///
/// Examples of such functions are `outE``,`bothE``, `inE``,`both` and `bothV`.
public interface SQLGraphRelationsFunction {

  /// List of property names that are used to navigate over relation.
  ///
  /// Those properties are used by SQL engine to determine index that will be used instead of given
  /// function to return the same result. Those properties returned only if property values are
  /// mapped directly to function result. For example `out` function will return `null` in case of
  /// usage of stateful edges.
  @Nullable
  Collection<String> propertyNamesForIndexCandidates(String[] labels);
}
