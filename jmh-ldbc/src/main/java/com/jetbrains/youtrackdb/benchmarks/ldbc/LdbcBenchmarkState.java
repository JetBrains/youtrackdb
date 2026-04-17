package com.jetbrains.youtrackdb.benchmarks.ldbc;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared JMH state for LDBC SNB benchmarks.
 * Reuses a pre-built YouTrackDB database if present at the configured path,
 * otherwise creates and loads one from the LDBC CSV dataset.
 *
 * <p>The recommended workflow for nightly CI is to download the pre-built database
 * archive from Hetzner Object Storage and extract it to the DB path. This skips
 * the ~21-minute CSV loading step for SF 1.
 *
 * <p>If no pre-built database exists, the CSV dataset must use LDBC datagen v1.0.0
 * CsvCompositeMergeForeign format, where 1-to-N relationships are embedded as
 * foreign key columns in entity CSV files. See {@code jmh-ldbc/README.md} for details.
 *
 * <p>Configure via system properties:
 * <ul>
 *   <li>{@code -Dldbc.dataset.path=/path/to/sf1} - path to LDBC dataset root
 *       (must contain static/ and dynamic/ subdirectories; only needed if DB
 *       does not already exist)</li>
 *   <li>{@code -Dldbc.db.path=./target/ldbc-bench-db} - path to store the database</li>
 *   <li>{@code -Dldbc.batch.size=1000} - batch size for data loading</li>
 *   <li>{@code -Dldbc.scale.factor=1} - scale factor (used in default dataset path)</li>
 * </ul>
 */
@State(Scope.Benchmark)
public class LdbcBenchmarkState {

  private static final Logger log = LoggerFactory.getLogger(LdbcBenchmarkState.class);

  private static final String DB_NAME = "ldbc_benchmark";
  private static final int DEFAULT_BATCH_SIZE = 1000;

  YouTrackDB db;
  YTDBGraphTraversalSource traversal;

  // Curated per-query parameters — populated by ParameterCurator after DB load
  private ParameterCurator.CuratedParams curatedParams;

  private final AtomicLong counter = new AtomicLong();

  /**
   * Advances the shared counter and returns the new index.
   * Call this once per benchmark invocation, then use the returned index
   * for all parameter lookups to keep them consistent.
   */
  public long nextIndex() {
    return counter.getAndIncrement();
  }

  // ==================== IS QUERY PARAMETERS ====================

  public long isPersonId(long idx) {
    return curatedParams.isPersonIds()[(int) (idx
        % curatedParams.isPersonIds().length)];
  }

  public long isMessageId(long idx) {
    return curatedParams.isMessageIds()[(int) (idx
        % curatedParams.isMessageIds().length)];
  }

  // ==================== IC QUERY PARAMETERS ====================

  /** IC1: person + firstName tuple. */
  public long ic1PersonId(long idx) {
    var p = curatedParams.ic1()[(int) (idx % curatedParams.ic1().length)];
    return p.personId();
  }

  public String ic1FirstName(long idx) {
    var p = curatedParams.ic1()[(int) (idx % curatedParams.ic1().length)];
    return p.firstName();
  }

  /** IC2: person + date tuple. */
  public long ic2PersonId(long idx) {
    var p = curatedParams.ic2()[(int) (idx % curatedParams.ic2().length)];
    return p.personId();
  }

  public Date ic2MaxDate(long idx) {
    var p = curatedParams.ic2()[(int) (idx % curatedParams.ic2().length)];
    return p.maxDate();
  }

  /** IC3: person + countryX + countryY + startDate tuple. */
  public long ic3PersonId(long idx) {
    var p = curatedParams.ic3()[(int) (idx % curatedParams.ic3().length)];
    return p.personId();
  }

  public String ic3CountryX(long idx) {
    var p = curatedParams.ic3()[(int) (idx % curatedParams.ic3().length)];
    return p.countryX();
  }

  public String ic3CountryY(long idx) {
    var p = curatedParams.ic3()[(int) (idx % curatedParams.ic3().length)];
    return p.countryY();
  }

  public Date ic3StartDate(long idx) {
    var p = curatedParams.ic3()[(int) (idx % curatedParams.ic3().length)];
    return p.startDate();
  }

  /** IC4: person + startDate tuple. */
  public long ic4PersonId(long idx) {
    var p = curatedParams.ic4()[(int) (idx % curatedParams.ic4().length)];
    return p.personId();
  }

  public Date ic4StartDate(long idx) {
    var p = curatedParams.ic4()[(int) (idx % curatedParams.ic4().length)];
    return p.startDate();
  }

  /** IC5: person ID. */
  public long ic5PersonId(long idx) {
    return curatedParams.ic5PersonIds()[(int) (idx
        % curatedParams.ic5PersonIds().length)];
  }

  /** IC5: date from the shared date pool. */
  public Date ic5Date(long idx) {
    return curatedParams.dates()[(int) (idx
        % curatedParams.dates().length)];
  }

