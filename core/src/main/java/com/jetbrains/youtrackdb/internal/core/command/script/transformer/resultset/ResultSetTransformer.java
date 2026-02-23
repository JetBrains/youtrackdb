package com.jetbrains.youtrackdb.internal.core.command.script.transformer.resultset;

import com.jetbrains.youtrackdb.internal.core.query.ResultSet;

/**
 * Transforms a value of type {@code T} into a {@link ResultSet}.
 */
public interface ResultSetTransformer<T> {

  ResultSet transform(T value);
}
