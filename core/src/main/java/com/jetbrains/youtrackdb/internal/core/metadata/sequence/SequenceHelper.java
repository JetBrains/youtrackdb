/*
 *
 *  *  Copyright YouTrackDB
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
package com.jetbrains.youtrackdb.internal.core.metadata.sequence;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence.CreateParams;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence.SEQUENCE_TYPE;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;

/**
 * Factory utility for creating database sequence instances based on the requested type.
 *
 * @since 3/1/2015
 */
public class SequenceHelper {

  public static final SEQUENCE_TYPE DEFAULT_SEQUENCE_TYPE = SEQUENCE_TYPE.CACHED;

  public static DBSequence createSequence(SEQUENCE_TYPE sequenceType, EntityImpl entity) {
    return switch (sequenceType) {
      case ORDERED -> new SequenceOrdered(entity);
      case CACHED -> new SequenceCached(entity);
    };
  }

  public static DBSequence createSequence(
      DatabaseSessionEmbedded db, SEQUENCE_TYPE sequenceType, CreateParams params, String name) {
    return switch (sequenceType) {
      case ORDERED -> new SequenceOrdered(db, params, name);
      case CACHED -> new SequenceCached(db, params, name);
    };
  }

  public static SEQUENCE_TYPE getSequenceTyeFromString(String typeAsString) {
    return SEQUENCE_TYPE.valueOf(typeAsString);
  }

  public static DBSequence createSequence(EntityImpl entity) {
    var sequenceType = DBSequence.getSequenceType(entity);
    return createSequence(sequenceType, entity);
  }
}
