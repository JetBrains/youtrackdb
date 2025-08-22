package com.jetbrains.youtrackdb.internal.enterprise.channel.binary;

import com.jetbrains.youtrackdb.api.record.RID;
import java.io.IOException;
import java.io.OutputStream;

/**
 *
 */
public interface ChannelDataOutput {

  ChannelDataOutput writeByte(final byte iContent) throws IOException;

  ChannelDataOutput writeBoolean(final boolean iContent) throws IOException;

  ChannelDataOutput writeInt(final int iContent) throws IOException;

  ChannelDataOutput writeLong(final long iContent) throws IOException;

  ChannelDataOutput writeShort(final short iContent) throws IOException;

  ChannelDataOutput writeString(final String iContent) throws IOException;

  ChannelDataOutput writeBytes(final byte[] iContent) throws IOException;

  ChannelDataOutput writeBytes(final byte[] iContent, final int iLength) throws IOException;

  void writeRID(final RID iRID) throws IOException;

  void writeVersion(final int version) throws IOException;

  OutputStream getDataOutput();
}
