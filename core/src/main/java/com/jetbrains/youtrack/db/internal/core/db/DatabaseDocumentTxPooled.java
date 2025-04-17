/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrack.db.internal.core.db;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Token;

/**
 * Pooled wrapper to the DatabaseDocumentTx class. Allows to being reused across calls. The close()
 * method does not close the database for real but release it to the owner pool. The database born
 * as opened and will leave open until the pool is closed.
 *
 * @see DatabasePoolBase
 */
@SuppressWarnings("unchecked")
public class DatabaseDocumentTxPooled extends DatabaseDocumentTx implements DatabasePooled {

  private DatabaseDocumentPool ownerPool;
  private final String userName;

  public DatabaseDocumentTxPooled(
      final DatabaseDocumentPool iOwnerPool,
      final String iURL,
      final String iUserName,
      final String iUserPassword) {
    super(iURL);
    ownerPool = iOwnerPool;
    userName = iUserName;

    super.open(iUserName, iUserPassword);
  }

  @Override
  public void reuse(final Object iOwner, final Object[] iAdditionalArgs) {
    ownerPool = (DatabaseDocumentPool) iOwner;
    getLocalCache().invalidate();
    // getMetadata().reload();
    try {
      callOnOpenListeners();
    } catch (Exception e) {
      LogManager.instance()
          .error(this, "Error on reusing database '%s' in pool", e, getDatabaseName());
    }
  }

  @Override
  public DatabaseSession open(final String iUserName, final String iUserPassword) {
    throw new UnsupportedOperationException(
        "Database instance was retrieved from a pool. You cannot open the database in this way. Use"
            + " directly a DatabaseDocumentTx instance if you want to manually open the"
            + " connection");
  }

  @Override
  public DatabaseSession open(final Token iToken) {
    throw new UnsupportedOperationException(
        "Database instance was retrieved from a pool. You cannot open the database in this way. Use"
            + " directly a DatabaseDocumentTx instance if you want to manually open the"
            + " connection");
  }

  @Override
  public DatabaseSession create() {
    throw new UnsupportedOperationException(
        "Database instance was retrieved from a pool. You cannot open the database in this way. Use"
            + " directly a DatabaseDocumentTx instance if you want to manually open the"
            + " connection");
  }

  @Override
  public DatabaseSession create(String incrementalBackupPath) {
    throw new UnsupportedOperationException(
        "Database instance was retrieved from a pool. You cannot open the database in this way. Use"
            + " directly a DatabaseDocumentTx instance if you want to manually open the"
            + " connection");
  }

  @Override
  public boolean isUnderlyingOpen() {
    return !super.isClosed();
  }

  @Override
  public boolean isClosed() {
    return ownerPool == null || super.isClosed();
  }

  /**
   * @return <code>true</code> if database is obtained from the pool and <code>false</code>
   * otherwise.
   */
  @Override
  public boolean isPooled() {
    return true;
  }

  /**
   * Avoid to close it but rather release itself to the owner pool.
   */
  @Override
  public void close() {
    if (isClosed()) {
      return;
    }

    checkOpenness();

    if (ownerPool != null && ownerPool.getConnectionsInCurrentThread(getURL(), userName) > 1) {
      ownerPool.release(this);
      return;
    }

    try {
      if (getTransactionInternal().isActive()) {
        commit();
      }
    } catch (Exception e) {
      LogManager.instance()
          .error(this, "Error on releasing database '%s' in pool", e, getDatabaseName());
    }

    try {
      callOnCloseListeners();
    } catch (Exception e) {
      LogManager.instance()
          .error(this, "Error on releasing database '%s' in pool", e, getDatabaseName());
    }

    getLocalCache().clear();

    if (ownerPool != null) {
      final var localCopy = ownerPool;
      ownerPool = null;
      localCopy.release(this);
    }
  }

  @Override
  public void forceClose() {
    super.close();
  }

  //  @Override
  @Override
  protected void checkOpenness() {
    if (ownerPool == null) {
      throw new DatabaseException(
          "Database instance has been released to the pool. Get another database instance from the"
              + " pool with the right username and password");
    }

    //    super.checkOpenness();
  }
}
