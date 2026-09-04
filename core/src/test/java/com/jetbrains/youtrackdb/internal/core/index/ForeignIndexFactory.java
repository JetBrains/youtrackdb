package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.internal.core.config.IndexEngineData;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.index.engine.BaseIndexEngine;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.lang.reflect.Proxy;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Service-loader fixture that returns a handle outside the supported implementation hierarchy. */
public final class ForeignIndexFactory implements IndexFactory {

  static final String INDEX_TYPE = "FOREIGN_TEST_INDEX";
  static final String ALGORITHM = "FOREIGN_TEST_ALGORITHM";

  private static final Index FOREIGN_HANDLE =
      (Index) Proxy.newProxyInstance(
          Index.class.getClassLoader(), new Class<?>[] {Index.class},
          (proxy, method, args) -> null);

  static String foreignHandleClassName() {
    return FOREIGN_HANDLE.getClass().getName();
  }

  @Override
  public int getLastVersion(String algorithm) {
    return 0;
  }

  @Override
  public Set<String> getTypes() {
    return Set.of(INDEX_TYPE);
  }

  @Override
  public Set<String> getAlgorithms() {
    return Set.of(ALGORITHM);
  }

  @Override
  public Index createIndex(String indexType, @Nonnull Storage storage) {
    return FOREIGN_HANDLE;
  }

  @Override
  public Index createIndex(String indexType, @Nullable RID identity,
      @Nonnull FrontendTransactionImpl transaction, @Nonnull Storage storage) {
    return FOREIGN_HANDLE;
  }

  @Override
  public BaseIndexEngine createIndexEngine(Storage storage, IndexEngineData data) {
    throw new UnsupportedOperationException("The foreign-index fixture creates no engines");
  }
}
