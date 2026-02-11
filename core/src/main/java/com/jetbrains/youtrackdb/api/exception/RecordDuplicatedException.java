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

package com.jetbrains.youtrackdb.api.exception;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.CoreException;

/**
 * @since 9/5/12
 */
public class RecordDuplicatedException extends CoreException implements HighLevelException {

  private final RID rid;
  private final String indexName;
  private final Object key;

  public RecordDuplicatedException(final RecordDuplicatedException exception) {
    super(exception);
    this.indexName = exception.indexName;
    this.rid = exception.rid;
    this.key = exception.key;
  }

  public RecordDuplicatedException(
      DatabaseSessionEmbedded db, final String message, final String indexName, final RID iRid,
      Object key) {
    super(db, message);
    this.indexName = indexName;
    this.rid = iRid;
    this.key = key;
  }

  public RID getRid() {
    return rid;
  }

  public String getIndexName() {
    return indexName;
  }

  public Object getKey() {
    return key;
  }

  @Override
  public String getMessage() {
    return super.getMessage() + " INDEX=" + indexName + " RID=" + rid;
  }
}
