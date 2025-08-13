package com.jetbrains.youtrackdb.internal.core.metadata.security.jwt;

import java.io.DataInputStream;
import java.io.IOException;

public interface TokenPayloadDeserializer {

  BinaryTokenPayload deserialize(DataInputStream input, TokenMetaInfo base) throws IOException;
}
