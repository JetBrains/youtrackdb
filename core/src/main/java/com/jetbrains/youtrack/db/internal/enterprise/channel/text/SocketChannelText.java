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
package com.jetbrains.youtrack.db.internal.enterprise.channel.text;

import com.jetbrains.youtrack.db.api.config.ContextConfiguration;
import com.jetbrains.youtrack.db.internal.enterprise.channel.SocketChannel;
import java.io.IOException;
import java.net.Socket;

public class SocketChannelText extends SocketChannel {

  public SocketChannelText(final Socket iSocket, final ContextConfiguration iConfig)
      throws IOException {
    super(iSocket, iConfig);
  }

  /**
   * @param iBuffer           byte[] to fill
   * @param iStartingPosition Offset to start to fill the buffer
   * @param iContentLength    Length of expected content to read
   * @return total of bytes read
   * @throws IOException
   */
  public int read(final byte[] iBuffer, final int iStartingPosition, final int iContentLength)
      throws IOException {
    int pos;
    int read = 0;
    pos = iStartingPosition;

    for (int required = iContentLength; required > 0; required -= read) {
      read = inStream.read(iBuffer, pos, required);
      pos += read;
    }

    return pos - iStartingPosition;
  }

  public byte read() throws IOException {
    return (byte) inStream.read();
  }

  public byte[] readBytes(final int iTotal) throws IOException {
    final byte[] buffer = new byte[iTotal];
    inStream.read(buffer);
    return buffer;
  }

  public SocketChannelText writeBytes(final byte[] iContent) throws IOException {
    outStream.write(iContent);
    return this;
  }
}
