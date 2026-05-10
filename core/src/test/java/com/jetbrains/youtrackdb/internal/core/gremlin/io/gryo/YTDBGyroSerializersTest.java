package com.jetbrains.youtrackdb.internal.core.gremlin.io.gryo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.gremlin.YTDBVertexPropertyId;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Modifier;
import org.apache.tinkerpop.shaded.kryo.Kryo;
import org.apache.tinkerpop.shaded.kryo.io.Input;
import org.apache.tinkerpop.shaded.kryo.io.Output;
import org.junit.Test;

/**
 * Direct tests for the gryo (Kryo) serializers used to encode YouTrackDB
 * record-ids over the GraphSON V3 wire format. The serializers themselves are tiny —
 * {@link RecordIdGyroSerializer} and {@link YTDBVertexPropertyIdGyroSerializer} — but their
 * round-trip pinning gives us coverage of (1) the persistent-vs-changeable RID branch in
 * {@link YTDBVertexPropertyIdGyroSerializer#read} and (2) the singleton {@code INSTANCE}
 * accessor that the {@link com.jetbrains.youtrackdb.internal.core.gremlin.io.YTDBIoRegistry}
 * relies on.
 *
 * <p>The Kryo instance is the upstream TinkerPop-shaded one
 * ({@code org.apache.tinkerpop.shaded.kryo}); class registration is irrelevant because the
 * serializers operate on the {@link Input}/{@link Output} stream directly and never delegate
 * back to Kryo for their value classes.
 */
public class YTDBGyroSerializersTest {

  /**
   * Round-trip a persistent {@link RecordId} through {@link RecordIdGyroSerializer} — the
   * wire form is the canonical {@code "#cid:cpos"} string. Pinning preserves the
   * "RID round-trips as RID" invariant the IoRegistry contract depends on.
   */
  @Test
  public void recordIdGyroSerializerRoundTripsPersistentRid() {
    var serializer = RecordIdGyroSerializer.INSTANCE;
    var kryo = new Kryo();
    var bytes = new ByteArrayOutputStream();

    var rid = new RecordId(7, 47);
    try (var output = new Output(bytes)) {
      serializer.write(kryo, output, rid);
    }

    try (var input = new Input(new ByteArrayInputStream(bytes.toByteArray()))) {
      RecordIdInternal decoded = serializer.read(kryo, input, RecordIdInternal.class);
      assertEquals(rid.getCollectionId(), decoded.getCollectionId());
      assertEquals(rid.getCollectionPosition(), decoded.getCollectionPosition());
    }
  }

  /**
   * {@code INSTANCE} is a singleton — pin the static-final field shape and that two
   * separate reads return the same reference. A regression that converts INSTANCE
   * into a getter allocating per-call would slip past a tautological self-comparison.
   */
  @Test
  public void recordIdGyroSerializerExposesSingleton() throws NoSuchFieldException {
    var f = RecordIdGyroSerializer.class.getDeclaredField("INSTANCE");
    var mods = f.getModifiers();
    assertTrue("INSTANCE must be static", Modifier.isStatic(mods));
    assertTrue("INSTANCE must be final", Modifier.isFinal(mods));
    var a = RecordIdGyroSerializer.INSTANCE;
    var b = RecordIdGyroSerializer.INSTANCE;
    assertSame(a, b);
  }

  /**
   * Round-trip a {@link YTDBVertexPropertyId} whose underlying RID is persistent — the
   * {@code "#cid:cpos:key"} wire format is parsed by {@code split(":", -1)} so embedded
   * empty key parts are preserved.
   */
  @Test
  public void vertexPropertyIdGyroSerializerRoundTripsPersistentRid() {
    var serializer = YTDBVertexPropertyIdGyroSerializer.INSTANCE;
    var kryo = new Kryo();
    var bytes = new ByteArrayOutputStream();

    var id = new YTDBVertexPropertyId(new RecordId(11, 222), "color");
    try (var output = new Output(bytes)) {
      serializer.write(kryo, output, id);
    }

    try (var input = new Input(new ByteArrayInputStream(bytes.toByteArray()))) {
      var decoded = serializer.read(kryo, input, YTDBVertexPropertyId.class);
      assertEquals(11, decoded.rid().getCollectionId());
      assertEquals(222, decoded.rid().getCollectionPosition());
      assertEquals("color", decoded.key());
      assertTrue(
          "persistent collection-position must decode as RecordId",
          decoded.rid() instanceof RecordId);
    }
  }

  /**
   * Round-trip a {@link YTDBVertexPropertyId} whose RID has a negative collection-position —
   * the reader's {@code collectionPosition < 0} branch picks {@link ChangeableRecordId}.
   */
  @Test
  public void vertexPropertyIdGyroSerializerRoundTripsChangeableRid() {
    var serializer = YTDBVertexPropertyIdGyroSerializer.INSTANCE;
    var kryo = new Kryo();
    var bytes = new ByteArrayOutputStream();

    var id = new YTDBVertexPropertyId(new ChangeableRecordId(5, -1), "name");
    try (var output = new Output(bytes)) {
      serializer.write(kryo, output, id);
    }

    try (var input = new Input(new ByteArrayInputStream(bytes.toByteArray()))) {
      var decoded = serializer.read(kryo, input, YTDBVertexPropertyId.class);
      assertEquals(5, decoded.rid().getCollectionId());
      assertEquals(-1, decoded.rid().getCollectionPosition());
      assertEquals("name", decoded.key());
      assertTrue(
          "negative collection-position must decode as ChangeableRecordId",
          decoded.rid() instanceof ChangeableRecordId);
    }
  }

  /**
   * Same singleton-pin rationale as {@link #recordIdGyroSerializerExposesSingleton()} —
   * pin the static-final field shape and that two separate reads return the same reference.
   */
  @Test
  public void vertexPropertyIdGyroSerializerExposesSingleton() throws NoSuchFieldException {
    var f = YTDBVertexPropertyIdGyroSerializer.class.getDeclaredField("INSTANCE");
    var mods = f.getModifiers();
    assertTrue("INSTANCE must be static", Modifier.isStatic(mods));
    assertTrue("INSTANCE must be final", Modifier.isFinal(mods));
    var a = YTDBVertexPropertyIdGyroSerializer.INSTANCE;
    var b = YTDBVertexPropertyIdGyroSerializer.INSTANCE;
    assertSame(a, b);
  }
}
