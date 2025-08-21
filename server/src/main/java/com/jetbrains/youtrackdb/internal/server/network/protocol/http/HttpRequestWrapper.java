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
package com.jetbrains.youtrackdb.internal.server.network.protocol.http;

import java.util.Map;
import javax.annotation.Nullable;

/**
 * Wrapper to use the HTTP request in functions and scripts. This class mimics the J2EE HTTPRequest
 * class.
 */
public class HttpRequestWrapper {

  private final HttpRequest request;
  private final String[] args;

  public HttpRequestWrapper(final HttpRequest iRequest) {
    this.request = iRequest;
    this.args = null;
  }

  public HttpRequestWrapper(final HttpRequest iRequest, final String[] iArgs) {
    this.request = iRequest;
    this.args = iArgs;
  }

  /**
   * Returns the request's content.
   *
   * @return The content in form of String
   */
  public String getContent() {
    return request.getContent();
  }

  /**
   * Gets the request's user name.
   *
   * @return The user name in form of String
   */
  public String getUser() {
    return request.getUser();
  }

  /**
   * Returns the request's content type.
   *
   * @return The content type in form of String
   */
  public String getContentType() {
    return request.getContentType();
  }

  /**
   * Return the request's HTTP version.
   *
   * @return The HTTP method version in form of String
   */
  public String getHttpVersion() {
    return request.getHttpVersion();
  }

  /**
   * Return the request's HTTP method called.
   *
   * @return The HTTP method name in form of String
   */
  public String getHttpMethod() {
    return request.getHttpMethod();
  }

  /**
   * Return the request's HTTP headers
   *
   * @return The HTTP request headers in the form of a map
   */
  public Map<String, String> getHeaders() {
    return request.getHeaders();
  }

  /**
   * Return the request's IF-MATCH header.
   *
   * @return The if-match header in form of String
   */
  public String getIfMatch() {
    return request.getIfMatch();
  }

  /**
   * Returns if the requests has multipart.
   *
   * @return true if is multipart, otherwise false
   */
  public boolean isMultipart() {
    return request.isMultipart();
  }

  /**
   * Returns the call's argument passed in REST form. Example: /2012/10/26
   *
   * @return Array of arguments
   */
  public String[] getArguments() {
    return args;
  }

  /**
   * Returns the argument by position
   *
   * @return Array of arguments
   */
  @Nullable
  public String getArgument(final int iPosition) {
    return args != null && args.length > iPosition ? args[iPosition] : null;
  }

  /**
   * Returns the request's parameters.
   *
   * @return The parameters as a Map<String,String>
   */
  public Map<String, String> getParameters() {
    return request.getParameters();
  }

  /**
   * Returns the request's parameter.
   *
   * @return The parameter value if any otherwise null
   */
  @Nullable
  public String getParameter(final String iName) {
    return request.getParameters() != null ? request.getParameters().get(iName) : null;
  }

  /**
   * Checks how many parameters have been received.
   *
   * @return The number of parameters found between the passed ones
   */
  public int hasParameters(final String... iNames) {
    var found = 0;

    if (iNames != null && request.getParameters() != null) {
      for (var name : iNames) {
        found += request.getParameters().containsKey(name) ? 1 : 0;
      }
    }

    return found;
  }

  /**
   * Returns the session-id.
   *
   * @return The session-id in form of String
   */
  public String getSessionId() {
    return request.getSessionId();
  }

  /**
   * Returns the request's URL.
   *
   * @return The URL requested in form of String
   */
  public String getURL() {
    return request.getUrl();
  }

  public String getRemoteAddress() {
    return request.getRemoteAddress();
  }
}
