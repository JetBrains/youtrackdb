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

package com.jetbrains.youtrack.db.internal.core.serialization.serializer.record;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import javax.annotation.Nonnull;

public interface RecordSerializer {

  void fromStream(@Nonnull DatabaseSessionEmbedded session, @Nonnull byte[] iSource,
      @Nonnull RecordAbstract iRecord,
      String[] iFields);

  byte[] toStream(@Nonnull DatabaseSessionInternal session, @Nonnull RecordAbstract iSource);

  int getCurrentVersion();

  int getMinSupportedVersion();

  String[] getFieldNames(@Nonnull DatabaseSessionInternal session, EntityImpl reference,
      @Nonnull byte[] iSource);

  boolean getSupportBinaryEvaluate();

  String getName();
}
