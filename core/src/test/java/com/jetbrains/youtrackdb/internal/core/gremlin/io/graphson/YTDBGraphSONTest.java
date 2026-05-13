package com.jetbrains.youtrackdb.internal.core.gremlin.io.graphson;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.gremlin.YTDBVertexPropertyId;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.gremlin.io.YTDBIoRegistry;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import java.io.IOException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.tinkerpop.gremlin.structure.io.graphson.GraphSONIo;
import org.apache.tinkerpop.shaded.jackson.databind.Module;
import org.apache.tinkerpop.shaded.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the GraphSON V3 Jackson layer used by the YouTrackDB gremlin engine. The tests
 * configure an {@link ObjectMapper} via {@link YTDBIoRegistry} (the same wiring the engine
 * uses) and exercise:
 *
 * <ul>
 *   <li>{@link YTDBRecordIdJacksonSerializer} default {@code serialize} writes a JSON object;
 *   <li>{@link YTDBImmutableRecordIdJacksonDeserializer} accepts a persistent string RID;
 *   <li>{@link YTDBChangeableRecordIdJacksonSerializer} (despite the misleading name, this is
 *       a deserializer in implementation — see class) accepts a changeable string RID and
 *       rejects a persistent one with {@link IllegalStateException};
 *   <li>{@link YTDBVertexPropertyIdJacksonSerializer}/Deserializer round-trip both the
 *       persistent and changeable RID branches;
 *   <li>{@link YTDBIoRegistry#newYTdbId} dispatches across {@code RecordIdInternal},
 *       {@link Map}, {@link String}, and {@code null} inputs and rejects unknown types;
 *   <li>{@link YTDBIoRegistry#isYTDBRecord} predicate against the wire-key set.
 * </ul>
 */
public class YTDBGraphSONTest {

  private ObjectMapper mapper;

  @Before
  public void setUp() {
    var modules = YTDBIoRegistry.instance().find(GraphSONIo.class);
    mapper = new ObjectMapper();
    modules.forEach(m -> mapper.registerModule((Module) m.getValue1()));
  }

  /**
   * Default serialise of a {@link RecordId} via the Jackson module — emits a JSON object
   * with the two integer fields the YouTrackDB-flavoured wire format declares.
   */
  @Test
  public void recordIdSerializesAsObject() throws IOException {
    var sw = new StringWriter();
    mapper.writeValue(sw, new RecordId(7, 47));
    assertEquals("{\"collectionId\":7,\"collectionPosition\":47}", sw.toString());
  }

  /**
   * Round-trip a persistent RID: serialise, then deserialise via
   * {@link YTDBImmutableRecordIdJacksonDeserializer} from a quoted string form. The reader
   * receives a JSON string (the form Jackson chooses for tagged scalars) and rebuilds the
   * persistent {@link RecordId}.
   */
  @Test
  public void recordIdImmutableDeserializerAcceptsPersistentRidString() throws IOException {
    var json = "\"#7:47\"";
    var decoded = mapper.readValue(json, RecordId.class);

    assertEquals(7, decoded.getCollectionId());
    assertEquals(47, decoded.getCollectionPosition());
  }

  /**
   * The immutable deserializer rejects strings that decode to a non-persistent RID
   * (a {@link ChangeableRecordId}); the rejection is propagated as
   * {@link IllegalStateException}.
   */
  @Test
  public void recordIdImmutableDeserializerRejectsChangeableRidString() {
    var json = "\"#7:-1\"";
    try {
      mapper.readValue(json, RecordId.class);
      fail("Expected rejection of a non-persistent RID via the immutable deserializer");
    } catch (IOException ioe) {
      // Jackson sometimes wraps; allow either layer to produce the IllegalStateException
      var cause = ioe.getCause() != null ? ioe.getCause() : ioe;
      assertTrue(
          "Expected IllegalStateException somewhere in the chain — actual: " + cause,
          cause instanceof IllegalStateException
              || cause.getMessage().contains("not persistent"));
    } catch (IllegalStateException expected) {
      // ok — direct rethrow
    }
  }

  /**
   * The {@link YTDBChangeableRecordIdJacksonSerializer} (deserializer despite the name) does
   * the symmetric job: accepts a changeable RID string and decodes a {@link ChangeableRecordId}.
   */
  @Test
  public void changeableRecordIdDeserializerAcceptsChangeableString() throws IOException {
    var json = "\"#7:-1\"";
    var decoded = mapper.readValue(json, ChangeableRecordId.class);

    assertEquals(7, decoded.getCollectionId());
    assertEquals(-1, decoded.getCollectionPosition());
  }

  /**
   * The changeable deserializer rejects a persistent RID — symmetric to the immutable
   * deserializer's rejection of a changeable one.
   */
  @Test
  public void changeableRecordIdDeserializerRejectsPersistentString() {
    var json = "\"#7:47\"";
    try {
      mapper.readValue(json, ChangeableRecordId.class);
      fail("Expected rejection of a persistent RID via the changeable deserializer");
    } catch (IOException ioe) {
      var cause = ioe.getCause() != null ? ioe.getCause() : ioe;
      assertTrue(
          "Expected IllegalStateException somewhere in the chain — actual: " + cause,
          cause instanceof IllegalStateException
              || cause.getMessage().contains("not new"));
    } catch (IllegalStateException expected) {
      // ok
    }
  }

  /**
   * {@link YTDBVertexPropertyIdJacksonSerializer} default serialise emits a JSON object —
   * pin the literal so a future reformat surfaces in tests rather than at runtime.
   */
  @Test
  public void vertexPropertyIdSerializesAsObject() throws IOException {
    var id = new YTDBVertexPropertyId(new RecordId(11, 222), "color");
    var sw = new StringWriter();
    mapper.writeValue(sw, id);

    assertEquals(
        "{\"collectionId\":11,\"collectionPosition\":222,\"propertyKey\":\"color\"}",
        sw.toString());
  }

  /**
   * {@link YTDBVertexPropertyIdJacksonDeserializer} reads the
   * {@code "#cid:cpos:key"} string format produced by the {@code serializeWithType} path —
   * persistent RID branch.
   */
  @Test
  public void vertexPropertyIdDeserializerAcceptsPersistentRid() throws IOException {
    var decoded = mapper.readValue("\"11:222:color\"", YTDBVertexPropertyId.class);

    assertEquals(11, decoded.rid().getCollectionId());
    assertEquals(222, decoded.rid().getCollectionPosition());
    assertEquals("color", decoded.key());
    assertTrue(decoded.rid() instanceof RecordId);
  }

  /**
   * Symmetric: the deserializer picks {@link ChangeableRecordId} when the encoded
   * collection-position is negative.
   */
  @Test
  public void vertexPropertyIdDeserializerAcceptsChangeableRid() throws IOException {
    var decoded = mapper.readValue("\"5:-1:name\"", YTDBVertexPropertyId.class);

    assertEquals(5, decoded.rid().getCollectionId());
    assertEquals(-1, decoded.rid().getCollectionPosition());
    assertEquals("name", decoded.key());
    assertTrue(decoded.rid() instanceof ChangeableRecordId);
  }

  /**
   * {@link YTDBIoRegistry#newYTdbId} returns {@code null} unchanged — the {@code case null}
   * arm of the switch.
   */
  @Test
  public void newYTdbIdNullPassThrough() {
    assertNull(YTDBIoRegistry.newYTdbId(null));
  }

  /**
   * {@link YTDBIoRegistry#newYTdbId} pass-through for an existing {@link RecordIdInternal} —
   * used when the upstream parser already constructed the typed value.
   */
  @Test
  public void newYTdbIdPassesThroughRecordIdInternal() {
    var rid = new RecordId(1, 2);
    assertEquals(rid, YTDBIoRegistry.newYTdbId(rid));
  }

  /**
   * The {@code Map} arm builds a {@link RecordId} or {@link ChangeableRecordId} based on the
   * sign of {@code collectionPosition} and an optional {@link YTDBVertexPropertyId} when the
   * map carries the {@code propertyKey} entry.
   */
  @Test
  public void newYTdbIdMapWithoutPropertyKeyReturnsRecordId() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put(YTDBIoRegistry.COLLECTION_ID, 7);
    map.put(YTDBIoRegistry.COLLECTION_POSITION, 47L);

    var built = YTDBIoRegistry.newYTdbId(map);
    assertNotNull(built);
    assertTrue(built instanceof RecordId);
    var rid = (RecordId) built;
    assertEquals(7, rid.getCollectionId());
    assertEquals(47, rid.getCollectionPosition());
  }

  /**
   * {@code collectionPosition < 0} drives the {@link ChangeableRecordId} branch in the
   * {@code Map} arm of {@code newYTdbId}.
   */
  @Test
  public void newYTdbIdMapNegativePositionReturnsChangeableRecordId() {
    Map<String, Object> map = new HashMap<>();
    map.put(YTDBIoRegistry.COLLECTION_ID, 1);
    map.put(YTDBIoRegistry.COLLECTION_POSITION, -1L);

    var built = YTDBIoRegistry.newYTdbId(map);
    assertTrue(built instanceof ChangeableRecordId);
  }

  /**
   * When the map carries the {@code propertyKey} entry the result is wrapped as a
   * {@link YTDBVertexPropertyId}.
   */
  @Test
  public void newYTdbIdMapWithPropertyKeyReturnsVertexPropertyId() {
    Map<String, Object> map = new HashMap<>();
    map.put(YTDBIoRegistry.COLLECTION_ID, 7);
    map.put(YTDBIoRegistry.COLLECTION_POSITION, 47L);
    map.put(YTDBIoRegistry.PROPERTY_KEY, "color");

    var built = YTDBIoRegistry.newYTdbId(map);
    assertNotNull(built);
    assertTrue(built instanceof YTDBVertexPropertyId);
    var vpId = (YTDBVertexPropertyId) built;
    assertEquals("color", vpId.key());
    assertEquals(7, vpId.rid().getCollectionId());
    assertEquals(47, vpId.rid().getCollectionPosition());
  }

  /**
   * The {@code String} arm parses the canonical {@code "#cid:cpos"} form via
   * {@link RecordIdInternal#fromString}.
   */
  @Test
  public void newYTdbIdStringRoundTripsCanonicalForm() {
    var built = YTDBIoRegistry.newYTdbId("#7:47");
    assertNotNull(built);
    assertTrue(built instanceof RID);
    var rid = (RID) built;
    assertEquals(7, rid.getCollectionId());
    assertEquals(47, rid.getCollectionPosition());
  }

  /** An unknown-type input must be rejected. */
  @Test
  public void newYTdbIdUnknownTypeRejected() {
    try {
      YTDBIoRegistry.newYTdbId(Integer.valueOf(42));
      fail("Expected IllegalArgumentException for unsupported input type");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("Unable to convert"));
    }
  }

  /**
   * {@link YTDBIoRegistry#isYTDBRecord} returns {@code true} only for maps that carry both
   * record-id keys.
   */
  @Test
  public void isYTDBRecordRecognisesMapWithBothKeys() {
    Map<String, Number> good = new HashMap<>();
    good.put(YTDBIoRegistry.COLLECTION_ID, 1);
    good.put(YTDBIoRegistry.COLLECTION_POSITION, 2L);
    assertTrue(YTDBIoRegistry.isYTDBRecord(good));
  }

  /**
   * Branch coverage: missing key, wrong type (non-{@link Map}), and {@code null} all return
   * {@code false}.
   */
  @Test
  public void isYTDBRecordRejectsNonMatchingShapes() {
    assertFalse(YTDBIoRegistry.isYTDBRecord(null));
    assertFalse(YTDBIoRegistry.isYTDBRecord("not a map"));
    Map<String, Number> incomplete = new HashMap<>();
    incomplete.put(YTDBIoRegistry.COLLECTION_ID, 1);
    assertFalse(YTDBIoRegistry.isYTDBRecord(incomplete));
  }

  /**
   * {@link YTDBGraphSONV3#getTypeDefinitions} declares the YouTrackDB type-name aliases —
   * the entries are load-bearing for the GraphSON serializer registration. Pin the keys.
   */
  @Test
  public void graphSonV3TypeDefinitionsCarryAllAliases() {
    var defs = YTDBGraphSONV3.INSTANCE.getTypeDefinitions();
    assertNotNull(defs);
    assertTrue(defs.containsKey(RecordId.class));
    assertTrue(defs.containsKey(ChangeableRecordId.class));
    assertTrue(defs.containsKey(YTDBVertexPropertyId.class));
    assertEquals("RecordId", defs.get(RecordId.class));
    assertEquals("NRecordId", defs.get(ChangeableRecordId.class));
    assertEquals("VertexPropertyId", defs.get(YTDBVertexPropertyId.class));
  }

  /** The YouTrackDB GraphSON namespace is shared across versions — pin the literal. */
  @Test
  public void graphSonV3TypeNamespaceIsYoutrackdb() {
    assertEquals("youtrackdb", YTDBGraphSONV3.INSTANCE.getTypeNamespace());
  }

  /**
   * Direct {@code createObject} pinning of the inner {@link YTDBGraphSONV3.YTDBIdDeserializer}
   * — the {@code isYTDBRecord} false branch returns the input map unchanged. The class is
   * package-private but reachable; a real deserialise does not exercise this branch
   * because Jackson never dispatches a non-record map through the YTDB deserializer in
   * normal usage. We pin it to defend the contract.
   */
  @Test
  public void idDeserializerCreateObjectReturnsNonMatchingMapUnchanged() {
    var deserializer = new YTDBGraphSONV3.YTDBIdDeserializer();
    Map<String, Object> nonRecord = new HashMap<>();
    nonRecord.put("foo", "bar");
    assertEquals(nonRecord, deserializer.createObject(nonRecord));
  }

  /** And conversely — when the map IS a record, {@code createObject} converts it. */
  @Test
  public void idDeserializerCreateObjectConvertsRecordShapedMap() {
    var deserializer = new YTDBGraphSONV3.YTDBIdDeserializer();
    Map<String, Object> record = new HashMap<>();
    record.put(YTDBIoRegistry.COLLECTION_ID, 7);
    record.put(YTDBIoRegistry.COLLECTION_POSITION, 47L);

    var result = deserializer.createObject(record);
    assertNotNull(result);
    assertTrue(result instanceof RID);
    assertEquals(7, ((RID) result).getCollectionId());
    assertEquals(47, ((RID) result).getCollectionPosition());
  }
}
