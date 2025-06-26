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
package com.jetbrains.youtrack.db.internal.server.network.protocol.http.command;

import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import java.util.Map;

public abstract class ServerCommandDocumentAbstract extends ServerCommandAuthenticatedDbAbstract {

  protected String bindToFields(
      final HttpRequest iRequest, final Map<String, String> iFields, final RecordId iRid)
      throws Exception {
    if (iRequest.getContent() == null) {
      throw new IllegalArgumentException("HTTP Request content is empty");
    }

    final var req = iRequest.getContent();

    // PARSE PARAMETERS
    String className = null;

    final var params = req.split("&");
    String value;

    for (var p : params) {
      if (StringSerializerHelper.contains(p, '=')) {
        var pairs = p.split("=");
        value = pairs.length == 1 ? null : pairs[1];

        if ("0".equals(pairs[0]) && iRid != null) {
          iRid.fromString(value);
        } else if ("1".equals(pairs[0])) {
          className = value;
        } else if (pairs[0].startsWith("_") || pairs[0].equals("id")) {
          continue;
        } else if (iFields != null) {
          iFields.put(pairs[0], value);
        }
      }
    }
    return className;
  }
}