  /** IC6: person + tag tuple. */
  public long ic6PersonId(long idx) {
    var p = curatedParams.ic6()[(int) (idx % curatedParams.ic6().length)];
    return p.personId();
  }

  public String ic6TagName(long idx) {
    var p = curatedParams.ic6()[(int) (idx % curatedParams.ic6().length)];
    return p.tagName();
  }

  /** IC7: person ID from friends-selected pool. */
  public long ic7PersonId(long idx) {
    return curatedParams.ic7PersonIds()[(int) (idx
        % curatedParams.ic7PersonIds().length)];
  }

  /** IC8: person ID from friends-selected pool. */
  public long ic8PersonId(long idx) {
    return curatedParams.ic8PersonIds()[(int) (idx
        % curatedParams.ic8PersonIds().length)];
  }

  /** IC9: person + date tuple. */
  public long ic9PersonId(long idx) {
    var p = curatedParams.ic9()[(int) (idx % curatedParams.ic9().length)];
    return p.personId();
  }

  public Date ic9MaxDate(long idx) {
    var p = curatedParams.ic9()[(int) (idx % curatedParams.ic9().length)];
    return p.maxDate();
  }

  /** IC10: person ID from FoF-selected pool. */
  public long ic10PersonId(long idx) {
    return curatedParams.ic10PersonIds()[(int) (idx
        % curatedParams.ic10PersonIds().length)];
  }

  /** IC11: person + country. */
  public long ic11PersonId(long idx) {
    return curatedParams.ic11PersonIds()[(int) (idx
        % curatedParams.ic11PersonIds().length)];
  }

  public String ic11CountryName(long idx) {
    return curatedParams.ic11CountryNames()[(int) (idx
        % curatedParams.ic11CountryNames().length)];
  }

  /** IC12: person + tagClass tuple. */
  public long ic12PersonId(long idx) {
    var p = curatedParams.ic12()[(int) (idx % curatedParams.ic12().length)];
    return p.personId();
  }

  public String ic12TagClassName(long idx) {
    var p = curatedParams.ic12()[(int) (idx % curatedParams.ic12().length)];
    return p.tagClassName();
  }

  // -- BothE-KNOWS extension parameters --

  /**
   * Person IDs for the BothE-KNOWS benchmark. Reuses the IS person-ID pool,
   * which selects active persons likely to have multiple KNOWS connections.
   */
  public long bothEKnowsPersonId(long idx) {
    return curatedParams.isPersonIds()[(int) (idx
        % curatedParams.isPersonIds().length)];
  }

  /**
   * Lower-bound date for the BothE-KNOWS benchmark. Reuses the shared dates
   * pool (also used by IC5), which covers the KNOWS activity period in the dataset.
   */
  public Date bothEKnowsMinDate(long idx) {
    return curatedParams.dates()[(int) (idx % curatedParams.dates().length)];
  }

  /** IC13: two distinct person IDs. */
  public long ic13Person1Id(long idx) {
    return curatedParams.ic13PersonIds1()[(int) (idx
        % curatedParams.ic13PersonIds1().length)];
  }

  public long ic13Person2Id(long idx) {
    return curatedParams.ic13PersonIds2()[(int) (idx
        % curatedParams.ic13PersonIds2().length)];
  }

  /**
   * Executes a SQL query within a transaction and returns all result rows.
   */
  @SuppressWarnings("unchecked")
  List<Map<String, Object>> executeSql(String sql, Object... keyValues) {
    return traversal.computeInTx(g -> {
      var ytg = (YTDBGraphTraversalSource) g;
      return ytg.yql(sql, keyValues).toList().stream()
          .map(obj -> (Map<String, Object>) obj)
          .toList();
    });
  }

