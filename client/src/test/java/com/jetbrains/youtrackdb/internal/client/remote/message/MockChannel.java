package com.jetbrains.youtrackdb.internal.client.remote.message;

import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.SocketChannelBinary;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

/**
 *
 */
public class MockChannel extends SocketChannelBinary {

  private final ByteArrayOutputStream byteOut;

  public MockChannel() throws IOException {
    super(new Socket(), new ContextConfiguration());

    this.byteOut = new ByteArrayOutputStream();
    this.out = new DataOutputStream(byteOut);
  }

  @Override
  public void close() {
    this.in = new DataInputStream(new ByteArrayInputStream(byteOut.toByteArray()));
  }
}
