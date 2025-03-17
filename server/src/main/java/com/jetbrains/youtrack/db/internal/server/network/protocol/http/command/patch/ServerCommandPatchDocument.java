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
package com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.patch;

import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string.RecordSerializerJackson;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.ServerCommandDocumentAbstract;

public class ServerCommandPatchDocument extends ServerCommandDocumentAbstract {

  private static final String[] NAMES = {"PATCH|document/*"};

  @Override
  public boolean execute(final HttpRequest iRequest, HttpResponse iResponse) throws Exception {
    final var urlParts =
        checkSyntax(iRequest.getUrl(), 2, "Syntax error: document/<database>[/<record-id>]");

    iRequest.getData().commandInfo = "Edit Document";
    try (var db = getProfiledDatabaseSessionInstance(iRequest)) {
      RawPair<Boolean, RID> result =
          db.computeInTx(
              () -> {
                RecordId recordId;

                if (urlParts.length > 2) {
                  // EXTRACT RID
                  final var parametersPos = urlParts[2].indexOf('?');
                  final var rid =
                      parametersPos > -1 ? urlParts[2].substring(0, parametersPos) : urlParts[2];
                  recordId = new RecordId(rid);

                  if (!recordId.isValid()) {
                    throw new IllegalArgumentException("Invalid Record ID in request: " + recordId);
                  }
                } else {
                  recordId = new ChangeableRecordId();
                }

                // UNMARSHALL DOCUMENT WITH REQUEST CONTENT
                var content = RecordSerializerJackson.mapFromJson(iRequest.getContent());
                final int recordVersion;

                if (iRequest.getIfMatch() != null)
                // USE THE IF-MATCH HTTP HEADER AS VERSION
                {
                  recordVersion = Integer.parseInt(iRequest.getIfMatch());
                } else {
                  recordVersion = -1;
                }

                if (!recordId.isValid()) {
                  var rid = content.get(EntityHelper.ATTRIBUTE_RID);
                  if (rid != null) {
                    recordId = new RecordId(rid.toString());
                  }
                }

                if (!recordId.isValid()) {
                  throw new IllegalArgumentException("Invalid Record ID in request: " + recordId);
                }
                final EntityImpl currentEntity;

                try {
                  currentEntity = db.load(recordId);
                } catch (RecordNotFoundException rnf) {
                  return new RawPair<>(false, recordId);
                }
                if (recordVersion != -1
                    && recordVersion != currentEntity.getVersion()) {
                  throw new IllegalArgumentException(
                      "Record with rid: " + recordId + " has version: "
                          + currentEntity.getVersion()
                          + " but the request has version: "
                          + recordVersion);
                }

                currentEntity.updateFromMap(content);

                return new RawPair<>(true, recordId);
              });

      if (!result.first) {
        iResponse.send(
            HttpUtils.STATUS_NOTFOUND_CODE,
            HttpUtils.STATUS_NOTFOUND_DESCRIPTION,
            HttpUtils.CONTENT_TEXT_PLAIN,
            "Record " + result.second + " was not found.",
            null);
        return false;
      }

      var record = db.load(result.second);
      iResponse.send(
          HttpUtils.STATUS_OK_CODE,
          HttpUtils.STATUS_OK_DESCRIPTION,
          HttpUtils.CONTENT_TEXT_PLAIN,
          record.toJSON(),
          HttpUtils.HEADER_ETAG + record.getVersion());
    }
    return false;
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }
}