  @Setup(Level.Trial)
  public void setup() throws Exception {
    String scaleFactor = System.getProperty("ldbc.scale.factor", "1");
    String datasetPath = System.getProperty("ldbc.dataset.path",
        "./target/ldbc-dataset/sf" + scaleFactor);
    String dbPath = System.getProperty("ldbc.db.path", "./target/ldbc-bench-db");
    int batchSize = Integer.getInteger("ldbc.batch.size", DEFAULT_BATCH_SIZE);

    db = YourTracks.instance(dbPath);

    if (!db.exists(DB_NAME)) {
      // No pre-built database — load from CSV dataset
      Path datasetDir = Path.of(datasetPath);
      if (!Files.exists(datasetDir.resolve("static"))
          || !Files.exists(datasetDir.resolve("dynamic"))) {
        throw new IllegalStateException(
            "LDBC dataset not found at: " + datasetDir.toAbsolutePath()
                + ". Either provide a pre-built database at " + dbPath
                + " or ensure the dataset contains static/ and dynamic/ subdirectories"
                + " in CsvCompositeMergeForeign format."
                + " Download from Hetzner Object Storage"
                + " or generate using the LDBC datagen Docker image."
                + " See jmh-ldbc/README.md for instructions.");
      }
      log.info("Creating and loading LDBC database from: {}", datasetDir);
      db.create(DB_NAME, DatabaseType.DISK, "admin", "admin", "admin");
      traversal = db.openTraversal(DB_NAME, "admin", "admin");
      createSchema();
      loadData(datasetDir, batchSize);
      log.info("Database loaded successfully");
    } else {
      log.info("Using existing LDBC database at: {}", dbPath);
      traversal = db.openTraversal(DB_NAME, "admin", "admin");
    }

    curatedParams = ParameterCurator.curate(this, Path.of(dbPath));
    log.info(
        "Benchmark state ready: {} IS persons, {} IS messages,"
            + " {} IC1 params, {} IC3 params",
        curatedParams.isPersonIds().length,
        curatedParams.isMessageIds().length,
        curatedParams.ic1().length,
        curatedParams.ic3().length);
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    try {
      if (traversal != null) {
        traversal.close();
      }
    } catch (Exception e) {
      log.warn("Error closing traversal", e);
    }
    try {
      if (db != null) {
        db.close();
      }
    } catch (Exception e) {
      log.warn("Error closing database", e);
    }
  }

  // ==================== SCHEMA ====================

  private static final String SCHEMA_RESOURCE = "/ldbc-schema.sql";

  /**
   * Reads SQL statements from a classpath resource file.
   * Blank lines and lines starting with {@code --} are skipped.
   * Trailing semicolons are stripped from each statement.
   */
  static List<String> loadSqlStatements(String resource) {
    try (InputStream is =
        LdbcBenchmarkState.class.getResourceAsStream(resource)) {
      if (is == null) {
        throw new IllegalStateException("Resource not found: " + resource);
      }
      try (var reader = new BufferedReader(
          new InputStreamReader(is, StandardCharsets.UTF_8))) {
        return reader.lines()
            .map(String::strip)
            .filter(line -> !line.isEmpty() && !line.startsWith("--"))
            .map(line -> line.endsWith(";")
                ? line.substring(0, line.length() - 1) : line)
            .toList();
      }
    } catch (IOException e) {
      throw new IllegalStateException("Failed to read " + resource, e);
    }
  }

  private void createSchema() {
    List<String> statements = loadSqlStatements(SCHEMA_RESOURCE);
    log.info("Creating LDBC schema ({} statements)...", statements.size());
    traversal.executeInTx(g -> {
      var ytg = (YTDBGraphTraversalSource) g;
      for (String sql : statements) {
        ytg.yql(sql).iterate();
      }
    });
  }

  // ==================== DATA LOADING ====================

  /**
   * Loads all LDBC data from CsvCompositeMergeForeign format. In this format,
   * 1-to-N relationships are embedded as foreign key columns in entity files,
   * so edges are created inline during entity loading. Remaining N-to-M
   * relationships are loaded from separate edge CSV files.
   */
  private void loadData(Path datasetRoot, int batchSize) throws Exception {
    Path staticDir = datasetRoot.resolve("static");
    Path dynamicDir = datasetRoot.resolve("dynamic");

    if (!Files.exists(staticDir) || !Files.exists(dynamicDir)) {
      throw new IllegalArgumentException(
          "Dataset directory must contain static/ and dynamic/"
              + " subdirectories: " + datasetRoot);
    }

    long start = System.currentTimeMillis();

    // ---- Static entities with embedded FK edges ----
    loadPlaces(staticDir, batchSize);
    loadTagClasses(staticDir, batchSize);
    loadTags(staticDir, batchSize);
    loadOrganisations(staticDir, batchSize);

    // ---- Dynamic entities with embedded FK edges ----
    loadPersons(dynamicDir, batchSize);
    loadForums(dynamicDir, batchSize);
    loadPosts(dynamicDir, batchSize);
    loadComments(dynamicDir, batchSize);

    // ---- Dynamic N-to-M edges from separate files ----
    loadKnowsEdges(dynamicDir, batchSize);
    loadNamedEdge(dynamicDir, "Person_hasInterest_Tag",
        "HAS_INTEREST", "Person", "Tag",
        "PersonId", "TagId", null, null, batchSize);
    loadNamedEdge(dynamicDir, "Person_studyAt_University",
        "STUDY_AT", "Person", "Organisation",
        "PersonId", "UniversityId", "classYear", "int", batchSize);
    loadNamedEdge(dynamicDir, "Person_workAt_Company",
        "WORK_AT", "Person", "Organisation",
        "PersonId", "CompanyId", "workFrom", "int", batchSize);
    loadNamedEdge(dynamicDir, "Person_likes_Post",
        "LIKES", "Person", "Post",
        "PersonId", "PostId", "creationDate", "long", batchSize);
    loadNamedEdge(dynamicDir, "Person_likes_Comment",
        "LIKES", "Person", "Comment",
        "PersonId", "CommentId", "creationDate", "long", batchSize);
    loadNamedEdge(dynamicDir, "Forum_hasMember_Person",
        "HAS_MEMBER", "Forum", "Person",
        "ForumId", "PersonId", "creationDate", "joinDate", batchSize);
    loadNamedEdge(dynamicDir, "Forum_hasTag_Tag",
        "HAS_TAG", "Forum", "Tag",
        "ForumId", "TagId", null, null, batchSize);
    loadNamedEdge(dynamicDir, "Post_hasTag_Tag",
        "HAS_TAG", "Post", "Tag",
        "PostId", "TagId", null, null, batchSize);
    loadNamedEdge(dynamicDir, "Comment_hasTag_Tag",
        "HAS_TAG", "Comment", "Tag",
        "CommentId", "TagId", null, null, batchSize);

    long duration = System.currentTimeMillis() - start;
    log.info("Data loading completed in {}ms ({} seconds)",
        duration, duration / 1000.0);
  }

