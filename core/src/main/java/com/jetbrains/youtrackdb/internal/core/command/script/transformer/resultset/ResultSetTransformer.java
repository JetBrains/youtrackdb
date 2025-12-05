package com.jetbrains.youtrackdb.internal.core.command.script.transformer.resultset;

import com.jetbrains.youtrackdb.internal.core.query.ResultSet;

/**
 *
 */
public interface ResultSetTransformer<T> {

  ResultSet transform(T value);
}
