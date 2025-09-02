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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Wrapper to use the HTTP response in functions and scripts. This class mimics the J2EE
 * HTTPResponse class.
 */
public class HttpResponseWrapper {

  private final HttpResponse response;

  /**
   * @param iResponse
   */
  public HttpResponseWrapper(final HttpResponse iResponse) {
    response = iResponse;
  }

  public HttpResponse getResponse() {
    return response;
  }

  /**
   * Returns the response's additional headers.
   *
   * @return The additional headers in form of String
   */
  public String getHeader() {
    return response.getHeaders();
  }

  /**
   * Sets the response's additional headers to send back. To specify multiple headers use the line
   * breaks.
   *
   * @param iHeader String containing the header
   * @return The object itself for fluent chained calls
   */
  public HttpResponseWrapper setHeader(final String iHeader) {
    response.setHeader(iHeader);
    return this;
  }

  /**
   * Returns the response's character set used.
   *
   * @return The character set in form of String
   */
  public String getCharacterSet() {
    return response.getCharacterSet();
  }

  /**
   * Sets the response's character set.
   *
   * @param iCharacterSet String containing the charset to use
   * @return The object itself for fluent chained calls
   */
  public HttpResponseWrapper setCharacterSet(final String iCharacterSet) {
    response.setCharacterSet(iCharacterSet);
    return this;
  }

  public String getHttpVersion() {
    return response.getHttpVersion();
  }

  public String[] getAdditionalResponseHeaders() {
    return response.getAdditionalHeaders();
  }

  public OutputStream getOutputStream() {
    return response.getOutputStream();
  }

  /**
   * Sets the response's status as HTTP code and reason.
   *
   * @param iHttpCode Response's HTTP code
   * @param iReason   Response's reason
   * @return The object itself for fluent chained calls
   */
  public HttpResponseWrapper writeStatus(final int iHttpCode, final String iReason)
      throws IOException {
    response.writeStatus(iHttpCode, iReason);
    return this;
  }

  /**
   * Sets the response's headers using the keep-alive.
   *
   * @param iContentType Response's content type
   * @return The object itself for fluent chained calls
   */
  public HttpResponseWrapper writeHeaders(final String iContentType) throws IOException {
    response.writeHeaders(iContentType);
    return this;
  }

  /**
   * Sets the response's headers specifying when using the keep-alive or not.
   *
   * @param iContentType Response's content type
   * @param iKeepAlive   Use the keep-alive of the connection
   * @return The object itself for fluent chained calls
   */
  public HttpResponseWrapper writeHeaders(final String iContentType, final boolean iKeepAlive)
      throws IOException {
    response.writeHeaders(iContentType, iKeepAlive);
    return this;
  }

  /**
   * Writes a line in the response. A line feed will be appended at the end of the content.
   *
   * @param iContent Content to send as string
   * @return The object itself for fluent chained calls
   */
  public HttpResponseWrapper writeLine(final String iContent) throws IOException {
    response.writeLine(iContent);
    return this;
  }

  /**
   * Writes content directly to the response.
   *
   * @param iContent Content to send as string
   * @return The object itself for fluent chained calls
   */
  public HttpResponseWrapper writeContent(final String iContent) throws IOException {
    response.writeContent(iContent);
    return this;
  }

  /**
   * Sends the complete HTTP response in one call.
   *
   * @param iCode        HTTP response's Code
   * @param iReason      Response's reason
   * @param iContentType Response's content type
   * @param iContent     Content to send. Content can be a string for plain text, binary data to
   *                     return directly binary information, Identifiable for a single record and
   *                     Collection<Identifiable> for a collection of records
   * @return The object itself for fluent chained calls
   */
  public HttpResponseWrapper send(
      final int iCode, final String iReason, final String iContentType, final Object iContent)
      throws IOException {
    response.send(iCode, iReason, iContentType, iContent, null);
    return this;
  }

  /**
   * Sends the complete HTTP response in one call specifying additional headers. Keep-alive is set.
   *
   * @param iCode        HTTP response's Code
   * @param iReason      Response's reason
   * @param iContentType Response's content type
   * @param iContent     Content to send. Content can be a string for plain text, binary data to
   *                     return directly binary information, Identifiable for a single record and
   *                     Collection<Identifiable> for a collection of records
   * @param iHeaders     Response's additional headers
   * @return The object itself for fluent chained calls
   */
  public HttpResponseWrapper send(
      final int iCode,
      final String iReason,
      final String iContentType,
      final Object iContent,
      final String iHeaders)
      throws IOException {
    response.send(iCode, iReason, iContentType, iContent, iHeaders);
    return this;
  }

  /**
   * Sends the complete HTTP response in one call specifying a stream as content.
   *
   * @param iCode        HTTP response's Code
   * @param iReason      Response's reason
   * @param iContentType Response's content type
   * @param iContent     java.io.InputStream object
   * @param iSize        Content size in bytes
   * @return The object itself for fluent chained calls
   */
  public HttpResponseWrapper sendStream(
      final int iCode,
      final String iReason,
      final String iContentType,
      final InputStream iContent,
      final long iSize)
      throws IOException {
    response.sendStream(iCode, iReason, iContentType, iContent, iSize);
    return this;
  }

  /**
   * Sends the complete HTTP response in one call specifying a stream as content.
   *
   * @param iCode        HTTP response's Code
   * @param iReason      Response's reason
   * @param iContentType Response's content type
   * @param iContent     java.io.InputStream object
   * @param iSize        Content size in bytes
   * @param iFileName    Optional file name
   * @return The object itself for fluent chained calls
   */
  public HttpResponseWrapper sendStream(
      final int iCode,
      final String iReason,
      final String iContentType,
      final InputStream iContent,
      final long iSize,
      final String iFileName)
      throws IOException {
    response.sendStream(iCode, iReason, iContentType, iContent, iSize, iFileName);
    return this;
  }

  /**
   * Flushes the content to the TCP/IP socket.
   *
   * @return The object itself for fluent chained calls
   */
  public HttpResponseWrapper flush() throws IOException {
    response.flush();
    return this;
  }

  public String getContentType() {
    return response.getContentType();
  }

  public void setContentType(final String contentType) {
    response.setContentType(contentType);
  }

  public String getContent() {
    return response.getContent();
  }

  public void setContent(String content) {
    response.setContent(content);
  }

  public int getCode() {
    return response.getCode();
  }

  public void setCode(int code) {
    response.setCode(code);
  }
}
