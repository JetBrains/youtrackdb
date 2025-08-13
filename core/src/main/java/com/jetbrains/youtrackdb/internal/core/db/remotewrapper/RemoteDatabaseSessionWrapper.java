package com.jetbrains.youtrackdb.internal.core.db.remotewrapper;

import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.api.exception.CommandScriptException;
import com.jetbrains.youtrackdb.api.remote.RemoteDatabaseSession;
import com.jetbrains.youtrackdb.api.remote.query.RemoteResultSet;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import java.nio.file.Path;
import java.util.Map;
import java.util.TimeZone;
import javax.annotation.Nullable;

public class RemoteDatabaseSessionWrapper implements RemoteDatabaseSession {

  private final DatabaseSessionEmbedded session;
  private boolean closed;

  public RemoteDatabaseSessionWrapper(DatabaseSessionEmbedded session) {
    this.session = session;
  }

  @Override
  public RemoteResultSet query(String query, Object... args)
      throws CommandSQLParsingException, CommandExecutionException {
    checkClosed();

    session.startRemoteCall();
    try {
      return new RemoteResultSetWrapper(session.query(query, args), this);
    } finally {
      session.endRemoteCall();
    }

  }

  @Override
  public RemoteResultSet query(String query, Map args)
      throws CommandSQLParsingException, CommandExecutionException {
    checkClosed();

    session.startRemoteCall();
    try {
      return new RemoteResultSetWrapper(session.query(query, args), this);
    } finally {
      session.endRemoteCall();
    }

  }

  @Override
  public RemoteResultSet execute(String query, Object... args)
      throws CommandSQLParsingException, CommandExecutionException {
    checkClosed();

    session.startRemoteCall();
    try {
      return new RemoteResultSetWrapper(session.execute(query, args), this);
    } finally {
      session.endRemoteCall();
    }

  }

  @Override
  public RemoteResultSet execute(String query, Map args)
      throws CommandSQLParsingException, CommandExecutionException {
    checkClosed();

    session.startRemoteCall();
    try {
      return new RemoteResultSetWrapper(session.execute(query, args), this);
    } finally {
      session.endRemoteCall();
    }
  }

  @Override
  public boolean isPooled() {
    session.startRemoteCall();
    try {
      return session.isPooled();
    } finally {
      session.endRemoteCall();
    }

  }

  @Override
  public void close() {
    if (closed) {
      return;
    }

    closed = true;
    session.remoteWrapperClosed();
  }

  @Override
  public STATUS getStatus() {
    checkClosed();

    session.startRemoteCall();
    try {
      return session.getStatus();
    } finally {
      session.endRemoteCall();
    }

  }

  @Override
  public String getURL() {
    checkClosed();

    session.startRemoteCall();
    try {
      return session.getURL();
    } finally {
      session.endRemoteCall();
    }
  }

  @Override
  public String getDatabaseName() {
    checkClosed();

    session.startRemoteCall();
    try {
      return session.getDatabaseName();
    } finally {
      session.endRemoteCall();
    }
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public void freeze() {
    checkClosed();

    session.startRemoteCall();
    try {
      session.freeze();
    } finally {
      session.endRemoteCall();
    }

  }

  @Override
  public void release() {
    checkClosed();

    session.startRemoteCall();
    try {
      session.release();
    } finally {
      session.endRemoteCall();
    }
  }

  @Override
  public void freeze(boolean throwException) {
    checkClosed();

    session.startRemoteCall();
    try {
      session.freeze(throwException);
    } finally {
      session.endRemoteCall();
    }
  }

  @Nullable
  @Override
  public String getCurrentUserName() {
    checkClosed();

    session.startRemoteCall();
    try {
      return session.getCurrentUserName();
    } finally {
      session.endRemoteCall();
    }
  }

  @Override
  public RemoteResultSet computeScript(String language, String script, Object... args)
      throws CommandExecutionException, CommandScriptException {
    checkClosed();

    session.startRemoteCall();
    try {
      return new RemoteResultSetWrapper(session.computeScript(language, script, args), this);
    } finally {
      session.endRemoteCall();
    }

  }

  @Override
  public RemoteResultSet computeScript(String language, String script, Map<String, ?> args)
      throws CommandExecutionException, CommandScriptException {
    checkClosed();

    session.startRemoteCall();
    try {
      return new RemoteResultSetWrapper(session.computeScript(language, script, args), this);
    } finally {
      session.endRemoteCall();
    }
  }

  @Override
  public void backup(Path path) {
    checkClosed();

    session.startRemoteCall();
    try {
      session.backup(path);
    } finally {
      session.endRemoteCall();
    }
  }

  @Nullable
  @Override
  public TimeZone getDatabaseTimeZone() {
    checkClosed();

    session.startRemoteCall();
    try {
      return session.getDatabaseTimeZone();
    } finally {
      session.endRemoteCall();
    }
  }

  public void startRemoteCall() {
    session.startRemoteCall();
  }

  public void endRemoteCall() {
    session.endRemoteCall();
  }

  private void checkClosed() {
    if (closed) {
      throw new IllegalStateException("Session is closed");
    }
  }
}