  // ---- Static entity loaders ----

  private void loadPlaces(Path staticDir, int batchSize) throws IOException {
    // First pass: insert all Place vertices
    long count = processEntityCsv(staticDir, "Place", batchSize, (hdr, batch) -> {
      int iId = hdr.get("id");
      int iName = hdr.get("name");
      int iUrl = hdr.get("url");
      int iType = hdr.get("type");
      String sql = "INSERT INTO Place SET"
          + " id = :id, name = :name, url = :url, type = :type";
      traversal.executeInTx(g -> {
        var ytg = (YTDBGraphTraversalSource) g;
        for (String[] f : batch) {
          ytg.yql(sql,
              "id", Long.parseLong(f[iId]),
              "name", f[iName], "url", f[iUrl],
              "type", f[iType]).iterate();
        }
      });
    });
    log.info("Loaded {} Place vertices", count);

    // Second pass: create IS_PART_OF edges from PartOfPlaceId FK
    long edgeCount = processEntityCsv(staticDir, "Place", batchSize,
        (hdr, batch) -> {
          int iId = hdr.get("id");
          int iFk = hdr.get("PartOfPlaceId");
          String sql = "CREATE EDGE IS_PART_OF"
              + " FROM (SELECT FROM Place WHERE id = :fromId)"
              + " TO (SELECT FROM Place WHERE id = :toId)";
          traversal.executeInTx(g -> {
            var ytg = (YTDBGraphTraversalSource) g;
            for (String[] f : batch) {
              if (!f[iFk].isEmpty()) {
                ytg.yql(sql,
                    "fromId", Long.parseLong(f[iId]),
                    "toId", Long.parseLong(f[iFk])).iterate();
              }
            }
          });
        });
    log.info("Loaded IS_PART_OF edges for {} Place rows", edgeCount);
  }

  private void loadTagClasses(Path staticDir, int batchSize)
      throws IOException {
    long count = processEntityCsv(staticDir, "TagClass", batchSize,
        (hdr, batch) -> {
          int iId = hdr.get("id");
          int iName = hdr.get("name");
          int iUrl = hdr.get("url");
          int iFk = hdr.get("SubclassOfTagClassId");
          String vertexSql = "INSERT INTO TagClass SET"
              + " id = :id, name = :name, url = :url";
          String edgeSql = "CREATE EDGE IS_SUBCLASS_OF"
              + " FROM (SELECT FROM TagClass WHERE id = :fromId)"
              + " TO (SELECT FROM TagClass WHERE id = :toId)";
          traversal.executeInTx(g -> {
            var ytg = (YTDBGraphTraversalSource) g;
            for (String[] f : batch) {
              ytg.yql(vertexSql,
                  "id", Long.parseLong(f[iId]),
                  "name", f[iName], "url", f[iUrl]).iterate();
            }
          });
          // Edges in a separate transaction so all TagClass vertices exist
          traversal.executeInTx(g -> {
            var ytg = (YTDBGraphTraversalSource) g;
            for (String[] f : batch) {
              if (!f[iFk].isEmpty()) {
                ytg.yql(edgeSql,
                    "fromId", Long.parseLong(f[iId]),
                    "toId", Long.parseLong(f[iFk])).iterate();
              }
            }
          });
        });
    log.info("Loaded {} TagClass vertices + IS_SUBCLASS_OF edges", count);
  }

