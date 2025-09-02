package com.jetbrains.youtrackdb.internal.core.command.script.transformer.resultset;

import com.jetbrains.youtrackdb.api.query.ResultSet;

/**
 *
 */
public interface ResultSetTransformer<T> {

  ResultSet transform(T value);
}
