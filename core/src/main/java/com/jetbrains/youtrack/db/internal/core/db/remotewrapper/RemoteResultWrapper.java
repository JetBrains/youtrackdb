package com.jetbrains.youtrack.db.internal.core.db.remotewrapper;

import com.jetbrains.youtrack.db.api.common.BasicDatabaseSession;
import com.jetbrains.youtrack.db.api.common.query.BasicResult;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Blob;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.remote.query.RemoteResult;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class RemoteResultWrapper implements RemoteResult {

  private final Result result;
  private final RemoteDatabaseSessionWrapper sessionWrapper;

  public RemoteResultWrapper(Result result, RemoteDatabaseSessionWrapper sessionWrapper) {
    this.result = result;
    this.sessionWrapper = sessionWrapper;
  }

  @Nullable
  @Override
  public <T> T getProperty(@Nonnull String name) {
    startRemoteCall();
    try {
      var value = result.getProperty(name);
      if (value instanceof Result res) {
        //noinspection unchecked
        return (T) (new RemoteResultWrapper(res, sessionWrapper));
      } else if (value instanceof Blob blob) {
        return (T) blob.toStream();
      } else if (value instanceof Edge) {
        throw new IllegalStateException("Lightweight edges are not supported in remote mode. ");
      }

      //noinspection unchecked
      return (T) value;
    } finally {
      endRemoteCall();
    }

  }

  private void endRemoteCall() {
    if (sessionWrapper != null) {
      sessionWrapper.endRemoteCall();
    }
  }

  private void startRemoteCall() {
    if (sessionWrapper != null) {
      sessionWrapper.startRemoteCall();
    }
  }

  @Nullable
  @Override
  public BasicResult getResult(@Nonnull String name) {
    startRemoteCall();
    try {
      return new RemoteResultWrapper(result.getResult(name), sessionWrapper);
    } finally {
      endRemoteCall();
    }
  }

  @Nullable
  @Override
  public RID getLink(@Nonnull String name) {
    startRemoteCall();
    try {
      return result.getLink(name);
    } finally {
      endRemoteCall();
    }
  }

  @Nonnull
  @Override
  public List<String> getPropertyNames() {
    startRemoteCall();
    try {
      return result.getPropertyNames();
    } finally {
      endRemoteCall();
    }
  }

  @Override
  public boolean isIdentifiable() {
    startRemoteCall();
    try {
      return result.isIdentifiable();
    } finally {
      endRemoteCall();
    }
  }

  @Nullable
  @Override
  public RID getIdentity() {
    startRemoteCall();
    try {
      return result.getIdentity();
    } finally {
      endRemoteCall();
    }

  }

  @Override
  public boolean isProjection() {
    startRemoteCall();
    try {
      return result.isProjection();
    } finally {
      endRemoteCall();
    }
  }

  @Nonnull
  @Override
  public Map<String, Object> toMap() {
    startRemoteCall();
    try {
      return result.toMap();
    } finally {
      endRemoteCall();
    }

  }

  @Nonnull
  @Override
  public String toJSON() {
    startRemoteCall();
    try {
      return result.toJSON();
    } finally {
      endRemoteCall();
    }
  }

  @Override
  public boolean hasProperty(@Nonnull String varName) {
    startRemoteCall();
    try {
      return result.hasProperty(varName);
    } finally {
      endRemoteCall();
    }
  }

  @Nullable
  @Override
  public BasicDatabaseSession<?, ?> getBoundedToSession() {
    return sessionWrapper;
  }

  @Nonnull
  @Override
  public BasicResult detach() {
    startRemoteCall();
    try {
      return new RemoteResultWrapper(result.detach(), null);
    } finally {
      endRemoteCall();
    }

  }

  @Override
  public boolean isBlob() {
    startRemoteCall();
    try {
      return result.isBlob();
    } finally {
      endRemoteCall();
    }

  }

  @Override
  public byte[] asBlob() {
    startRemoteCall();
    try {
      return result.asBlob().toStream();
    } finally {
      endRemoteCall();
    }
  }

  @Override
  public byte[] asBlobOrNull() {
    startRemoteCall();
    try {
      var blob = result.asBlobOrNull();
      if (blob != null) {
        return blob.toStream();
      }
      return null;
    } finally {
      endRemoteCall();
    }
  }
}
