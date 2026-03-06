package com.jetbrains.youtrackdb.benchmarks.ldbc;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Shared JMH state for LDBC SNB benchmarks.
 * Creates and loads a YouTrackDB database from LDBC SF 0.1 dataset on first run,
 * reuses the existing database on subsequent runs.
 *
 * <p>The dataset is automatically downloaded from the LDBC SURF repository if not
 * present locally.
 *
 * <p>Configure via system properties:
 * <ul>
 *   <li>{@code -Dldbc.dataset.path=/path/to/sf0.1} - path to LDBC dataset root
 *       (must contain static/ and dynamic/ subdirectories).
 *       If not set, defaults to {@code ./target/ldbc-dataset/sf0.1} and
 *       auto-downloads the dataset.</li>
 *   <li>{@code -Dldbc.db.path=./target/ldbc-bench-db} - path to store the database</li>
 *   <li>{@code -Dldbc.batch.size=1000} - batch size for data loading</li>
 *   <li>{@code -Dldbc.scale.factor=0.1} - scale factor for auto-download</li>
 * </ul>
 */
@State(Scope.Benchmark)
public class LdbcBenchmarkState {

  private static final Logger log = LoggerFactory.getLogger(LdbcBenchmarkState.class);

  private static final String DB_NAME = "ldbc_benchmark";
  private static final int MAX_PARAMS = 200;
  private static final int DEFAULT_BATCH_SIZE = 1000;

  private static final String LDBC_DATASET_BASE_URL =
      "https://repository.surfsara.nl/datasets/cwi/snb/files";
  private static final String LDBC_SERIALIZER = "social_network-csv_composite-longdateformatter";

  YouTrackDB db;
  YTDBGraphTraversalSource traversal;

  // Query parameters - populated after data load
  long[] personIds;
  long[] messageIds;
  String[] firstNames;
  String[] tagNames;
  String[] countryNames;
  String[] tagClassNames;
  Date[] messageDates;

  private final AtomicLong counter = new AtomicLong();

  /**
   * Advances the shared counter and returns the new index.
   * Call this once per benchmark invocation, then use the returned index
   * for all parameter lookups to keep them consistent.
   */
  public long nextIndex() {
    return counter.getAndIncrement();
  }

  public long personId(long idx) {
    return personIds[(int) (idx % personIds.length)];
  }

  public long personId2(long idx) {
    return personIds[(int) ((idx + personIds.length / 2) % personIds.length)];
  }

  public long messageId(long idx) {
    return messageIds[(int) (idx % messageIds.length)];
  }

  public String firstName(long idx) {
    return firstNames[(int) (idx % firstNames.length)];
  }

  public String tagName(long idx) {
    return tagNames[(int) (idx % tagNames.length)];
  }

  public String countryName(long idx) {
    return countryNames[(int) (idx % countryNames.length)];
  }

  public String countryName2(long idx) {
    return countryNames[(int) ((idx + 1) % countryNames.length)];
  }

  public String tagClassName(long idx) {
    return tagClassNames[(int) (idx % tagClassNames.length)];
  }

  public Date maxDate(long idx) {
    return messageDates[(int) (idx % messageDates.length)];
  }

  /**
   * Executes a SQL query within a transaction and returns all result rows.
   */
  @SuppressWarnings("unchecked")
  List<Map<String, Object>> executeSql(String sql, Object... keyValues) {
    return traversal.computeInTx(g -> {
      var ytg = (YTDBGraphTraversalSource) g;
      return ytg.sqlCommand(sql, keyValues).toList().stream()
          .map(obj -> (Map<String, Object>) obj)
          .toList();
    });
  }

