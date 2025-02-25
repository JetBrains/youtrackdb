/*
 * Copyright 2013 Geomatys
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrack.db.internal.core.sql.functions.misc;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Blob;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.serialization.SerializableStream;
import com.jetbrains.youtrack.db.internal.core.sql.functions.SQLFunctionAbstract;
import java.util.Base64;

/**
 * Encode a string in various format (only base64 for now)
 */
public class SQLFunctionEncode extends SQLFunctionAbstract {

  public static final String NAME = "encode";
  public static final String FORMAT_BASE64 = "base64";

  /**
   * Get the date at construction to have the same date for all the iteration.
   */
  public SQLFunctionEncode() {
    super(NAME, 2, 2);
  }

  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      Object iCurrentResult,
      final Object[] iParams,
      CommandContext context) {

    final var candidate = iParams[0];
    final var format = iParams[1].toString();

    byte[] data = null;
    if (candidate instanceof byte[]) {
      data = (byte[]) candidate;
    } else if (candidate instanceof RecordId) {
      try {
        final RecordAbstract rec = ((RecordId) candidate).getRecord(context.getDatabaseSession());
        if (rec instanceof Blob) {
          data = rec.toStream();
        }
      } catch (RecordNotFoundException rnf) {
        return null;
      }
    } else if (candidate instanceof SerializableStream) {
      data = ((SerializableStream) candidate).toStream();
    }

    if (data == null) {
      return null;
    }

    if (FORMAT_BASE64.equalsIgnoreCase(format)) {
      return Base64.getEncoder().encodeToString(data);
    } else {
      throw new DatabaseException(context.getDatabaseSession(), "unknowned format :" + format);
    }
  }

  @Override
  public String getSyntax(DatabaseSession session) {
    return "encode(<binaryfield>, <format>)";
  }
}
