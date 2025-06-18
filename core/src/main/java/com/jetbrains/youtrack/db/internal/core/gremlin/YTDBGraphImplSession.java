package com.jetbrains.youtrack.db.internal.core.gremlin;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import org.apache.commons.configuration2.Configuration;

public class YTDBGraphImplSession extends YTDBGraphImplAbstract {

  static {
    registerOptimizationStrategies(YTDBGraphImplSession.class);
  }

  private final DatabaseSessionEmbedded session;

  public YTDBGraphImplSession(DatabaseSessionEmbedded session, Configuration configuration) {
    super(configuration);
    this.session = session;
  }

  @Override
  public void close() {
    session.close();
  }

  @Override
  public DatabaseSessionEmbedded getUnderlyingDatabaseSession() {
    return session;
  }

  @Override
  public DatabaseSessionEmbedded peekUnderlyingDatabaseSession() {
    return session;
  }

  @Override
  public boolean isOpen() {
    return !session.isClosed();
  }

  @Override
  public boolean isSingleThreaded() {
    return true;
  }
}
