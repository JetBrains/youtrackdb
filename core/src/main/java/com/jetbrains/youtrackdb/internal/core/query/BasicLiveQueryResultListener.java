package com.jetbrains.youtrackdb.internal.core.query;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import javax.annotation.Nonnull;

/// Interface that is used to notify about changes in the database. Records that are monitored are
/// expressed using the `select` query syntax.
///
/// In the case of a remote session, there is no guarantee that all changes will be notified as some
/// of them can be lost due to network issues.
///
/// So this functionality can be used for notification logic but not for business processing logic.
///
/// [Result] instance passed in listener methods is detached from the database and represents a
/// projection expressed in `select` query.
///
/// @see YouTrackDB#live(String, String, String, String, BasicLiveQueryResultListener, Map)
/// @see YouTrackDB#live(String, String, String, String, BasicLiveQueryResultListener, Object...)
/// @see LiveQueryMonitor#unSubscribe()
public interface BasicLiveQueryResultListener<S extends DatabaseSessionEmbedded, R extends BasicResult> {

  /// Method is called by the database when the record satisfied by the query conditions is
  /// created.
  void onCreate(@Nonnull S session, @Nonnull R data);

  /// Method is called by the database when the record satisfied by the query conditions is
  /// updated.
  void onUpdate(@Nonnull S session, @Nonnull R before, @Nonnull R after);

  /// Method is called by the database when the record satisfied by the query conditions is
  /// deleted.
  void onDelete(@Nonnull S session, @Nonnull R data);

  /// Method is called in case of error during handling events of the provided query. Typically
  /// network errors are reported here.
  void onError(@Nonnull S session, @Nonnull BaseException exception);

  /// Method is called when the user requests to stop listening to the query.
  ///
  /// @see LiveQueryMonitor#unSubscribe()
  void onEnd(@Nonnull S session);
}
