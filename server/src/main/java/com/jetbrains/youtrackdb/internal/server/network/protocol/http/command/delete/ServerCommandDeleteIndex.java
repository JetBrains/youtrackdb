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
package com.jetbrains.youtrackdb.internal.server.network.protocol.http.command.delete;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.command.ServerCommandDocumentAbstract;

public class ServerCommandDeleteIndex extends ServerCommandDocumentAbstract {

  private static final String[] NAMES = {"DELETE|index/*"};

  @Override
  public boolean execute(final HttpRequest iRequest, HttpResponse iResponse) throws Exception {
    final var urlParts =
        checkSyntax(
            iRequest.getUrl(), 3, "Syntax error: index/<database>/<index-name>/<key>/[<value>]");

    iRequest.getData().commandInfo = "Index remove";

    DatabaseSessionInternal db = null;
    try {
      db = getProfiledDatabaseSessionInstance(iRequest);

      final var index = db.getSharedContext().getIndexManager().getIndex(urlParts[2]);
      if (index == null) {
        throw new IllegalArgumentException("Index name '" + urlParts[2] + "' not found");
      }

      db.executeInTxInternal(transaction -> {
        final boolean found;
        if (urlParts.length > 4) {
          found = index.remove(transaction, urlParts[3],
              RecordIdInternal.fromString(urlParts[3], false));
        } else {
          found = index.remove(transaction, urlParts[3]);
        }

        if (found) {
          iResponse.send(
              HttpUtils.STATUS_OK_CODE,
              HttpUtils.STATUS_OK_DESCRIPTION,
              HttpUtils.CONTENT_TEXT_PLAIN,
              null,
              null);
        } else {
          iResponse.send(
              HttpUtils.STATUS_NOTFOUND_CODE,
              HttpUtils.STATUS_NOTFOUND_DESCRIPTION,
              HttpUtils.CONTENT_TEXT_PLAIN,
              null,
              null);
        }
      });
    } finally {
      if (db != null) {
        db.close();
      }
    }
    return false;
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }
}