  @Setup(Level.Trial)
  public void setup() throws Exception {
    String scaleFactor = System.getProperty("ldbc.scale.factor", "0.1");
    String datasetPath = System.getProperty("ldbc.dataset.path",
        "./target/ldbc-dataset/sf" + scaleFactor);
    String dbPath = System.getProperty("ldbc.db.path", "./target/ldbc-bench-db");
    int batchSize = Integer.getInteger("ldbc.batch.size", DEFAULT_BATCH_SIZE);

    Path datasetDir = Path.of(datasetPath);

    // Auto-download dataset if not present
    if (!Files.exists(datasetDir.resolve("static"))
        || !Files.exists(datasetDir.resolve("dynamic"))) {
      downloadDataset(datasetDir, scaleFactor);
    }

    db = YourTracks.instance(dbPath);

    if (!db.exists(DB_NAME)) {
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

    sampleQueryParameters();
    log.info(
        "Benchmark state ready: {} persons, {} messages, {} tags, {} countries",
        personIds.length, messageIds.length, tagNames.length, countryNames.length);
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

  // ==================== DATASET DOWNLOAD ====================

  private void downloadDataset(Path targetDir, String scaleFactor) throws Exception {
    String archiveName = LDBC_SERIALIZER + "-sf" + scaleFactor + ".tar.zst";
    String url = LDBC_DATASET_BASE_URL + "/" + LDBC_SERIALIZER + "/" + archiveName;

    Path tempDir = targetDir.getParent().resolve("tmp");
    Files.createDirectories(tempDir);
    Path archivePath = tempDir.resolve(archiveName);

    if (!Files.exists(archivePath)) {
      log.info("Downloading LDBC SF {} dataset from: {}", scaleFactor, url);
      log.info("This may take a few minutes for the first run...");
      downloadFile(url, archivePath);
      log.info("Download complete: {}", archivePath);
    } else {
      log.info("Using cached archive: {}", archivePath);
    }

    log.info("Extracting dataset...");
    Files.createDirectories(targetDir);
    extractTarZst(archivePath, targetDir);

    // Handle nested directory structure - move files up if needed
    Path nestedDir = findDataDir(targetDir);
    if (nestedDir != null && !nestedDir.equals(targetDir)) {
      log.info("Moving extracted data from {} to {}", nestedDir, targetDir);
      try (var files = Files.list(nestedDir)) {
        for (Path file : files.toList()) {
          Path dest = targetDir.resolve(file.getFileName());
          if (!Files.exists(dest)) {
            Files.move(file, dest);
          }
        }
      }
    }

    log.info("Dataset extracted to: {}", targetDir);
  }

  private Path findDataDir(Path root) throws IOException {
    if (Files.exists(root.resolve("static")) && Files.exists(root.resolve("dynamic"))) {
      return root;
    }
    // Search one level deep for static/dynamic directories
    try (var dirs = Files.list(root)) {
      for (Path dir : dirs.toList()) {
        if (Files.isDirectory(dir)
            && Files.exists(dir.resolve("static"))
            && Files.exists(dir.resolve("dynamic"))) {
          return dir;
        }
      }
    }
    return null;
  }

  /**
   * Downloads a file from a URL using curl (with --insecure for envs with incomplete CA
   * bundles), falling back to wget, then Java.
   *
   * <p>The SURF Data Repository may return HTTP 409 when files are on tape storage.
   * In that case we trigger staging via their REST API and poll until the file is online.
   */
  private void downloadFile(String url, Path target) throws Exception {
    String targetAbs = target.toAbsolutePath().toString();

    // SURF tape-storage handling: stage the file if needed
    stageIfSurfOffline(url);

    // Strategy 1: curl
    if (tryExec("curl", "-fSL", "--insecure", "-o", targetAbs, url) == 0
        && Files.exists(target) && Files.size(target) > 0) {
      return;
    }
    Files.deleteIfExists(target);
    log.info("curl not available or failed, trying wget...");

    // Strategy 2: wget
    if (tryExec("wget", "-q", "--no-check-certificate", "-O", targetAbs, url) == 0
        && Files.exists(target) && Files.size(target) > 0) {
      return;
    }
    Files.deleteIfExists(target);
    log.info("wget not available or failed, trying Java URL.openStream()...");

    // Strategy 3: Java (may fail with SSL cert issues in some environments)
    try (InputStream in = URI.create(url).toURL().openStream()) {
      Files.copy(in, target);
      return;
    } catch (Exception e) {
      Files.deleteIfExists(target);
      throw new IOException(
          "Failed to download " + url + ". "
              + "Install curl or wget, or download manually to: " + target, e);
    }
  }

  /**
   * SURF Data Repository stores large files on tape. An initial request returns HTTP 409
   * with {"error":"File is offline"}. We must POST to the stage endpoint and poll until
   * the status becomes "ONL" (online).
   */
  private void stageIfSurfOffline(String url) throws Exception {
    if (!url.contains("repository.surfsara.nl")) {
      return;
    }
    // Check if file is offline by reading the HTTP status code via curl
    ProcessBuilder pb = new ProcessBuilder(
        "curl", "-skI", "-o", "/dev/null", "-w", "%{http_code}", url);
    pb.redirectErrorStream(true);
    Process proc = pb.start();
    String httpCode = new String(proc.getInputStream().readAllBytes()).trim();
    proc.waitFor();

    if (!"409".equals(httpCode)) {
      return; // file is available
    }

    // Extract the status and stage URLs from the 409 response body
    pb = new ProcessBuilder("curl", "-sk", url);
    pb.redirectErrorStream(true);
    proc = pb.start();
    String body = new String(proc.getInputStream().readAllBytes()).trim();
    proc.waitFor();

    String stageUrl = extractJsonValue(body, "stage");
    String statusUrl = extractJsonValue(body, "status");

    if (stageUrl == null || statusUrl == null) {
      log.warn("Could not parse SURF staging URLs from response: {}", body);
      return;
    }

    // Trigger staging
    log.info("SURF file is offline (tape storage). Requesting staging...");
    tryExec("curl", "-sk", "-X", "POST", stageUrl);

    // Poll until online (max ~10 minutes)
    for (int i = 0; i < 40; i++) {
      Thread.sleep(15_000);
      pb = new ProcessBuilder("curl", "-sk", statusUrl);
      pb.redirectErrorStream(true);
      proc = pb.start();
      String status = new String(proc.getInputStream().readAllBytes()).trim();
      proc.waitFor();

      if (status.contains("\"ONL\"")) {
        log.info("SURF file is now online, proceeding with download.");
        return;
      }
      String stateCode = extractJsonValue(status, "status");
      log.info("Waiting for SURF staging... status={} ({}/40)", stateCode, i + 1);
    }
    log.warn("SURF staging timed out after 10 minutes. Attempting download anyway.");
  }

  private static String extractJsonValue(String json, String key) {
    // Minimal JSON extraction — avoids adding a JSON library dependency
    String pattern = "\"" + key + "\"";
    int idx = json.indexOf(pattern);
    if (idx < 0) {
      return null;
    }
    int valueStart = json.indexOf('"', idx + pattern.length() + 1);
    if (valueStart < 0) {
      return null;
    }
    int valueEnd = json.indexOf('"', valueStart + 1);
    if (valueEnd < 0) {
      return null;
    }
    return json.substring(valueStart + 1, valueEnd).replace("\\/", "/");
  }

  /**
   * Extracts a .tar.zst archive. Tries three strategies in order:
   * 1. tar --use-compress-program=zstd (requires zstd CLI)
   * 2. python3 with zstandard + tarfile modules
   * 3. Fails with an actionable error message
   */
  private void extractTarZst(Path archive, Path targetDir) throws Exception {
    String archiveAbs = archive.toAbsolutePath().toString();
    String targetAbs = targetDir.toAbsolutePath().toString();

    // Strategy 1: native zstd
    if (tryExec("tar", "--use-compress-program=zstd", "-xf", archiveAbs,
        "-C", targetAbs) == 0) {
      return;
    }
    log.info("zstd CLI not found, falling back to python3...");

    // Strategy 2: python3 + zstandard (with path traversal protection)
    String pyScript = String.join("\n",
        "import zstandard, tarfile, os, sys",
        "archive = sys.argv[1]",
        "target = os.path.realpath(sys.argv[2])",
        "def safe_filter(member, path):",
        "    resolved = os.path.realpath(os.path.join(path, member.name))",
        "    if not resolved.startswith(path + os.sep) and resolved != path:",
        "        raise Exception('Path traversal detected: ' + member.name)",
        "    return member",
        "with open(archive, 'rb') as fh:",
        "    dctx = zstandard.ZstdDecompressor()",
        "    with dctx.stream_reader(fh) as reader:",
        "        with tarfile.open(fileobj=reader, mode='r|') as tf:",
        "            tf.extractall(path=target, filter=safe_filter)"
    );
    if (tryExec("python3", "-c", pyScript, archiveAbs, targetAbs) == 0) {
      return;
    }

    throw new IOException(
        "Failed to extract " + archive.getFileName() + ". "
            + "Install one of: zstd CLI (apt/brew/dnf install zstd) "
            + "or python3 + zstandard (pip install zstandard)");
  }

  private int tryExec(String... command) throws Exception {
    try {
      ProcessBuilder pb = new ProcessBuilder(command);
      pb.inheritIO();
      return pb.start().waitFor();
    } catch (IOException e) {
      // command not found
      return -1;
    }
  }

  // ==================== SCHEMA ====================

  private static final String[] SCHEMA_STATEMENTS = {
      // Vertex classes
      "CREATE CLASS Place EXTENDS V",
      "CREATE PROPERTY Place.id LONG",
      "CREATE PROPERTY Place.name STRING",
      "CREATE PROPERTY Place.url STRING",
      "CREATE PROPERTY Place.type STRING",

      "CREATE CLASS Organisation EXTENDS V",
      "CREATE PROPERTY Organisation.id LONG",
      "CREATE PROPERTY Organisation.type STRING",
      "CREATE PROPERTY Organisation.name STRING",
      "CREATE PROPERTY Organisation.url STRING",

      "CREATE CLASS Company EXTENDS Organisation",
      "CREATE CLASS University EXTENDS Organisation",

      "CREATE CLASS TagClass EXTENDS V",
      "CREATE PROPERTY TagClass.id LONG",
      "CREATE PROPERTY TagClass.name STRING",
      "CREATE PROPERTY TagClass.url STRING",

      "CREATE CLASS Tag EXTENDS V",
      "CREATE PROPERTY Tag.id LONG",
      "CREATE PROPERTY Tag.name STRING",
      "CREATE PROPERTY Tag.url STRING",

      "CREATE CLASS Person EXTENDS V",
      "CREATE PROPERTY Person.id LONG",
      "CREATE PROPERTY Person.firstName STRING",
      "CREATE PROPERTY Person.lastName STRING",
      "CREATE PROPERTY Person.gender STRING",
      "CREATE PROPERTY Person.birthday DATETIME",
      "CREATE PROPERTY Person.creationDate DATETIME",
      "CREATE PROPERTY Person.locationIP STRING",
      "CREATE PROPERTY Person.browserUsed STRING",
      "CREATE PROPERTY Person.languages EMBEDDEDLIST STRING",
      "CREATE PROPERTY Person.emails EMBEDDEDLIST STRING",

      "CREATE CLASS Forum EXTENDS V",
      "CREATE PROPERTY Forum.id LONG",
      "CREATE PROPERTY Forum.title STRING",
      "CREATE PROPERTY Forum.creationDate DATETIME",

      "CREATE CLASS Message EXTENDS V",
      "CREATE PROPERTY Message.id LONG",
      "CREATE PROPERTY Message.creationDate DATETIME",
      "CREATE PROPERTY Message.locationIP STRING",
      "CREATE PROPERTY Message.browserUsed STRING",
      "CREATE PROPERTY Message.content STRING",
      "CREATE PROPERTY Message.length INTEGER",

      "CREATE CLASS Post EXTENDS Message",
      "CREATE PROPERTY Post.imageFile STRING",
      "CREATE PROPERTY Post.language STRING",

      "CREATE CLASS Comment EXTENDS Message",

      // Edge classes
      "CREATE CLASS KNOWS EXTENDS E",
      "CREATE PROPERTY KNOWS.creationDate DATETIME",

      "CREATE CLASS IS_LOCATED_IN EXTENDS E",
      "CREATE CLASS HAS_INTEREST EXTENDS E",

      "CREATE CLASS STUDY_AT EXTENDS E",
      "CREATE PROPERTY STUDY_AT.classYear INTEGER",

      "CREATE CLASS WORK_AT EXTENDS E",
      "CREATE PROPERTY WORK_AT.workFrom INTEGER",

      "CREATE CLASS HAS_MODERATOR EXTENDS E",

      "CREATE CLASS HAS_MEMBER EXTENDS E",
      "CREATE PROPERTY HAS_MEMBER.joinDate DATETIME",

      "CREATE CLASS CONTAINER_OF EXTENDS E",
      "CREATE CLASS HAS_TAG EXTENDS E",
      "CREATE CLASS HAS_CREATOR EXTENDS E",

      "CREATE CLASS LIKES EXTENDS E",
      "CREATE PROPERTY LIKES.creationDate DATETIME",

      "CREATE CLASS REPLY_OF EXTENDS E",
      "CREATE CLASS IS_PART_OF EXTENDS E",
      "CREATE CLASS IS_SUBCLASS_OF EXTENDS E",
      "CREATE CLASS HAS_TYPE EXTENDS E",

      // Indexes
      "CREATE INDEX Place.id ON Place(id) UNIQUE",
      "CREATE INDEX Organisation.id ON Organisation(id) UNIQUE",
      "CREATE INDEX TagClass.id ON TagClass(id) UNIQUE",
      "CREATE INDEX Tag.id ON Tag(id) UNIQUE",
      "CREATE INDEX Person.id ON Person(id) UNIQUE",
      "CREATE INDEX Forum.id ON Forum(id) UNIQUE",
      "CREATE INDEX Message.id ON Message(id) UNIQUE",

      "CREATE INDEX Place.name ON Place(name) NOTUNIQUE",
      "CREATE INDEX Place.type ON Place(type) NOTUNIQUE",
      "CREATE INDEX Organisation.name ON Organisation(name) NOTUNIQUE",
      "CREATE INDEX Tag.name ON Tag(name) NOTUNIQUE",
      "CREATE INDEX TagClass.name ON TagClass(name) NOTUNIQUE",
      "CREATE INDEX Person.firstName ON Person(firstName) NOTUNIQUE",
      "CREATE INDEX Person.birthday ON Person(birthday) NOTUNIQUE",
      "CREATE INDEX Message.creationDate ON Message(creationDate) NOTUNIQUE",
      "CREATE INDEX Forum.creationDate ON Forum(creationDate) NOTUNIQUE",
      "CREATE INDEX HAS_MEMBER.joinDate ON HAS_MEMBER(joinDate) NOTUNIQUE",
      "CREATE INDEX WORK_AT.workFrom ON WORK_AT(workFrom) NOTUNIQUE",
  };

  private void createSchema() {
    log.info("Creating LDBC schema ({} statements)...", SCHEMA_STATEMENTS.length);
    traversal.executeInTx(g -> {
      var ytg = (YTDBGraphTraversalSource) g;
      for (String sql : SCHEMA_STATEMENTS) {
        ytg.sqlCommand(sql).iterate();
      }
    });
  }

  // ==================== DATA LOADING ====================

  private void loadData(Path datasetRoot, int batchSize) throws Exception {
    Path staticDir = datasetRoot.resolve("static");
    Path dynamicDir = datasetRoot.resolve("dynamic");

    if (!Files.exists(staticDir) || !Files.exists(dynamicDir)) {
      throw new IllegalArgumentException(
          "Dataset directory must contain static/ and dynamic/ subdirectories: "
              + datasetRoot);
    }

    long start = System.currentTimeMillis();

    // Static entities (parameterized queries)
    loadVertices(staticDir, "place_0_0.csv", "Place", batchSize,
        "INSERT INTO Place SET id = :p0, name = :p1, url = :p2, type = :p3",
        f -> new Object[]{"p0", Long.parseLong(f[0]), "p1", f[1], "p2", f[2], "p3", f[3]});

    loadVertices(staticDir, "organisation_0_0.csv", "Organisation", batchSize,
        "INSERT INTO Organisation SET id = :p0, type = :p1, name = :p2, url = :p3",
        f -> new Object[]{"p0", Long.parseLong(f[0]), "p1", f[1], "p2", f[2], "p3", f[3]});

    loadVertices(staticDir, "tagclass_0_0.csv", "TagClass", batchSize,
        "INSERT INTO TagClass SET id = :p0, name = :p1, url = :p2",
        f -> new Object[]{"p0", Long.parseLong(f[0]), "p1", f[1], "p2", f[2]});

    loadVertices(staticDir, "tag_0_0.csv", "Tag", batchSize,
        "INSERT INTO Tag SET id = :p0, name = :p1, url = :p2",
        f -> new Object[]{"p0", Long.parseLong(f[0]), "p1", f[1], "p2", f[2]});

    // Static relationships
    loadEdgeBySql(staticDir, "place_isPartOf_place_0_0.csv",
        "IS_PART_OF", "Place", "Place", batchSize);
    loadEdgeBySql(staticDir, "organisation_isLocatedIn_place_0_0.csv",
        "IS_LOCATED_IN", "Organisation", "Place", batchSize);
    loadEdgeBySql(staticDir, "tagclass_isSubclassOf_tagclass_0_0.csv",
        "IS_SUBCLASS_OF", "TagClass", "TagClass", batchSize);
    loadEdgeBySql(staticDir, "tag_hasType_tagclass_0_0.csv",
        "HAS_TYPE", "Tag", "TagClass", batchSize);

    // Dynamic entities
    loadPersons(dynamicDir, batchSize);
    loadForums(dynamicDir, batchSize);
    loadPosts(dynamicDir, batchSize);
    loadComments(dynamicDir, batchSize);

    // Dynamic relationships
    loadKnowsEdges(dynamicDir, batchSize);
    loadEdgeBySql(dynamicDir, "person_isLocatedIn_place_0_0.csv",
        "IS_LOCATED_IN", "Person", "Place", batchSize);
    loadEdgeBySql(dynamicDir, "person_hasInterest_tag_0_0.csv",
        "HAS_INTEREST", "Person", "Tag", batchSize);
    loadEdgeBySqlWithProp(dynamicDir, "person_studyAt_organisation_0_0.csv",
        "STUDY_AT", "Person", "Organisation", "classYear", batchSize);
    loadEdgeBySqlWithProp(dynamicDir, "person_workAt_organisation_0_0.csv",
        "WORK_AT", "Person", "Organisation", "workFrom", batchSize);
    loadEdgeBySqlWithDateProp(dynamicDir, "person_likes_post_0_0.csv",
        "LIKES", "Person", "Post", "creationDate", batchSize);
    loadEdgeBySqlWithDateProp(dynamicDir, "person_likes_comment_0_0.csv",
        "LIKES", "Person", "Comment", "creationDate", batchSize);

    loadEdgeBySql(dynamicDir, "forum_hasModerator_person_0_0.csv",
        "HAS_MODERATOR", "Forum", "Person", batchSize);
    loadEdgeBySql(dynamicDir, "forum_containerOf_post_0_0.csv",
        "CONTAINER_OF", "Forum", "Post", batchSize);
    loadEdgeBySql(dynamicDir, "forum_hasTag_tag_0_0.csv",
        "HAS_TAG", "Forum", "Tag", batchSize);
    loadEdgeBySqlWithDateProp(dynamicDir, "forum_hasMember_person_0_0.csv",
        "HAS_MEMBER", "Forum", "Person", "joinDate", batchSize);

    loadEdgeBySql(dynamicDir, "post_hasCreator_person_0_0.csv",
        "HAS_CREATOR", "Post", "Person", batchSize);
    loadEdgeBySql(dynamicDir, "post_isLocatedIn_place_0_0.csv",
        "IS_LOCATED_IN", "Post", "Place", batchSize);
    loadEdgeBySql(dynamicDir, "post_hasTag_tag_0_0.csv",
        "HAS_TAG", "Post", "Tag", batchSize);
    loadEdgeBySql(dynamicDir, "comment_hasCreator_person_0_0.csv",
        "HAS_CREATOR", "Comment", "Person", batchSize);
    loadEdgeBySql(dynamicDir, "comment_isLocatedIn_place_0_0.csv",
        "IS_LOCATED_IN", "Comment", "Place", batchSize);
    loadEdgeBySql(dynamicDir, "comment_replyOf_post_0_0.csv",
        "REPLY_OF", "Comment", "Post", batchSize);
    loadEdgeBySql(dynamicDir, "comment_replyOf_comment_0_0.csv",
        "REPLY_OF", "Comment", "Comment", batchSize);
    loadEdgeBySql(dynamicDir, "comment_hasTag_tag_0_0.csv",
        "HAS_TAG", "Comment", "Tag", batchSize);

    long duration = System.currentTimeMillis() - start;
    log.info("Data loading completed in {}ms ({} seconds)", duration, duration / 1000.0);
  }

  private void loadVertices(Path dir, String filename, String label, int batchSize,
      String sql, java.util.function.Function<String[], Object[]> paramBuilder) throws IOException {
    Path csvFile = dir.resolve(filename);
    if (!Files.exists(csvFile)) {
      log.warn("File not found, skipping: {}", csvFile);
      return;
    }
    long count = processCsv(csvFile, batchSize, batch -> {
      traversal.executeInTx(g -> {
        var ytg = (YTDBGraphTraversalSource) g;
        for (String[] fields : batch) {
          ytg.sqlCommand(sql, paramBuilder.apply(fields)).iterate();
        }
      });
    });
    log.info("Loaded {} {} vertices", count, label);
  }

  private void loadPersons(Path dynamicDir, int batchSize) throws IOException {
    Path csvFile = dynamicDir.resolve("person_0_0.csv");
    if (!Files.exists(csvFile)) {
      return;
    }
    String sql = "INSERT INTO Person SET id = :id, firstName = :firstName,"
        + " lastName = :lastName, gender = :gender, birthday = :birthday,"
        + " creationDate = :creationDate, locationIP = :locationIP,"
        + " browserUsed = :browserUsed, languages = :languages, emails = :emails";
    long count = processCsv(csvFile, batchSize, batch -> {
      traversal.executeInTx(g -> {
        var ytg = (YTDBGraphTraversalSource) g;
        for (String[] f : batch) {
          ytg.sqlCommand(sql,
              "id", Long.parseLong(f[0]),
              "firstName", f[1], "lastName", f[2], "gender", f[3],
              "birthday", Long.parseLong(f[4]),
              "creationDate", Long.parseLong(f[5]),
              "locationIP", f[6], "browserUsed", f[7],
              "languages", parseList(f[8]),
              "emails", parseList(f[9])
          ).iterate();
        }
      });
    });
    log.info("Loaded {} Person vertices", count);
  }

  private void loadForums(Path dynamicDir, int batchSize) throws IOException {
    Path csvFile = dynamicDir.resolve("forum_0_0.csv");
    if (!Files.exists(csvFile)) {
      return;
    }
    String sql = "INSERT INTO Forum SET id = :id, title = :title,"
        + " creationDate = :creationDate";
    long count = processCsv(csvFile, batchSize, batch -> {
      traversal.executeInTx(g -> {
        var ytg = (YTDBGraphTraversalSource) g;
        for (String[] f : batch) {
          ytg.sqlCommand(sql,
              "id", Long.parseLong(f[0]),
              "title", f[1],
              "creationDate", Long.parseLong(f[2])
          ).iterate();
        }
      });
    });
    log.info("Loaded {} Forum vertices", count);
  }

  private void loadPosts(Path dynamicDir, int batchSize) throws IOException {
    Path csvFile = dynamicDir.resolve("post_0_0.csv");
    if (!Files.exists(csvFile)) {
      return;
    }
    String sql = "INSERT INTO Post SET id = :id, creationDate = :creationDate,"
        + " locationIP = :locationIP, browserUsed = :browserUsed,"
        + " language = :language, length = :length,"
        + " imageFile = :imageFile, content = :content";
    long count = processCsv(csvFile, batchSize, batch -> {
      traversal.executeInTx(g -> {
        var ytg = (YTDBGraphTraversalSource) g;
        for (String[] f : batch) {
          ytg.sqlCommand(sql,
              "id", Long.parseLong(f[0]),
              "creationDate", Long.parseLong(f[2]),
              "locationIP", f[3], "browserUsed", f[4],
              "language", f[5], "length", Integer.parseInt(f[7]),
              "imageFile", f[1].isEmpty() ? null : f[1],
              "content", f[6].isEmpty() ? null : f[6]
          ).iterate();
        }
      });
    });
    log.info("Loaded {} Post vertices", count);
  }

  private void loadComments(Path dynamicDir, int batchSize) throws IOException {
    Path csvFile = dynamicDir.resolve("comment_0_0.csv");
    if (!Files.exists(csvFile)) {
      return;
    }
    String sql = "INSERT INTO Comment SET id = :id, creationDate = :creationDate,"
        + " locationIP = :locationIP, browserUsed = :browserUsed,"
        + " content = :content, length = :length";
    long count = processCsv(csvFile, batchSize, batch -> {
      traversal.executeInTx(g -> {
        var ytg = (YTDBGraphTraversalSource) g;
        for (String[] f : batch) {
          ytg.sqlCommand(sql,
              "id", Long.parseLong(f[0]),
              "creationDate", Long.parseLong(f[1]),
              "locationIP", f[2], "browserUsed", f[3],
              "content", f[4], "length", Integer.parseInt(f[5])
          ).iterate();
        }
      });
    });
    log.info("Loaded {} Comment vertices", count);
  }

  private void loadEdgeBySql(Path dir, String filename,
      String edgeLabel, String fromLabel, String toLabel, int batchSize) throws IOException {
    Path csvFile = dir.resolve(filename);
    if (!Files.exists(csvFile)) {
      log.warn("File not found, skipping: {}", csvFile);
      return;
    }
    String sql = "CREATE EDGE " + edgeLabel
        + " FROM (SELECT FROM " + fromLabel + " WHERE id = :fromId)"
        + " TO (SELECT FROM " + toLabel + " WHERE id = :toId)";

    long count = processCsv(csvFile, batchSize, batch -> {
      traversal.executeInTx(g -> {
        var ytg = (YTDBGraphTraversalSource) g;
        for (String[] fields : batch) {
          ytg.sqlCommand(sql,
              "fromId", Long.parseLong(fields[0]),
              "toId", Long.parseLong(fields[1])
          ).iterate();
        }
      });
    });
    log.info("Loaded {} {} edges ({} -> {})", count, edgeLabel, fromLabel, toLabel);
  }

  private void loadEdgeBySqlWithProp(Path dir, String filename,
      String edgeLabel, String fromLabel, String toLabel,
      String propName, int batchSize) throws IOException {
    Path csvFile = dir.resolve(filename);
    if (!Files.exists(csvFile)) {
      return;
    }
    String sql = "CREATE EDGE " + edgeLabel
        + " FROM (SELECT FROM " + fromLabel + " WHERE id = :fromId)"
        + " TO (SELECT FROM " + toLabel + " WHERE id = :toId)"
        + " SET " + propName + " = :propValue";

    long count = processCsv(csvFile, batchSize, batch -> {
      traversal.executeInTx(g -> {
        var ytg = (YTDBGraphTraversalSource) g;
        for (String[] fields : batch) {
          ytg.sqlCommand(sql,
              "fromId", Long.parseLong(fields[0]),
              "toId", Long.parseLong(fields[1]),
              "propValue", Integer.parseInt(fields[2])
          ).iterate();
        }
      });
    });
    log.info("Loaded {} {} edges", count, edgeLabel);
  }

  private void loadEdgeBySqlWithDateProp(Path dir, String filename,
      String edgeLabel, String fromLabel, String toLabel,
      String propName, int batchSize) throws IOException {
    Path csvFile = dir.resolve(filename);
    if (!Files.exists(csvFile)) {
      return;
    }
    String sql = "CREATE EDGE " + edgeLabel
        + " FROM (SELECT FROM " + fromLabel + " WHERE id = :fromId)"
        + " TO (SELECT FROM " + toLabel + " WHERE id = :toId)"
        + " SET " + propName + " = :propValue";

    long count = processCsv(csvFile, batchSize, batch -> {
      traversal.executeInTx(g -> {
        var ytg = (YTDBGraphTraversalSource) g;
        for (String[] fields : batch) {
          ytg.sqlCommand(sql,
              "fromId", Long.parseLong(fields[0]),
              "toId", Long.parseLong(fields[1]),
              "propValue", Long.parseLong(fields[2])
          ).iterate();
        }
      });
    });
    log.info("Loaded {} {} edges", count, edgeLabel);
  }

  private void loadKnowsEdges(Path dynamicDir, int batchSize) throws IOException {
    Path csvFile = dynamicDir.resolve("person_knows_person_0_0.csv");
    if (!Files.exists(csvFile)) {
      return;
    }
    String sql = "CREATE EDGE KNOWS"
        + " FROM (SELECT FROM Person WHERE id = :fromId)"
        + " TO (SELECT FROM Person WHERE id = :toId)"
        + " SET creationDate = :creationDate";

    long count = processCsv(csvFile, batchSize, batch -> {
      traversal.executeInTx(g -> {
        var ytg = (YTDBGraphTraversalSource) g;
        for (String[] fields : batch) {
          long p1 = Long.parseLong(fields[0]);
          long p2 = Long.parseLong(fields[1]);
          long cd = Long.parseLong(fields[2]);
          // Bidirectional
          ytg.sqlCommand(sql, "fromId", p1, "toId", p2, "creationDate", cd).iterate();
          ytg.sqlCommand(sql, "fromId", p2, "toId", p1, "creationDate", cd).iterate();
        }
      });
    });
    log.info("Loaded {} KNOWS edges (bidirectional)", count);
  }

  // ==================== CSV UTILITIES ====================

  @FunctionalInterface
  interface BatchConsumer {
    void accept(List<String[]> batch);
  }

  private long processCsv(Path csvFile, int batchSize, BatchConsumer consumer)
      throws IOException {
    try (Stream<String> lines = Files.lines(csvFile)) {
      var batch = new ArrayList<String[]>(batchSize);
      long count = 0;
      var iterator = lines.skip(1).iterator(); // skip header
      while (iterator.hasNext()) {
        String line = iterator.next();
        if (line.isEmpty()) {
          continue;
        }
        batch.add(line.split("\\|", -1));
        count++;
        if (batch.size() >= batchSize) {
          consumer.accept(List.copyOf(batch));
          batch.clear();
        }
      }
      if (!batch.isEmpty()) {
        consumer.accept(List.copyOf(batch));
      }
      return count;
    }
  }

  private static List<String> parseList(String value) {
    if (value == null || value.isEmpty()) {
      return List.of();
    }
    return List.of(value.split(";"));
  }

  // ==================== PARAMETER SAMPLING ====================

  @SuppressWarnings("unchecked")
  private void sampleQueryParameters() {
    // Person IDs
    List<Map<String, Object>> persons = executeSql(
        "SELECT id FROM Person LIMIT " + MAX_PARAMS);
    List<Long> pIds = new ArrayList<>(
        persons.stream().map(r -> ((Number) r.get("id")).longValue()).toList());
    Collections.shuffle(pIds);
    personIds = pIds.stream().mapToLong(Long::longValue).toArray();

    // Message IDs (Posts + Comments)
    List<Map<String, Object>> messages = executeSql(
        "SELECT id FROM Message LIMIT " + (MAX_PARAMS * 2));
    List<Long> mIds = new ArrayList<>(
        messages.stream().map(r -> ((Number) r.get("id")).longValue()).toList());
    Collections.shuffle(mIds);
    messageIds = mIds.stream().limit(MAX_PARAMS).mapToLong(Long::longValue).toArray();

    // First names (distinct, from loaded persons)
    List<Map<String, Object>> fNames = executeSql(
        "SELECT DISTINCT(firstName) as firstName FROM Person LIMIT " + MAX_PARAMS);
    List<String> fnList = new ArrayList<>(
        fNames.stream().map(r -> r.get("firstName").toString()).toList());
    Collections.shuffle(fnList);
    firstNames = fnList.isEmpty() ? new String[]{"John"} : fnList.toArray(new String[0]);

    // Tag names
    List<Map<String, Object>> tNames = executeSql(
        "SELECT DISTINCT(name) as name FROM Tag LIMIT " + MAX_PARAMS);
    List<String> tnList = new ArrayList<>(
        tNames.stream().map(r -> r.get("name").toString()).toList());
    Collections.shuffle(tnList);
    tagNames = tnList.isEmpty() ? new String[]{"Tag1"} : tnList.toArray(new String[0]);

    // Country names
    List<Map<String, Object>> cNames = executeSql(
        "SELECT DISTINCT(name) as name FROM Place WHERE type = 'Country' LIMIT " + MAX_PARAMS);
    List<String> cnList = new ArrayList<>(
        cNames.stream().map(r -> r.get("name").toString()).toList());
    Collections.shuffle(cnList);
    countryNames = cnList.isEmpty() ? new String[]{"China"} : cnList.toArray(new String[0]);

    // TagClass names
    List<Map<String, Object>> tcNames = executeSql(
        "SELECT DISTINCT(name) as name FROM TagClass LIMIT " + MAX_PARAMS);
    List<String> tcList = new ArrayList<>(
        tcNames.stream().map(r -> r.get("name").toString()).toList());
    Collections.shuffle(tcList);
    tagClassNames = tcList.isEmpty()
        ? new String[]{"MusicalArtist"} : tcList.toArray(new String[0]);

    // Message dates
    List<Map<String, Object>> dates = executeSql(
        "SELECT creationDate FROM Message LIMIT " + MAX_PARAMS);
    List<Date> dateList = new ArrayList<>(dates.stream().map(r -> {
      Object d = r.get("creationDate");
      return d instanceof Date ? (Date) d : new Date(((Number) d).longValue());
    }).toList());
    Collections.shuffle(dateList);
    messageDates = dateList.isEmpty()
        ? new Date[]{new Date()} : dateList.toArray(new Date[0]);
  }
}
