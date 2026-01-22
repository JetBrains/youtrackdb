package com.jetbrains.youtrackdb.internal.server;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.server.YouTrackDBServer;
import com.jetbrains.youtrackdb.internal.server.ServerMain;
import io.cucumber.java.AfterAll;
import io.cucumber.java.BeforeAll;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Assert;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cucumber step definitions for testing command step in SERVER mode.
 * 
 * These tests run against a remote YouTrackDB server (started in @BeforeAll).
 * They test the same functionality as CommandStepFeatureSteps in core module,
 * but through network connection.
 */
public class CommandStepServerFeatureSteps {
    
    private static Exception lastException;
    private static List<Result> lastResultList = new ArrayList<>();
    private static YouTrackDB youTrackDB;
    private static String currentDbName;
    private static YTDBGraphTraversalSource g;
    private static YouTrackDBServer server;
    private static final String SERVER_DIRECTORY = "./target/remotetest-command-step";
    private static boolean serverStarted = false;
    private static final Object serverLock = new Object();
    private static final int SERVER_PORT = 45941;

    @BeforeAll
    public static void startServer() throws Exception {
        synchronized (serverLock) {
            if (serverStarted && youTrackDB != null) {
                System.out.println("[DEBUG] Server mode: Server already started, reusing connection");
                return;
            }

            System.setProperty("YOUTRACKDB_HOME", SERVER_DIRECTORY);
            server = ServerMain.create(false);
            server.startup(
                "classpath:com/jetbrains/youtrackdb/internal/server/youtrackdb-server-integration.yaml");
            server.getConfiguration().port = SERVER_PORT;
            server.activate();

            Thread.sleep(1000);
            
            youTrackDB = YourTracks.instance("localhost", SERVER_PORT, "root", "root");
            serverStarted = true;
        }
    }

    @AfterAll
    public static void stopServer() throws Exception {
        synchronized (serverLock) {
            if (g != null) {
                try {
                    g.close();
                } catch (Exception e) {
                    // Ignore
                }
                g = null;
            }
            if (youTrackDB != null) {
                try {
                    youTrackDB.close();
                } catch (Exception e) {
                    // Ignore
                }
                youTrackDB = null;
            }
            if (server != null) {
                try {
                    server.shutdown();
                } catch (Exception e) {
                    // Ignore
                }
                server = null;
                YouTrackDBEnginesManager.instance().shutdown();
                FileUtils.deleteRecursively(new File(SERVER_DIRECTORY));
                YouTrackDBEnginesManager.instance().startup();
                serverStarted = false;
            }
        }
    }

    @Given("an empty graph database on server")
    public void prepareServerDatabase() {
        synchronized (serverLock) {
            if (!serverStarted || server == null) {
                try {
                    startServer();
                } catch (Exception e) {
                    throw new RuntimeException("Failed to start server", e);
                }
            } else if (youTrackDB == null || !youTrackDB.isOpen()) {
                try {
                    youTrackDB = YourTracks.instance("localhost", SERVER_PORT, "root", "root");
                } catch (Exception e) {
                    throw new RuntimeException("Failed to reconnect to server", e);
                }
            }
        }
        
        currentDbName = "CommandStepTestDB_" + System.currentTimeMillis();

        if (!youTrackDB.exists(currentDbName)) {
            youTrackDB.create(currentDbName, DatabaseType.MEMORY,
                new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));
        }
        
        if (g != null) {
            try {
                g.close();
            } catch (Exception e) {
                // Ignore
            }
        }
        g = youTrackDB.openTraversal(currentDbName, "admin", "admin");
    }

    @Given("a vertex with label {string} and name {string} on server")
    public void createVertexOnServer(String label, String name) {
        if (g == null) {
            prepareServerDatabase();
        }
        try {
            g.command("CREATE CLASS " + label + " IF NOT EXISTS EXTENDS V", Map.of());
        } catch (Exception e) {
            lastException = e;
        }

        g.addV(label).property("name", name).next();
    }

    @Given("I am using server mode")
    public void iAmUsingServerMode() {
        if (g == null) {
            prepareServerDatabase();
        }
    }

    @When("I execute command {string} on server")
    public void executeCommandOnServer(String commandText) {
        lastException = null;

        try {
            if (g == null) {
                prepareServerDatabase();
            }

            if (commandText.trim().toUpperCase().startsWith("CREATE CLASS")) {
                g.command(commandText, Map.of());
            } else {
                g.executeInTx((YTDBGraphTraversalSource gtx) -> {
                    gtx.command(commandText, Map.of());
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
            lastException = e;
        }
    }

    @When("I execute query {string} on server")
    public void executeQueryOnServer(String query) {
        lastException = null;
        try {
            if (g == null) prepareServerDatabase();

            g.command(query, Map.of());

        } catch (Exception e) {
            lastException = e;
            e.printStackTrace();
        }
    }

    @Then("the command execution should be successful")
    public void checkSuccess() {
        Assert.assertNull("Error: " + (lastException != null ? lastException.getMessage() : ""), lastException);
    }


    @Then("the database should contain a vertex with name {string} on server")
    public void verifyVertexExistsOnServer(String name) {
        if (g == null) {
            prepareServerDatabase();
        }

        var vertices = g.V().has("name", name).toList();
        Assert.assertTrue("Vertex '" + name + "' not found! Found: " + vertices.size(), 
            !vertices.isEmpty());
    }

    @Then("the command execution should fail")
    public void checkFailure() {
        Assert.assertNotNull("An error should be thrown! lastException is null", lastException);
    }

    @io.cucumber.java.After
    public void cleanup() throws Exception {
        if (g != null) {
            try {
                g.close();
            } catch (Exception e) {
                // Ignore
            }
            g = null;
        }
        if (youTrackDB != null && currentDbName != null) {
            try {
                youTrackDB.drop(currentDbName);
            } catch (Exception e) {
            }
        }
        currentDbName = null;
    }
}
