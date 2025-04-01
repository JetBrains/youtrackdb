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
package com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.put;

import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string.JSONSerializerJackson;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.ServerCommandDocumentAbstract;

public class ServerCommandPutDocument extends ServerCommandDocumentAbstract {

  private static final String[] NAMES = {"PUT|document/*"};

  @Override
  public boolean execute(final HttpRequest iRequest, HttpResponse iResponse) throws Exception {
    final var urlParts =
        checkSyntax(
            iRequest.getUrl(),
            2,
            "Syntax error: document/<database>[/<record-id>][?updateMode=full|partial]");

    iRequest.getData().commandInfo = "Edit Document";

    try (var db = getProfiledDatabaseSessionInstance(iRequest)) {
      RecordId recordId;
      if (urlParts.length > 2) {
        // EXTRACT RID
        final var parametersPos = urlParts[2].indexOf('?');
        final var rid =
            parametersPos > -1 ? urlParts[2].substring(0, parametersPos) : urlParts[2];
        recordId = new RecordId(rid);

        if (!recordId.isValidPosition()) {
          throw new IllegalArgumentException("Invalid Record ID in request: " + recordId);
        }
      } else {
        recordId = new ChangeableRecordId();
      }

      var pair =
          db.computeInTx(
              tx -> {
                var txRecordId = recordId;
                final var content = JSONSerializerJackson.mapFromJson(
                    iRequest.getContent());
                final int recordVersion;
                // UNMARSHALL DOCUMENT WITH REQUEST CONTENT

                if (iRequest.getIfMatch() != null)
                // USE THE IF-MATCH HTTP HEADER AS VERSION
                {
                  recordVersion = Integer.parseInt(iRequest.getIfMatch());
                } else {
                  recordVersion = -1;
                }

                if (!txRecordId.isValidPosition()) {
                  var rid = content.get(EntityHelper.ATTRIBUTE_RID);
                  if (rid != null) {
                    txRecordId = new RecordId(rid.toString());
                  }
                }

                if (!txRecordId.isValidPosition()) {
                  throw new IllegalArgumentException("Invalid Record ID in request: " + txRecordId);
                }

                final EntityImpl currentEntity;
                try {
                  currentEntity = db.load(txRecordId);
                } catch (RecordNotFoundException rnf) {
                  //noinspection ReturnOfNull
                  return null;
                }
                if (recordVersion >= 0 && recordVersion != currentEntity.getVersion()) {
                  throw new RecordNotFoundException(
                      db.getDatabaseName(), currentEntity.getIdentity(),
                      "Record version mismatch, expected: "
                          + recordVersion + ", found: " + currentEntity.getVersion());
                }

                var partialUpdateMode = false;
                var mode = iRequest.getParameter("updateMode");
                if (mode != null && mode.equalsIgnoreCase("partial")) {
                  partialUpdateMode = true;
                }

                mode = iRequest.getHeader("updateMode");
                if (mode != null && mode.equalsIgnoreCase("partial")) {
                  partialUpdateMode = true;
                }
                if (!partialUpdateMode) {
                  for (var propertyName : currentEntity.getPropertyNames()) {
                    currentEntity.removeProperty(propertyName);
                  }
                }

                currentEntity.updateFromMap(content);
                return new RawPair<>(currentEntity.detach(), currentEntity);
              });

      if (pair == null) {
        iResponse.send(
            HttpUtils.STATUS_NOTFOUND_CODE,
            HttpUtils.STATUS_NOTFOUND_DESCRIPTION,
            HttpUtils.CONTENT_TEXT_PLAIN,
            "Record " + recordId + " was not found.",
            null);
        return false;
      }

      var detached = pair.getFirst();
      var unloaded = pair.getSecond();
      ((ResultInternal) detached).setProperty(EntityHelper.ATTRIBUTE_VERSION,
          unloaded.getVersion());
      iResponse.send(
          HttpUtils.STATUS_OK_CODE,
          HttpUtils.STATUS_OK_DESCRIPTION,
          HttpUtils.CONTENT_JSON,
          detached.toJSON(),
          HttpUtils.HEADER_ETAG + unloaded.getVersion());
    }
    return false;
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }
}
