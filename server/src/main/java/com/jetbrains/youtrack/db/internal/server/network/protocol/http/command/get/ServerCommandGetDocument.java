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
package com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get;

import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.ServerCommandAuthenticatedDbAbstract;

public class ServerCommandGetDocument extends ServerCommandAuthenticatedDbAbstract {

  private static final String[] NAMES = {"GET|document/*", "HEAD|document/*"};

  @Override
  public boolean execute(final HttpRequest iRequest, HttpResponse iResponse) throws Exception {
    final var urlParts =
        checkSyntax(
            iRequest.getUrl(), 3, "Syntax error: document/<database>/<record-id>[/fetchPlan]");

    final var fetchPlan = urlParts.length > 3 ? urlParts[3] : null;

    iRequest.getData().commandInfo = "Load document";

    final var parametersPos = urlParts[2].indexOf('?');
    var rid = parametersPos > -1 ? urlParts[2].substring(0, parametersPos) : urlParts[2];
    try (var db = getProfiledDatabaseSessionInstance(iRequest)) {
      return db.computeInTx(transaction -> {
        final DBRecord rec;
        try {
          rec = db.load(new RecordId(rid));
        } catch (RecordNotFoundException e) {
          iResponse.send(
              HttpUtils.STATUS_NOTFOUND_CODE,
              HttpUtils.STATUS_NOTFOUND_DESCRIPTION,
              HttpUtils.CONTENT_JSON,
              "Record with id '" + urlParts[2] + "' was not found.",
              null);
          return false;
        }

        if (iRequest.getHttpMethod().equals("HEAD"))
        // JUST SEND HTTP CODE 200
        {
          iResponse.send(
              HttpUtils.STATUS_OK_CODE,
              HttpUtils.STATUS_OK_DESCRIPTION,
              null,
              null,
              HttpUtils.HEADER_ETAG + rec.getVersion());
        } else {
          final var ifNoneMatch = iRequest.getHeader("If-None-Match");
          if (ifNoneMatch != null && Integer.toString(rec.getVersion()).equals(ifNoneMatch)) {
            // SAME CONTENT, DON'T SEND BACK RECORD
            iResponse.send(
                HttpUtils.STATUS_OK_NOMODIFIED_CODE,
                HttpUtils.STATUS_OK_NOMODIFIED_DESCRIPTION,
                null,
                null,
                HttpUtils.HEADER_ETAG + rec.getVersion());
          }

          // SEND THE DOCUMENT BACK
          iResponse.writeRecord(rec, fetchPlan, null);

        }
        return false;
      });
    }
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }
}
