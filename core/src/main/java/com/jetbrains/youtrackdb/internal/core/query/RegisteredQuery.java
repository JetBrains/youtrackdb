package com.jetbrains.youtrackdb.internal.core.query;

/** A session-tracked execution resource with a diagnostic description. */
public interface RegisteredQuery {

  /** Releases the resources owned by this execution. */
  void close();

  /** Returns diagnostic text without opening or executing another query. */
  String getDescription();
}
