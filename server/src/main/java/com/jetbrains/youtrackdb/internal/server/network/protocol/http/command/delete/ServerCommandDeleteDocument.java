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

import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassSnapshot;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.command.ServerCommandDocumentAbstract;

public class ServerCommandDeleteDocument extends ServerCommandDocumentAbstract {

  private static final String[] NAMES = {"DELETE|document/*"};

  @Override
  public boolean execute(final HttpRequest iRequest, HttpResponse iResponse) throws Exception {

    try (var session = getProfiledDatabaseSessionInstance(iRequest)) {
      final var urlParts =
          checkSyntax(iRequest.getUrl(), 3, "Syntax error: document/<database>/<record-id>");

      iRequest.getData().commandInfo = "Delete document";

      // PARSE PARAMETERS
      final var parametersPos = urlParts[2].indexOf('?');
      final var rid = parametersPos > -1 ? urlParts[2].substring(0, parametersPos) : urlParts[2];
      final var recordId = RecordIdInternal.fromString(rid, false);

      if (!recordId.isValidPosition()) {
        throw new IllegalArgumentException("Invalid Record ID in request: " + urlParts[2]);
      }

      session.executeInTx(
          transaction -> {
            final EntityImpl entity = transaction.load(recordId);

            // UNMARSHALL DOCUMENT WITH REQUEST CONTENT
            if (iRequest.getContent() != null)
            // GET THE VERSION FROM THE DOCUMENT
            {
              entity.updateFromJSON(iRequest.getContent());
            } else {
              if (iRequest.getIfMatch() != null)
              // USE THE IF-MATCH HTTP HEADER AS VERSION
              {
                final int iVersion = Integer.parseInt(iRequest.getIfMatch());
                final var rec = (RecordAbstract) entity;
                rec.setVersion(iVersion);
              } else
              // IGNORE THE VERSION
              {
                final var rec = (RecordAbstract) entity;
                rec.setVersion(-1);
              }
            }

            SchemaClassSnapshot result = null;
            result = entity.getImmutableSchemaClass(session);
            final SchemaClass cls = result;
            if (cls != null) {
              if (cls.isChildOf("V"))
              // DELETE IT AS VERTEX
              {
                session.execute("DELETE VERTEX ?", recordId).close();
              } else if (cls.isChildOf("E"))
              // DELETE IT AS EDGE
              {
                session.execute("DELETE EDGE ?", recordId).close();
              } else {
                entity.delete();
              }
            } else {
              entity.delete();
            }
          });

      iResponse.send(
          HttpUtils.STATUS_OK_NOCONTENT_CODE,
          HttpUtils.STATUS_OK_NOCONTENT_DESCRIPTION,
          HttpUtils.CONTENT_TEXT_PLAIN,
          null,
          null);
    }
    return false;
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }
}
