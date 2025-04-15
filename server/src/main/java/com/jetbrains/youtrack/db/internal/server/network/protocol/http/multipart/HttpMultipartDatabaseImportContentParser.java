/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrack.db.internal.server.network.protocol.http.multipart;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 *
 */
public class HttpMultipartDatabaseImportContentParser
    implements HttpMultipartContentParser<InputStream> {

  @Override
  public InputStream parse(
      final HttpRequest iRequest,
      final Map<String, String> headers,
      final HttpMultipartContentInputStream in,
      DatabaseSessionInternal db)
      throws IOException {
    final var fileName = headers.get(HttpUtils.MULTIPART_CONTENT_FILENAME);

    if (fileName.endsWith(".gz") || fileName.endsWith(".gzip")) {
      return new GZIPInputStream(in, 16384); // 16KB
    }

    return in;
  }
}
