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
package com.jetbrains.youtrack.db.internal.server.network.protocol.http;

import com.jetbrains.youtrack.db.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrack.db.internal.core.security.ParsedToken;
import com.jetbrains.youtrack.db.internal.server.network.protocol.NetworkProtocolData;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.multipart.HttpMultipartBaseInputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Maintains information about current HTTP request.
 */
public abstract class HttpRequestAbstract implements HttpRequest {

  private final ContextConfiguration configuration;
  private final InputStream in;
  private final NetworkProtocolData data;
  private final ONetworkHttpExecutor executor;
  private String content;
  private Map<String, String> parameters;
  private String sessionId;
  private String authorization;
  private String databaseName;

  public HttpRequestAbstract(
      final ONetworkHttpExecutor iExecutor,
      final InputStream iInStream,
      final NetworkProtocolData iData,
      final ContextConfiguration iConfiguration) {
    executor = iExecutor;
    in = iInStream;
    data = iData;
    configuration = iConfiguration;
  }

  @Nullable
  @Override
  public String getUser() {
    return authorization != null ? authorization.substring(0, authorization.indexOf(':')) : null;
  }

  @Override
  public InputStream getInputStream() {
    return in;
  }

  @Nullable
  @Override
  public String getParameter(final String iName) {
    return parameters != null ? parameters.get(iName) : null;
  }

  @Override
  public void addHeader(final String h) {
    if (getHeaders() == null) {
      setHeaders(new HashMap<String, String>());
    }

    final var pos = h.indexOf(':');
    if (pos > -1) {
      getHeaders()
          .put(h.substring(0, pos).trim().toLowerCase(Locale.ENGLISH), h.substring(pos + 1).trim());
    }
  }

  @Nullable
  @Override
  public Map<String, String> getUrlEncodedContent() {
    if (content == null || content.length() < 1) {
      return null;
    }
    var retMap = new HashMap<String, String>();
    String key;
    String value;
    var pairs = content.split("\\&");
    for (var i = 0; i < pairs.length; i++) {
      var fields = pairs[i].split("=");
      if (fields.length == 2) {
        key = URLDecoder.decode(fields[0], StandardCharsets.UTF_8);
        value = URLDecoder.decode(fields[1], StandardCharsets.UTF_8);
        retMap.put(key, value);
      }
    }
    return retMap;
  }

  @Override
  public void setParameters(Map<String, String> parameters) {
    this.parameters = parameters;
  }

  @Override
  public Map<String, String> getParameters() {
    return parameters;
  }

  @Override
  public String getHeader(final String iName) {
    return getHeaders().get(iName.toLowerCase(Locale.ENGLISH));
  }

  @Override
  public abstract Map<String, String> getHeaders();

  @Override
  public String getRemoteAddress() {
    if (data.caller != null) {
      return data.caller;
    }
    return executor.getRemoteAddress();
  }

  @Override
  public String getContent() {
    return content;
  }

  @Override
  public void setContent(String content) {
    this.content = content;
  }

  @Override
  public abstract String getUrl();

  @Override
  public ContextConfiguration getConfiguration() {
    return configuration;
  }

  @Override
  public InputStream getIn() {
    return in;
  }

  @Override
  public NetworkProtocolData getData() {
    return data;
  }

  @Override
  public ONetworkHttpExecutor getExecutor() {
    return executor;
  }

  @Override
  public String getAuthorization() {
    return authorization;
  }

  @Override
  public void setAuthorization(String authorization) {
    this.authorization = authorization;
  }

  @Override
  public String getSessionId() {
    return sessionId;
  }

  @Override
  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  @Override
  public abstract void setUrl(String url);

  @Override
  public abstract String getHttpMethod();

  @Override
  public abstract void setHttpMethod(String httpMethod);

  @Override
  public abstract String getHttpVersion();

  @Override
  public abstract void setHttpVersion(String httpVersion);

  @Override
  public abstract String getContentType();

  @Override
  public abstract void setContentType(String contentType);

  @Override
  public abstract String getContentEncoding();

  @Override
  public abstract void setContentEncoding(String contentEncoding);

  @Override
  public abstract String getAcceptEncoding();

  @Override
  public abstract void setAcceptEncoding(String acceptEncoding);

  @Override
  public abstract HttpMultipartBaseInputStream getMultipartStream();

  @Override
  public abstract void setMultipartStream(HttpMultipartBaseInputStream multipartStream);

  @Override
  public abstract String getBoundary();

  @Override
  public abstract void setBoundary(String boundary);

  @Override
  public String getDatabaseName() {
    return databaseName;
  }

  @Override
  public void setDatabaseName(String databaseName) {
    this.databaseName = databaseName;
  }

  @Override
  public abstract boolean isMultipart();

  @Override
  public abstract void setMultipart(boolean multipart);

  @Override
  public abstract String getIfMatch();

  @Override
  public abstract void setIfMatch(String ifMatch);

  @Override
  public abstract String getAuthentication();

  @Override
  public abstract void setAuthentication(String authentication);

  @Override
  public abstract boolean isKeepAlive();

  @Override
  public abstract void setKeepAlive(boolean keepAlive);

  @Override
  public abstract void setHeaders(Map<String, String> headers);

  @Override
  public abstract String getBearerTokenRaw();

  @Override
  public abstract void setBearerTokenRaw(String bearerTokenRaw);

  @Override
  public abstract ParsedToken getBearerToken();

  @Override
  public abstract void setBearerToken(ParsedToken bearerToken);
}
