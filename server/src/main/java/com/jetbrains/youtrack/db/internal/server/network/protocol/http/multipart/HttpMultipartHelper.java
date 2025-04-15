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

import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import java.io.IOException;

/**
 *
 */
public class HttpMultipartHelper {

  protected static boolean isMultipartPartHeader(StringBuilder header) {
    final var linePart = header.toString();
    return ((linePart.equals(HttpUtils.MULTIPART_CONTENT_CHARSET))
        || (linePart.equals(HttpUtils.MULTIPART_CONTENT_FILENAME))
        || (linePart.equals(HttpUtils.MULTIPART_CONTENT_NAME))
        || (linePart.equals(HttpUtils.MULTIPART_CONTENT_TYPE))
        || (linePart.equals(HttpUtils.MULTIPART_CONTENT_DISPOSITION))
        || (linePart.equals(HttpUtils.MULTIPART_CONTENT_TRANSFER_ENCODING)));
  }

  public static boolean isEndRequest(final HttpRequest iRequest) throws IOException {
    var in = iRequest.getMultipartStream().read();
    if (((char) in) == '-') {
      in = iRequest.getMultipartStream().read();
      if (((char) in) == '-') {
        in = iRequest.getMultipartStream().read();
        if (((char) in) == '\r') {
          in = iRequest.getMultipartStream().read();
          if (((char) in) == '\n') {
            return true;
          } else {
            iRequest.getMultipartStream().setSkipInput(in);
          }
        } else {
          iRequest.getMultipartStream().setSkipInput(in);
        }
      } else {
        iRequest.getMultipartStream().setSkipInput(in);
      }
    } else {
      iRequest.getMultipartStream().setSkipInput(in);
    }
    return false;
  }
}
