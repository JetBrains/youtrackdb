package com.jetbrains.youtrackdb.internal.core.gql.executor;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/// Immutable context for GQL query execution.
///
/// Contains the database session and query parameters.
/// The executor layer works with session and YTDB entities only, not graph instances.
public record GqlExecutionContext(@Nonnull DatabaseSessionEmbedded session,
    @Nonnull Map<String, Object> parameters) {

  public GqlExecutionContext(@Nonnull DatabaseSessionEmbedded session) {
    this(session, Map.of());
  }

  public GqlExecutionContext(
      @Nonnull DatabaseSessionEmbedded session,
      @Nonnull Map<String, Object> parameters) {
    this.session = session;
    this.parameters = parameters.isEmpty() ? Map.of() : Map.copyOf(parameters);
  }

  @SuppressWarnings("unused")
  public @Nullable Object getParameter(@Nonnull String name) {
    return parameters.get(name);
  }
}
