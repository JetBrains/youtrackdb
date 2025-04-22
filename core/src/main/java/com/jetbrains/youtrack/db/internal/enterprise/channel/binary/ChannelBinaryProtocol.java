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
package com.jetbrains.youtrack.db.internal.enterprise.channel.binary;

public class ChannelBinaryProtocol {
  // OUTGOING
  public static final byte REQUEST_SHUTDOWN = 1;
  public static final byte REQUEST_CONNECT = 2;
  public static final byte REQUEST_HANDSHAKE = 20;

  public static final byte REQUEST_DB_OPEN = 3;
  public static final byte REQUEST_DB_CREATE = 4;
  public static final byte REQUEST_DB_CLOSE = 5;
  public static final byte REQUEST_DB_EXIST = 6;
  public static final byte REQUEST_DB_DROP = 7;
  public static final byte REQUEST_DB_REOPEN = 17;

  public static final byte REQUEST_INCREMENTAL_BACKUP = 27; // since 2.2

  public static final byte REQUEST_QUERY = 45; // since 3.0
  public static final byte REQUEST_CLOSE_QUERY = 46; // since 3.0
  public static final byte REQUEST_QUERY_NEXT_PAGE = 47; // since 3.0

  public static final byte REQUEST_SERVER_QUERY = 50; // since 3.2
  public static final byte REQUEST_CONFIG_GET = 70;
  public static final byte REQUEST_CONFIG_SET = 71;
  public static final byte REQUEST_CONFIG_LIST = 72;
  public static final byte REQUEST_DB_LIST = 74; // SINCE 1.0rc6
  public static final byte REQUEST_SERVER_INFO = 75; // SINCE 2.2.0

  public static final byte REQUEST_OK_PUSH = 90;

  public static final byte REQUEST_DB_FREEZE = 94; // SINCE 1.1.0
  public static final byte REQUEST_DB_RELEASE = 95; // SINCE 1.1.0

  public static final byte REQUEST_DB_IMPORT = 98;

  public static final byte SUBSCRIBE_PUSH = 100;
  public static final byte UNSUBSCRIBE_PUSH = 101;

  // INCOMING
  public static final byte RESPONSE_STATUS_OK = 0;
  public static final byte RESPONSE_STATUS_ERROR = 1;
  public static final byte PUSH_DATA = 3;

  public static final int PROTOCOL_VERSION_26 = 26;
  public static final int PROTOCOL_VERSION_32 = 32; // STREAMABLE RESULT SET

  public static final int PROTOCOL_VERSION_37 = 37;
  public static final int PROTOCOL_VERSION_38 = 38;

  public static final int CURRENT_PROTOCOL_VERSION = PROTOCOL_VERSION_38;
  public static final int OLDEST_SUPPORTED_PROTOCOL_VERSION = PROTOCOL_VERSION_26;

  public static final byte SUBSCRIBE_PUSH_LIVE_QUERY = 2;
  public static final byte UNSUBSCRIBE_PUSH_LIVE_QUERY = 2;
  public static final byte REQUEST_PUSH_LIVE_QUERY = 81; // SINCE 2.1

  // Default encoding, in future will be possible to have other encodings
  public static final byte ENCODING_DEFAULT = 0;

  // Error encoding
  public static final byte ERROR_MESSAGE_JAVA = 0;
  public static final byte ERROR_MESSAGE_STRING = 1;
}
