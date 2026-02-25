package com.jetbrains.youtrackdb.internal.core.metadata.security.binary;

import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.metadata.security.jwt.TokenMetaInfo;
import com.jetbrains.youtrackdb.internal.core.metadata.security.jwt.TokenPayload;
import com.jetbrains.youtrackdb.internal.core.metadata.security.jwt.TokenPayloadDeserializer;
import com.jetbrains.youtrackdb.internal.core.metadata.security.jwt.YouTrackDBJwtHeader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

public class BinaryTokenSerializer implements TokenMetaInfo {

  private final String[] types;
  private final String[] keys;
  private final String[] algorithms;
  private final String[] dbTypes;
  private final Map<String, Byte> associetedDdTypes;
  private final Map<String, Byte> associetedKeys;
  private final Map<String, Byte> associetedAlgorithms;
  private final Map<String, Byte> associetedTypes;

  public BinaryTokenSerializer(
      String[] dbTypes, String[] keys, String[] algorithms, String[] entityTypes) {
    this.dbTypes = dbTypes;
    this.keys = keys;
    this.algorithms = algorithms;
    this.types = entityTypes;
    associetedDdTypes = createMap(dbTypes);
    associetedKeys = createMap(keys);
    associetedAlgorithms = createMap(algorithms);
    associetedTypes = createMap(entityTypes);
  }

  private static TokenPayloadDeserializer getForType(String type) {
    if ("YouTrackDB".equals(type)) {
      return new BinaryTokenPayloadDeserializer();
    }

    throw new DatabaseException("Unknown payload type");
  }

  public BinaryTokenSerializer() {
    this(
        new String[]{"disk", "memory"},
        new String[]{"dafault"},
        new String[]{"HmacSHA256"},
        new String[]{"YouTrackDB", "node"});
  }

  public Map<String, Byte> createMap(String[] entries) {
    Map<String, Byte> newMap = new HashMap<String, Byte>();
    for (var i = 0; i < entries.length; i++) {
      newMap.put(entries[i], (byte) i);
    }
    return newMap;
  }

  public BinaryToken deserialize(InputStream stream) throws IOException {
    var input = new DataInputStream(stream);

    var header = new YouTrackDBJwtHeader();
    header.setType(types[input.readByte()]);
    header.setKeyId(keys[input.readByte()]);
    header.setAlgorithm(algorithms[input.readByte()]);

    var token = new BinaryToken();
    token.setHeader(header);

    var payload = getForType(header.getType()).deserialize(input, this);
    token.setPayload(payload);

    return token;
  }

  @Nullable
  protected static String readString(DataInputStream input) throws IOException {
    var s = input.readShort();
    if (s >= 0) {
      var str = new byte[s];
      input.readFully(str);
      return new String(str, StandardCharsets.UTF_8);
    }
    return null;
  }

  public void serialize(BinaryToken token, OutputStream stream) throws IOException {

    var output = new DataOutputStream(stream);
    var header = token.getHeader();
    TokenPayload payload = token.getPayload();
    assert header.getType().equals(payload.getPayloadType());
    output.writeByte(associetedTypes.get(header.getType())); // type
    output.writeByte(associetedKeys.get(header.getKeyId())); // keys
    output.writeByte(associetedAlgorithms.get(header.getAlgorithm())); // algorithm
    payload.serialize(output, this);
  }

  public static void writeString(DataOutputStream output, String toWrite) throws IOException {
    if (toWrite == null) {
      output.writeShort(-1);
    } else {
      var str = toWrite.getBytes(StandardCharsets.UTF_8);
      output.writeShort(str.length);
      output.write(str);
    }
  }

  @Override
  public String getDbType(int pos) {
    return dbTypes[pos];
  }

  @Override
  public int getDbTypeID(String databaseType) {
    return associetedDdTypes.get(databaseType);
  }
}