  private void loadTags(Path staticDir, int batchSize) throws IOException {
    long count = processEntityCsv(staticDir, "Tag", batchSize,
        (hdr, batch) -> {
          int iId = hdr.get("id");
          int iName = hdr.get("name");
          int iUrl = hdr.get("url");
          int iFk = hdr.get("TypeTagClassId");
          String vertexSql =
              "INSERT INTO Tag SET id = :id, name = :name, url = :url";
          String edgeSql = "CREATE EDGE HAS_TYPE"
              + " FROM (SELECT FROM Tag WHERE id = :fromId)"
              + " TO (SELECT FROM TagClass WHERE id = :toId)";
          traversal.executeInTx(g -> {
            var ytg = (YTDBGraphTraversalSource) g;
            for (String[] f : batch) {
              ytg.yql(vertexSql,
                  "id", Long.parseLong(f[iId]),
                  "name", f[iName], "url", f[iUrl]).iterate();
              if (!f[iFk].isEmpty()) {
                ytg.yql(edgeSql,
                    "fromId", Long.parseLong(f[iId]),
                    "toId", Long.parseLong(f[iFk])).iterate();
              }
            }
          });
        });
    log.info("Loaded {} Tag vertices + HAS_TYPE edges", count);
  }

  private void loadOrganisations(Path staticDir, int batchSize)
      throws IOException {
    long count = processEntityCsv(staticDir, "Organisation", batchSize,
        (hdr, batch) -> {
          int iId = hdr.get("id");
          int iType = hdr.get("type");
          int iName = hdr.get("name");
          int iUrl = hdr.get("url");
          int iFk = hdr.get("LocationPlaceId");
          String vertexSql = "INSERT INTO Organisation SET"
              + " id = :id, type = :type, name = :name, url = :url";
          String edgeSql = "CREATE EDGE IS_LOCATED_IN"
              + " FROM (SELECT FROM Organisation WHERE id = :fromId)"
              + " TO (SELECT FROM Place WHERE id = :toId)";
          traversal.executeInTx(g -> {
            var ytg = (YTDBGraphTraversalSource) g;
            for (String[] f : batch) {
              ytg.yql(vertexSql,
                  "id", Long.parseLong(f[iId]),
                  "type", f[iType], "name", f[iName],
                  "url", f[iUrl]).iterate();
              if (!f[iFk].isEmpty()) {
                ytg.yql(edgeSql,
                    "fromId", Long.parseLong(f[iId]),
                    "toId", Long.parseLong(f[iFk])).iterate();
              }
            }
          });
        });
    log.info("Loaded {} Organisation vertices + IS_LOCATED_IN edges", count);
  }

  // ---- Dynamic entity loaders ----

  private void loadPersons(Path dynamicDir, int batchSize)
      throws IOException {
    long count = processEntityCsv(dynamicDir, "Person", batchSize,
        (hdr, batch) -> {
          int iId = hdr.get("id");
          int iFirstName = hdr.get("firstName");
          int iLastName = hdr.get("lastName");
          int iGender = hdr.get("gender");
          int iBirthday = hdr.get("birthday");
          int iCreationDate = hdr.get("creationDate");
          int iLocationIP = hdr.get("locationIP");
          int iBrowserUsed = hdr.get("browserUsed");
          int iLanguage = hdr.get("language");
          int iEmail = hdr.get("email");
          int iLocationCityId = hdr.get("LocationCityId");
          String vertexSql = "INSERT INTO Person SET id = :id,"
              + " firstName = :firstName, lastName = :lastName,"
              + " gender = :gender, birthday = :birthday,"
              + " creationDate = :creationDate,"
              + " locationIP = :locationIP,"
              + " browserUsed = :browserUsed,"
              + " languages = :languages, emails = :emails";
          String edgeSql = "CREATE EDGE IS_LOCATED_IN"
              + " FROM (SELECT FROM Person WHERE id = :fromId)"
              + " TO (SELECT FROM Place WHERE id = :toId)";
          traversal.executeInTx(g -> {
            var ytg = (YTDBGraphTraversalSource) g;
            for (String[] f : batch) {
              ytg.yql(vertexSql,
                  "id", Long.parseLong(f[iId]),
                  "firstName", f[iFirstName],
                  "lastName", f[iLastName],
                  "gender", f[iGender],
                  "birthday", Long.parseLong(f[iBirthday]),
                  "creationDate", Long.parseLong(f[iCreationDate]),
                  "locationIP", f[iLocationIP],
                  "browserUsed", f[iBrowserUsed],
                  "languages", parseList(f[iLanguage]),
                  "emails", parseList(f[iEmail])).iterate();
              if (!f[iLocationCityId].isEmpty()) {
                ytg.yql(edgeSql,
                    "fromId", Long.parseLong(f[iId]),
                    "toId", Long.parseLong(f[iLocationCityId])).iterate();
              }
            }
          });
        });
    log.info("Loaded {} Person vertices + IS_LOCATED_IN edges", count);
  }

