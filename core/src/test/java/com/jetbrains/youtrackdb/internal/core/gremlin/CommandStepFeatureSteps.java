package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import io.cucumber.java.PendingException;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.HashMap;
import java.util.Map;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Assert;

import java.util.ArrayList;
import java.util.List;

public class CommandStepFeatureSteps extends GraphBaseTest {
    private static Exception lastException;
    private static List<Result> lastResultList = new ArrayList<>();

    private static String globalDatabaseName;
    private static String globalDbPath;
    private static boolean isInitialized = false;
    
    // Server mode fields
    private static boolean isServerMode = false;
    private static YouTrackDB remoteYouTrackDB;
    private static YTDBGraphTraversalSource remoteG;
    private static Object serverInstance; // YouTrackDBServer via reflection

    @Given("an empty graph database")
    public void prepare() {
        if (!isInitialized) {
            globalDatabaseName = "CommandTestDB_" + System.currentTimeMillis();
            globalDbPath = "target/databases/" + globalDatabaseName;
            isInitialized = true;
        }

        this.databaseName = globalDatabaseName;
        this.dbPath = globalDbPath;
        this.dbType = DatabaseType.MEMORY;
        this.adminUser = "admin";
        this.adminPassword = "admin";

        if (youTrackDB == null) {
            youTrackDB = createContext();
        }

        if (graph == null) {
            setupGraphDB();
        }

        if (session == null || session.isClosed()) {
            session = openDatabase();
        }
    }

    @Given("a vertex with label {string} and name {string}")
    public void createVertex(String label, String name) {
        prepare();
        if (graph.tx().isOpen()) {
            graph.tx().commit();
        }

        try {
            var graphImpl = (com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphImplAbstract) graph;
            var session = graphImpl.getUnderlyingDatabaseSession();
            session.command("CREATE CLASS " + label + " IF NOT EXISTS EXTENDS V");
        } catch (Exception e) {
            throw e;
        }

        if (!graph.tx().isOpen()) {
            graph.tx().open();
        }

        try {
            graph.addVertex(T.label, label, "name", name);
            graph.tx().commit();
        } catch (Exception e) {
            graph.tx().rollback();
            throw e;
        }
    }

    @When("I execute command {string}")
    public void executeCommand(String commandText) {
        if (graph == null) prepare();

        lastException = null;

        try {
            if (graph.tx().isOpen()) {
                graph.tx().commit();
            }

            if (!graph.tx().isOpen()) {
                graph.tx().open();
            }

            System.out.println("[DEBUG] executeCommand: Wykonuję komendę: " + commandText);

            graph.traversal().command(commandText, java.util.Map.of());

            graph.tx().commit();


        } catch (Exception e) {
            e.printStackTrace();
            lastException = e;
            if (graph != null && graph.tx().isOpen()) {
                try {
                    graph.tx().rollback();
                } catch (Exception rollbackEx) {
                }
            }
        }
    }

    @Then("the database should contain a vertex with name {string}")
    public void verifyVertexExists(String name) {
        if (graph == null) prepare();

        if (graph.tx().isOpen()) {
            graph.tx().commit();
        }

        var graphImpl = (com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphImplAbstract) graph;
        var dbSession = graphImpl.getUnderlyingDatabaseSession();

        dbSession.activateOnCurrentThread();

        String query = "SELECT FROM User WHERE name = ?";
        try (var rs = dbSession.query(query, name)) {
            Assert.assertTrue("Vertex '" + name + "' not found!", rs.hasNext());
        }
    }

    @Then("the database should contain a vertex with name {string} and age {int}")
    public void verifyVertexExists(String name, int age) {
        if (graph == null) prepare();

        if (graph.tx().isOpen()) {
            graph.tx().commit();
        }

        var graphImpl = (com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphImplAbstract) graph;
        var dbSession = graphImpl.getUnderlyingDatabaseSession();

        dbSession.activateOnCurrentThread();

        String query = "SELECT FROM User WHERE name = ? and age = ?";
        try (var rs = dbSession.query(query, name, age)) {
            Assert.assertTrue("Vertex '" + name + age + "' not found!", rs.hasNext());
        }
    }


    @When("I execute query {string}")
    public void execute(String query) {
        try {
            lastException = null;
            lastResultList = new ArrayList<>();
            prepare();

            graph.traversal().command(query, Map.of());

            var graphImpl = (com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphImplAbstract) graph;
            try (ResultSet rs = graphImpl.getUnderlyingDatabaseSession().query(query)) {
                while (rs.hasNext()) {
                    lastResultList.add(rs.next());
                }
            }
        } catch (Exception e) {
            lastException = e;
        }
    }

    @Then("the result should contain {int} vertices")
    public void checkCount(int expectedCount) {
        int actualCount = lastResultList.size();

        Assert.assertEquals("Wrong records number!", (long) expectedCount, (long) actualCount);
    }

    @Then("the command execution should be successful")
    public void checkSuccess() {
        Assert.assertNull("Error: " + (lastException != null ? lastException.getMessage() : ""), lastException);
    }

    @When("I execute command {string} with params:")
    public void executeWithParams(String commandText, Map<String, String> params) {
        if (graph == null) prepare();
        Map<String, Object> convertedParams = new HashMap<>(params);
        if (params.containsKey("age")) {
            convertedParams.put("age", Integer.parseInt(params.get("age")));
        }

        graph.tx().open();
        graph.traversal().command(commandText, convertedParams);
        graph.tx().commit();
    }

    @Then("the command execution should fail")
    public void checkFailure() {
        Assert.assertNotNull("An error should be thrown! lastException is null", lastException);
    }

    @Given("I am using embedded mode")
    public void iAmUsingEmbeddedMode() {

    }

    @Then("the database should not contain a vertex with name {string}")
    public void theDatabaseShouldNotContainAVertexWithName(String arg0) {
        if (graph == null) prepare();

        if (graph.tx().isOpen()) {
            graph.tx().commit();
        }

        var graphImpl = (com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphImplAbstract) graph;
        var dbSession = graphImpl.getUnderlyingDatabaseSession();

        dbSession.activateOnCurrentThread();

        String query = "SELECT FROM User WHERE name = ? and age = ?";
        try (var rs = dbSession.query(query, name)) {
            Assert.assertFalse("Vertex '" + name + "' found!", rs.hasNext());
        }
    }
}