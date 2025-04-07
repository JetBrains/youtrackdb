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

import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.ServerCommandDocumentAbstract;

public class ServerCommandPostDocument extends ServerCommandDocumentAbstract {

  private static final String[] NAMES = {"POST|document/*"};

  @Override
  public boolean execute(final HttpRequest iRequest, HttpResponse iResponse) throws Exception {
    checkSyntax(iRequest.getUrl(), 2, "Syntax error: document/<database>");

    iRequest.getData().commandInfo = "Create entity";

    try (var db = getProfiledDatabaseSessionInstance(iRequest)) {
      var detached = db.computeInTx(
          transaction -> {
            var entity = transaction.createOrLoadEntityFromJson(iRequest.getContent());
            return new RawPair<>(entity, entity.detach());
          });
      ((ResultInternal) detached.second()).setProperty(EntityHelper.ATTRIBUTE_VERSION,
          detached.first().getVersion());
      iResponse.send(
          HttpUtils.STATUS_CREATED_CODE,
          HttpUtils.STATUS_CREATED_DESCRIPTION,
          HttpUtils.CONTENT_JSON,
          detached.second().toJSON(),
          HttpUtils.HEADER_ETAG + detached.first().getVersion());
    }
    return false;
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }
}