  private void loadForums(Path dynamicDir, int batchSize)
      throws IOException {
    long count = processEntityCsv(dynamicDir, "Forum", batchSize,
        (hdr, batch) -> {
          int iId = hdr.get("id");
          int iTitle = hdr.get("title");
          int iCreationDate = hdr.get("creationDate");
          int iModeratorId = hdr.get("ModeratorPersonId");
          String vertexSql = "INSERT INTO Forum SET id = :id,"
              + " title = :title, creationDate = :creationDate";
          String edgeSql = "CREATE EDGE HAS_MODERATOR"
              + " FROM (SELECT FROM Forum WHERE id = :fromId)"
              + " TO (SELECT FROM Person WHERE id = :toId)";
          traversal.executeInTx(g -> {
            var ytg = (YTDBGraphTraversalSource) g;
            for (String[] f : batch) {
              ytg.yql(vertexSql,
                  "id", Long.parseLong(f[iId]),
                  "title", f[iTitle],
                  "creationDate",
                  Long.parseLong(f[iCreationDate])).iterate();
              if (!f[iModeratorId].isEmpty()) {
                ytg.yql(edgeSql,
                    "fromId", Long.parseLong(f[iId]),
                    "toId", Long.parseLong(f[iModeratorId])).iterate();
              }
            }
          });
        });
    log.info("Loaded {} Forum vertices + HAS_MODERATOR edges", count);
  }

  private void loadPosts(Path dynamicDir, int batchSize) throws IOException {
    long count = processEntityCsv(dynamicDir, "Post", batchSize,
        (hdr, batch) -> {
          int iId = hdr.get("id");
          int iCreationDate = hdr.get("creationDate");
          int iLocationIP = hdr.get("locationIP");
          int iBrowserUsed = hdr.get("browserUsed");
          int iLanguage = hdr.get("language");
          int iContent = hdr.get("content");
          int iLength = hdr.get("length");
          int iImageFile = hdr.get("imageFile");
          int iCreatorId = hdr.get("CreatorPersonId");
          int iForumId = hdr.get("ContainerForumId");
          int iLocationId = hdr.get("LocationCountryId");
          String vertexSql = "INSERT INTO Post SET id = :id,"
              + " creationDate = :creationDate,"
              + " locationIP = :locationIP,"
              + " browserUsed = :browserUsed,"
              + " language = :language, length = :length,"
              + " imageFile = :imageFile, content = :content";
          String creatorSql = "CREATE EDGE HAS_CREATOR"
              + " FROM (SELECT FROM Post WHERE id = :fromId)"
              + " TO (SELECT FROM Person WHERE id = :toId)";
          // CONTAINER_OF goes FROM Forum TO Post
          String containerSql = "CREATE EDGE CONTAINER_OF"
              + " FROM (SELECT FROM Forum WHERE id = :fromId)"
              + " TO (SELECT FROM Post WHERE id = :toId)";
          String locationSql = "CREATE EDGE IS_LOCATED_IN"
              + " FROM (SELECT FROM Post WHERE id = :fromId)"
              + " TO (SELECT FROM Place WHERE id = :toId)";
          traversal.executeInTx(g -> {
            var ytg = (YTDBGraphTraversalSource) g;
            for (String[] f : batch) {
              long postId = Long.parseLong(f[iId]);
              ytg.yql(vertexSql,
                  "id", postId,
                  "creationDate", Long.parseLong(f[iCreationDate]),
                  "locationIP", f[iLocationIP],
                  "browserUsed", f[iBrowserUsed],
                  "language", f[iLanguage],
                  "length", Integer.parseInt(f[iLength]),
                  "imageFile",
                  f[iImageFile].isEmpty() ? null : f[iImageFile],
                  "content",
                  f[iContent].isEmpty() ? null : f[iContent]).iterate();
              if (!f[iCreatorId].isEmpty()) {
                ytg.yql(creatorSql,
                    "fromId", postId,
                    "toId", Long.parseLong(f[iCreatorId])).iterate();
              }
              if (!f[iForumId].isEmpty()) {
                ytg.yql(containerSql,
                    "fromId", Long.parseLong(f[iForumId]),
                    "toId", postId).iterate();
              }
              if (!f[iLocationId].isEmpty()) {
                ytg.yql(locationSql,
                    "fromId", postId,
                    "toId", Long.parseLong(f[iLocationId])).iterate();
              }
            }
          });
        });
    log.info("Loaded {} Post vertices + HAS_CREATOR/CONTAINER_OF/"
        + "IS_LOCATED_IN edges", count);
  }

