package com.jetbrains.youtrackdb.internal.client.remote.message;

import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;

/**
 *
 */
public interface BinaryPushResponse {

  void write(final ChannelDataOutput network) throws IOException;

  void read(ChannelDataInput channel) throws IOException;
}
