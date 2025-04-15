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

package com.jetbrains.youtrack.db.api.exception;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public abstract class BaseException extends RuntimeException {

  @Nullable
  private String dbName;

  public static BaseException wrapException(final BaseException exception, final Throwable cause,
      @Nullable DatabaseSession session) {
    return wrapException(exception, cause, session != null ? session.getDatabaseName() : null);
  }


  public static BaseException wrapException(final BaseException exception, final Throwable cause,
      @Nullable String dbName) {
    if (cause instanceof BaseException baseCause && baseCause.dbName == null) {
      if (dbName != null) {
        baseCause.dbName = dbName;
      } else {
        baseCause.dbName = exception.dbName;
      }
    }

    if (cause instanceof HighLevelException) {
      return (BaseException) cause;
    }

    exception.initCause(cause);

    if (exception.dbName == null) {
      if (dbName != null) {
        exception.dbName = dbName;
      } else if (cause instanceof BaseException baseCause) {
        exception.dbName = baseCause.dbName;
      }
    }

    return exception;
  }

  public BaseException(final String message, @Nullable DatabaseSession session) {
    super(message);

    if (session != null) {
      this.dbName = session.getDatabaseName();
    }
  }

  public BaseException(final String message, @Nullable String dbName) {
    super(message);

    this.dbName = dbName;
  }

  public BaseException(final String message) {
    super(message);
  }

  /**
   * This constructor is needed to restore and reproduce exception on client side in case of remote
   * storage exception handling. Please create "copy constructor" for each exception which has
   * current one as a parent.
   */
  public BaseException(final BaseException exception) {
    super(exception.getMessage(), exception.getCause());
    this.dbName = exception.dbName;
  }

  @Nullable
  public String getDbName() {
    return dbName;
  }

  public void setDbName(@Nullable String dbName) {
    this.dbName = dbName;
  }

  public void setDbName(@Nonnull DatabaseSession db) {
    if (this.dbName == null) {
      this.dbName = db.getDatabaseName();
    }
  }
}