  private void loadComments(Path dynamicDir, int batchSize)
      throws IOException {
    long count = processEntityCsv(dynamicDir, "Comment", batchSize,
        (hdr, batch) -> {
          int iId = hdr.get("id");
          int iCreationDate = hdr.get("creationDate");
          int iLocationIP = hdr.get("locationIP");
          int iBrowserUsed = hdr.get("browserUsed");
          int iContent = hdr.get("content");
          int iLength = hdr.get("length");
          int iCreatorId = hdr.get("CreatorPersonId");
          int iLocationId = hdr.get("LocationCountryId");
          int iParentPostId = hdr.get("ParentPostId");
          int iParentCommentId = hdr.get("ParentCommentId");
          String vertexSql = "INSERT INTO Comment SET id = :id,"
              + " creationDate = :creationDate,"
              + " locationIP = :locationIP,"
              + " browserUsed = :browserUsed,"
              + " content = :content, length = :length";
          String creatorSql = "CREATE EDGE HAS_CREATOR"
              + " FROM (SELECT FROM Comment WHERE id = :fromId)"
              + " TO (SELECT FROM Person WHERE id = :toId)";
          String locationSql = "CREATE EDGE IS_LOCATED_IN"
              + " FROM (SELECT FROM Comment WHERE id = :fromId)"
              + " TO (SELECT FROM Place WHERE id = :toId)";
          String replyPostSql = "CREATE EDGE REPLY_OF"
              + " FROM (SELECT FROM Comment WHERE id = :fromId)"
              + " TO (SELECT FROM Post WHERE id = :toId)";
          String replyCommentSql = "CREATE EDGE REPLY_OF"
              + " FROM (SELECT FROM Comment WHERE id = :fromId)"
              + " TO (SELECT FROM Comment WHERE id = :toId)";
          traversal.executeInTx(g -> {
            var ytg = (YTDBGraphTraversalSource) g;
            for (String[] f : batch) {
              long commentId = Long.parseLong(f[iId]);
              ytg.yql(vertexSql,
                  "id", commentId,
                  "creationDate", Long.parseLong(f[iCreationDate]),
                  "locationIP", f[iLocationIP],
                  "browserUsed", f[iBrowserUsed],
                  "content", f[iContent],
                  "length", Integer.parseInt(f[iLength])).iterate();
              if (!f[iCreatorId].isEmpty()) {
                ytg.yql(creatorSql,
                    "fromId", commentId,
                    "toId", Long.parseLong(f[iCreatorId])).iterate();
              }
              if (!f[iLocationId].isEmpty()) {
                ytg.yql(locationSql,
                    "fromId", commentId,
                    "toId", Long.parseLong(f[iLocationId])).iterate();
              }
              if (!f[iParentPostId].isEmpty()) {
                ytg.yql(replyPostSql,
                    "fromId", commentId,
                    "toId", Long.parseLong(f[iParentPostId])).iterate();
              }
              if (!f[iParentCommentId].isEmpty()) {
                ytg.yql(replyCommentSql,
                    "fromId", commentId,
                    "toId",
                    Long.parseLong(f[iParentCommentId])).iterate();
              }
            }
          });
        });
    log.info("Loaded {} Comment vertices + HAS_CREATOR/IS_LOCATED_IN/"
        + "REPLY_OF edges", count);
  }

  // ---- Dynamic edge loaders ----

  private void loadKnowsEdges(Path dynamicDir, int batchSize)
      throws IOException {
    long count = processEntityCsv(dynamicDir, "Person_knows_Person",
        batchSize, (hdr, batch) -> {
          int iP1 = hdr.get("Person1Id");
          int iP2 = hdr.get("Person2Id");
          int iCd = hdr.get("creationDate");
          String sql = "CREATE EDGE KNOWS"
              + " FROM (SELECT FROM Person WHERE id = :fromId)"
              + " TO (SELECT FROM Person WHERE id = :toId)"
              + " SET creationDate = :creationDate";
          traversal.executeInTx(g -> {
            var ytg = (YTDBGraphTraversalSource) g;
            for (String[] f : batch) {
              long p1 = Long.parseLong(f[iP1]);
              long p2 = Long.parseLong(f[iP2]);
              long cd = Long.parseLong(f[iCd]);
              // Bidirectional
              ytg.yql(sql,
                  "fromId", p1, "toId", p2,
                  "creationDate", cd).iterate();
              ytg.yql(sql,
                  "fromId", p2, "toId", p1,
                  "creationDate", cd).iterate();
            }
          });
        });
    log.info("Loaded {} KNOWS edges (bidirectional)", count);
  }

