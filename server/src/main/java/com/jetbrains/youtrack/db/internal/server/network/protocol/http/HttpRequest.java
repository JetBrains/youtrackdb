package com.jetbrains.youtrack.db.internal.server.network.protocol.http;

import com.jetbrains.youtrack.db.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrack.db.internal.core.security.ParsedToken;
import com.jetbrains.youtrack.db.internal.server.network.protocol.NetworkProtocolData;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.multipart.HttpMultipartBaseInputStream;
import java.io.InputStream;
import java.util.Map;

public interface HttpRequest {

  String getUser();

  InputStream getInputStream();

  String getParameter(String iName);

  void addHeader(String h);

  Map<String, String> getUrlEncodedContent();

  void setParameters(Map<String, String> parameters);

  Map<String, String> getParameters();

  String getHeader(String iName);

  Map<String, String> getHeaders();

  String getRemoteAddress();

  String getContent();

  void setContent(String content);

  String getUrl();

  ContextConfiguration getConfiguration();

  InputStream getIn();

  NetworkProtocolData getData();

  ONetworkHttpExecutor getExecutor();

  String getAuthorization();

  void setAuthorization(String authorization);

  String getSessionId();

  void setSessionId(String sessionId);

  void setUrl(String url);

  String getHttpMethod();

  void setHttpMethod(String httpMethod);

  String getHttpVersion();

  void setHttpVersion(String httpVersion);

  String getContentType();

  void setContentType(String contentType);

  String getContentEncoding();

  void setContentEncoding(String contentEncoding);

  String getAcceptEncoding();

  void setAcceptEncoding(String acceptEncoding);

  HttpMultipartBaseInputStream getMultipartStream();

  void setMultipartStream(HttpMultipartBaseInputStream multipartStream);

  String getBoundary();

  void setBoundary(String boundary);

  String getDatabaseName();

  void setDatabaseName(String databaseName);

  boolean isMultipart();

  void setMultipart(boolean multipart);

  String getIfMatch();

  void setIfMatch(String ifMatch);

  String getAuthentication();

  void setAuthentication(String authentication);

  boolean isKeepAlive();

  void setKeepAlive(boolean keepAlive);

  void setHeaders(Map<String, String> headers);

  String getBearerTokenRaw();

  void setBearerTokenRaw(String bearerTokenRaw);

  ParsedToken getBearerToken();

  void setBearerToken(ParsedToken bearerToken);
}
