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
package com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.post;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.ServerCommandDocumentAbstract;

public class ServerCommandPostDocument extends ServerCommandDocumentAbstract {

  private static final String[] NAMES = {"POST|document/*"};

  @Override
  public boolean execute(final HttpRequest iRequest, HttpResponse iResponse) throws Exception {
    checkSyntax(iRequest.getUrl(), 2, "Syntax error: document/<database>");

    iRequest.getData().commandInfo = "Create document";

    EntityImpl d;
    try (var db = getProfiledDatabaseSessionInstance(iRequest)) {
      d =
          db.computeInTx(
              () -> {
                var entity = new EntityImpl(db);
                entity.updateFromJSON(iRequest.getContent());
                final var rec = (RecordAbstract) entity;
                rec.setVersion(0);

                // ASSURE TO MAKE THE RECORD ID INVALID
                entity.getIdentity().setClusterPosition(RID.CLUSTER_POS_INVALID);

                return entity;
              });

      d = db.bindToSession(d);
      iResponse.send(
          HttpUtils.STATUS_CREATED_CODE,
          HttpUtils.STATUS_CREATED_DESCRIPTION,
          HttpUtils.CONTENT_JSON,
          d.toJSON(),
          HttpUtils.HEADER_ETAG + d.getVersion());

    }
    return false;
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }
}