  /**
   * Generic loader for N-to-M edge CSV files with named columns.
   *
   * @param propCol CSV column name for the edge property (null if none)
   * @param propType "int", "long", or "joinDate" (maps creationDate to
   *     joinDate); null if no property
   */
  private void loadNamedEdge(Path dir, String entityName,
      String edgeLabel, String fromLabel, String toLabel,
      String fromCol, String toCol,
      String propCol, String propType,
      int batchSize) throws IOException {
    boolean hasProp = propCol != null && propType != null;
    // For HAS_MEMBER, creationDate in CSV maps to joinDate edge property
    boolean isJoinDate = "joinDate".equals(propType);
    String sql;
    if (!hasProp) {
      sql = "CREATE EDGE " + edgeLabel
          + " FROM (SELECT FROM " + fromLabel + " WHERE id = :fromId)"
          + " TO (SELECT FROM " + toLabel + " WHERE id = :toId)";
    } else {
      String edgePropName = isJoinDate ? "joinDate" : propCol;
      sql = "CREATE EDGE " + edgeLabel
          + " FROM (SELECT FROM " + fromLabel + " WHERE id = :fromId)"
          + " TO (SELECT FROM " + toLabel + " WHERE id = :toId)"
          + " SET " + edgePropName + " = :propValue";
    }
    String finalSql = sql;

    long count = processEntityCsv(dir, entityName, batchSize,
        (hdr, batch) -> {
          int iFrom = hdr.get(fromCol);
          int iTo = hdr.get(toCol);
          int iProp = hasProp ? hdr.get(propCol) : -1;
          traversal.executeInTx(g -> {
            var ytg = (YTDBGraphTraversalSource) g;
            for (String[] f : batch) {
              if (!hasProp) {
                ytg.yql(finalSql,
                    "fromId", Long.parseLong(f[iFrom]),
                    "toId", Long.parseLong(f[iTo])).iterate();
              } else if ("int".equals(propType)) {
                ytg.yql(finalSql,
                    "fromId", Long.parseLong(f[iFrom]),
                    "toId", Long.parseLong(f[iTo]),
                    "propValue", Integer.parseInt(f[iProp])).iterate();
              } else {
                // "long" or "joinDate" — both store a long timestamp
                ytg.yql(finalSql,
                    "fromId", Long.parseLong(f[iFrom]),
                    "toId", Long.parseLong(f[iTo]),
                    "propValue", Long.parseLong(f[iProp])).iterate();
              }
            }
          });
        });
    log.info("Loaded {} {} edges ({} -> {})",
        count, edgeLabel, fromLabel, toLabel);
  }

  // ==================== CSV UTILITIES ====================

  /**
   * Callback for processing batches of CSV rows with header-based column
   * access.
   */
  @FunctionalInterface
  interface HeaderBatchConsumer {
    void accept(Map<String, Integer> header, List<String[]> batch);
  }

  /**
   * Resolves a CSV entity to one or more part files. Supports two layouts:
   * <ul>
   *   <li>Datagen v1.0.0: directory with Spark part-*.csv files</li>
   *   <li>Datagen v0.3.5: flat file named entity_0_0.csv</li>
   * </ul>
   */
  private List<Path> resolveCsvFiles(Path dir, String entityName)
      throws IOException {
    // Datagen v1.0.0: directory with part files
    Path entityDir = dir.resolve(entityName);
    if (Files.isDirectory(entityDir)) {
      try (Stream<Path> files = Files.list(entityDir)) {
        List<Path> parts = files
            .filter(p -> p.getFileName().toString().startsWith("part-"))
            .filter(p -> p.getFileName().toString().endsWith(".csv"))
            .sorted()
            .toList();
        if (!parts.isEmpty()) {
          return parts;
        }
      }
    }
    // Datagen v0.3.5 fallback: flat file
    String flatName = entityName.toLowerCase() + "_0_0.csv";
    Path flatFile = dir.resolve(flatName);
    if (Files.exists(flatFile)) {
      return List.of(flatFile);
    }
    return List.of();
  }

  /**
   * Processes CSV files for an entity, using the header row for column lookup.
   * Handles both single-file and multi-part-file layouts.
   */
  private long processEntityCsv(Path dir, String entityName,
      int batchSize, HeaderBatchConsumer consumer) throws IOException {
    List<Path> csvFiles = resolveCsvFiles(dir, entityName);
    if (csvFiles.isEmpty()) {
      log.warn("No CSV files found for entity: {}", entityName);
      return 0;
    }

    Map<String, Integer> header = null;
    long totalCount = 0;

    for (Path csvFile : csvFiles) {
      try (Stream<String> lines = Files.lines(csvFile)) {
        var iterator = lines.iterator();
        if (!iterator.hasNext()) {
          continue;
        }

        // Parse header from every part file (they're identical)
        String headerLine = iterator.next();
        if (header == null) {
          header = parseHeader(headerLine);
        }

        var batch = new ArrayList<String[]>(batchSize);
        while (iterator.hasNext()) {
          String line = iterator.next();
          if (line.isEmpty()) {
            continue;
          }
          batch.add(line.split("\\|", -1));
          totalCount++;
          if (batch.size() >= batchSize) {
            consumer.accept(header, List.copyOf(batch));
            batch.clear();
          }
        }
        if (!batch.isEmpty()) {
          consumer.accept(header, List.copyOf(batch));
        }
      }
    }
    return totalCount;
  }

  private static Map<String, Integer> parseHeader(String headerLine) {
    String[] cols = headerLine.split("\\|", -1);
    var map = new HashMap<String, Integer>(cols.length * 2);
    for (int i = 0; i < cols.length; i++) {
      map.put(cols[i], i);
    }
    return map;
  }

  private static List<String> parseList(String value) {
    if (value == null || value.isEmpty()) {
      return List.of();
    }
    return List.of(value.split(";"));
  }

}
