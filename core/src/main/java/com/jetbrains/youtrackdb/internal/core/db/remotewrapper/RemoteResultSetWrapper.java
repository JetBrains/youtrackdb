package com.jetbrains.youtrackdb.internal.core.db.remotewrapper;

import com.jetbrains.youtrackdb.api.common.BasicDatabaseSession;
import com.jetbrains.youtrackdb.api.query.ResultSet;
import com.jetbrains.youtrackdb.api.remote.query.RemoteResult;
import com.jetbrains.youtrackdb.api.remote.query.RemoteResultSet;
import java.util.Spliterator;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class RemoteResultSetWrapper implements RemoteResultSet {

  private final ResultSet resultSet;
  private final RemoteDatabaseSessionWrapper sessionWrapper;

  public RemoteResultSetWrapper(ResultSet resultSet, RemoteDatabaseSessionWrapper sessionWrapper) {
    this.resultSet = resultSet;
    this.sessionWrapper = sessionWrapper;
  }

  @Override
  public boolean hasNext() {
    sessionWrapper.startRemoteCall();
    try {
      return resultSet.hasNext();
    } finally {
      sessionWrapper.endRemoteCall();
    }
  }

  @Override
  public RemoteResult next() {
    sessionWrapper.startRemoteCall();
    try {
      return new RemoteResultWrapper(resultSet.next(), sessionWrapper);
    } finally {
      sessionWrapper.endRemoteCall();
    }
  }

  @Override
  public void close() {
    sessionWrapper.startRemoteCall();
    try {
      resultSet.close();
    } finally {
      sessionWrapper.endRemoteCall();
    }
  }

  @Nullable
  @Override
  public BasicDatabaseSession<?, ?> getBoundToSession() {
    return sessionWrapper;
  }

  @Override
  public boolean tryAdvance(Consumer<? super RemoteResult> action) {
    sessionWrapper.startRemoteCall();
    try {
      return resultSet.tryAdvance(
          result -> action.accept(new RemoteResultWrapper(result, sessionWrapper)));
    } finally {
      sessionWrapper.endRemoteCall();
    }

  }

  @Override
  public void forEachRemaining(@Nonnull Consumer<? super RemoteResult> action) {
    sessionWrapper.startRemoteCall();
    try {
      resultSet.forEachRemaining(
          result -> action.accept(new RemoteResultWrapper(result, sessionWrapper)));
    } finally {
      sessionWrapper.endRemoteCall();
    }

  }

  @Override
  @Nullable
  public Spliterator<RemoteResult> trySplit() {
    return null;
  }

  @Override
  public long estimateSize() {
    return resultSet.estimateSize();
  }

  @Override
  public int characteristics() {
    return resultSet.characteristics();
  }

  @Override
  public boolean isClosed() {
    return resultSet.isClosed();
  }
}
