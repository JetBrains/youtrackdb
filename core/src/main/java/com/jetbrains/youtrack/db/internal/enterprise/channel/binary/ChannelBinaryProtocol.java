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
  public static final byte REQUEST_HANDSHAKE = 3;

  public static final byte REQUEST_DB_OPEN = 4;
  public static final byte REQUEST_DB_CREATE = 5;
  public static final byte REQUEST_DB_CLOSE = 6;
  public static final byte REQUEST_DB_EXIST = 7;
  public static final byte REQUEST_DB_DROP = 8;
  public static final byte REQUEST_DB_REOPEN = 9;

  public static final byte REQUEST_INCREMENTAL_BACKUP = 10;

  public static final byte REQUEST_QUERY = 11;
  public static final byte REQUEST_CLOSE_QUERY = 12;
  public static final byte REQUEST_QUERY_NEXT_PAGE = 13;

  public static final byte REQUEST_SERVER_QUERY = 14;
  public static final byte REQUEST_CONFIG_GET = 15;
  public static final byte REQUEST_CONFIG_SET = 16;
  public static final byte REQUEST_CONFIG_LIST = 17;
  public static final byte REQUEST_DB_LIST = 18;
  public static final byte REQUEST_SERVER_INFO = 19;

  public static final byte REQUEST_OK_PUSH = 20;

  public static final byte REQUEST_DB_FREEZE = 21;
  public static final byte REQUEST_DB_RELEASE = 22;

  public static final byte REQUEST_DB_IMPORT = 23;

  public static final byte SUBSCRIBE_PUSH = 24;
  public static final byte UNSUBSCRIBE_PUSH = 25;

  public static final byte REQUEST_PUSH_LIVE_QUERY = 26;

  public static final byte REQUEST_ROLLBACK_ACTIVE_TX = 27;

  // INCOMING
  public static final byte RESPONSE_STATUS_OK = 1;
  public static final byte RESPONSE_STATUS_ERROR = 2;
  public static final byte PUSH_DATA = 3;

  public static final byte SUBSCRIBE_PUSH_LIVE_QUERY = 4;
  public static final byte UNSUBSCRIBE_PUSH_LIVE_QUERY = 5;

  // Default encoding, in future will be possible to have other encodings
  public static final byte ENCODING_DEFAULT = 0;

  // Error encoding
  public static final byte ERROR_MESSAGE_JAVA = 0;
  public static final byte ERROR_MESSAGE_STRING = 1;

  public static final int PROTOCOL_VERSION_1 = 1;
  public static final int CURRENT_PROTOCOL_VERSION = PROTOCOL_VERSION_1;
  public static final int OLDEST_SUPPORTED_PROTOCOL_VERSION = 1;
}
