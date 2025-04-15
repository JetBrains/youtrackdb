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

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.JSONWriter;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.ServerCommandAuthenticatedDbAbstract;
import java.io.StringWriter;

public class ServerCommandGetClass extends ServerCommandAuthenticatedDbAbstract {

  private static final String[] NAMES = {"GET|class/*"};

  @Override
  public boolean execute(final HttpRequest iRequest, HttpResponse iResponse) throws Exception {
    var urlParts =
        checkSyntax(iRequest.getUrl(), 3, "Syntax error: class/<database>/<class-name>");

    iRequest.getData().commandInfo = "Returns the information of a class in the schema";
    iRequest.getData().commandDetail = urlParts[2];

    DatabaseSessionInternal db = null;

    try {
      db = getProfiledDatabaseSessionInstance(iRequest);

      if (db.getMetadata().getSchema().existsClass(urlParts[2])) {
        var cls = db.getMetadata().getSchemaInternal().getClassInternal(urlParts[2]);
        final var buffer = new StringWriter();
        final var json = new JSONWriter(buffer, HttpResponse.JSON_FORMAT);
        ServerCommandGetDatabase.exportClass(db, json, cls);
        iResponse.send(
            HttpUtils.STATUS_OK_CODE,
            HttpUtils.STATUS_OK_DESCRIPTION,
            HttpUtils.CONTENT_JSON,
            buffer.toString(),
            null);
      } else {
        iResponse.send(HttpUtils.STATUS_NOTFOUND_CODE, null, null, null, null);
      }

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
