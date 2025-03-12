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

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.ServerCommandAuthenticatedDbAbstract;
import java.util.ArrayList;
import java.util.List;

public class ServerCommandGetCluster extends ServerCommandAuthenticatedDbAbstract {

  private static final String[] NAMES = {"GET|cluster/*"};

  @Override
  public boolean execute(final HttpRequest iRequest, HttpResponse iResponse) throws Exception {
    var urlParts =
        checkSyntax(
            iRequest.getUrl(),
            3,
            "Syntax error: cluster/<database>/<cluster-name>[/<limit>]<br>Limit is optional and is"
                + " setted to 20 by default. Set expressely to 0 to have no limits.");

    iRequest.getData().commandInfo = "Browse cluster";
    iRequest.getData().commandDetail = urlParts[2];

    try (var db = getProfiledDatabaseSessionInstance(iRequest)) {
      if (db.getClusterIdByName(urlParts[2]) > -1) {
        final var limit = urlParts.length > 3 ? Integer.parseInt(urlParts[3]) : 20;

        final List<Identifiable> response = new ArrayList<Identifiable>();
        var recordIterator = db.browseCluster(urlParts[2]);
        while (recordIterator.hasNext()) {
          final var rec = recordIterator.next();
          if (limit > 0 && response.size() >= limit) {
            break;
          }

          response.add(rec);
        }

        iResponse.writeRecords(response, db);
      } else {
        iResponse.send(HttpUtils.STATUS_NOTFOUND_CODE, null, null, null, null);
      }

    }
    return false;
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }
}
