/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jena.fuseki;

import static org.apache.jena.fuseki.mgt.ServerMgtConst.opBackup;
import static org.apache.jena.fuseki.mgt.ServerMgtConst.opCompact;
import static org.apache.jena.fuseki.mgt.ServerMgtConst.opDatasets;
import static org.apache.jena.fuseki.mgt.ServerMgtConst.opListBackups;
import static org.apache.jena.fuseki.server.ServerConst.opStats;
import static org.apache.jena.http.HttpOp.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.SystemUtils;
import org.apache.jena.atlas.io.IO;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.jena.atlas.junit.AssertExtra;
import org.apache.jena.atlas.lib.Lib;
import org.apache.jena.atlas.logging.LogCtl;
import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.atlas.web.TypedInputStream;
import org.apache.jena.fuseki.ctl.JsonConstCtl;
import org.apache.jena.fuseki.test.HttpTest;
import org.apache.jena.fuseki.webapp.FusekiWebapp;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.riot.WebContent;
import org.apache.jena.web.HttpSC;
import org.awaitility.Awaitility;
import org.junit.After;
import org.junit.Assert;
import org.junit.AssumptionViolatedException;
import org.junit.Before;
import org.junit.jupiter.api.Test;

/** Tests of the admin functionality */
public class TestWebappAdminAddDeleteDatasetFile extends AbstractFusekiWebappTest {

    // Name of the dataset in the assembler file.
    static String dsTest      = "test-ds1";
    static String dsTestInf   = "test-ds4";

    // There are two Fuseki-TDB2 tests: add_delete_dataset_6() and compact_01().
    //
    // On certain build systems (GH action/Linux under load, ASF Jenkins sometimes),
    // add_delete_dataset_6 fails (transactions active), or compact_01 (gets a 404),
    // if the two databases are the same.
    static String dsTestTdb2a = "test-tdb2a";
    static String dsTestTdb2b = "test-tdb2b";
    static String fileBase    = "testing/";

    @Before public void setLogging() {
        LogCtl.setLevel(Fuseki.backupLogName, "ERROR");
        LogCtl.setLevel(Fuseki.compactLogName,"ERROR");
        LogCtl.setLevel(Fuseki.adminLogName,"ERROR");
        Awaitility.setDefaultPollDelay(20,TimeUnit.MILLISECONDS);
        Awaitility.setDefaultPollInterval(50,TimeUnit.MILLISECONDS);
    }

    @After public void unsetLogging() {
        LogCtl.setLevel(Fuseki.backupLogName, "WARN");
        LogCtl.setLevel(Fuseki.compactLogName,"WARN");
        LogCtl.setLevel(Fuseki.adminLogName,"WARN");
    }

    private static void withFileEnabled(Runnable action) {
        System.setProperty(FusekiWebapp.allowConfigFileProperty, "true");
        try {
            action.run();
        } finally {
            System.getProperties().remove(FusekiWebapp.allowConfigFileProperty);
        }
    }

    // --- List all datasets

    @Test public void list_datasets_1() {
        try ( TypedInputStream in = httpGet(ServerCtl.urlRoot()+"$/"+opDatasets); ) {
            IO.skipToEnd(in);
        }
    }

    @Test public void list_datasets_2() {
        try ( TypedInputStream in = httpGet(ServerCtl.urlRoot()+"$/"+opDatasets) ) {
            assertEqualsIgnoreCase(WebContent.contentTypeJSON, in.getContentType());
            JsonValue v = JSON.parseAny(in);
            assertNotNull(v.getAsObject().get("datasets"));
            checkJsonDatasetsAll(v);
        }
    }

    // Specific dataset
    @Test public void list_datasets_3() {
        checkExists(ServerCtl.datasetName());
    }

    // Specific dataset
    @Test public void list_datasets_4() {
        HttpTest.expect404( () -> getDatasetDescription("does-not-exist") );
    }

    // Specific dataset
    @Test public void list_datasets_5() {
        JsonValue v = getDatasetDescription(ServerCtl.datasetName());
        checkJsonDatasetsOne(v.getAsObject());
    }

    @Test public void add_dataset_blocked() {
        // Do without enabling upload of configuration files - expected to fail
        HttpException ex = assertThrows(HttpException.class, ()->addTestDatasetPerform(fileBase+"config-ds-plain-1.ttl"));
        assertEquals(ex.getStatusCode(), HttpSC.BAD_REQUEST_400);
    }

    // Specific dataset
    @Test public void add_delete_dataset_1() {
        checkNotThere(dsTest);

        addTestDataset();

        // Check exists.
        checkExists(dsTest);

        // Remove it.
        deleteDataset(dsTest);
        checkNotThere(dsTest);
    }

    // Try to add twice
    @Test public void add_delete_dataset_2() {
        checkNotThere(dsTest);

        withFileEnabled(()->{
            try {
                Path f = Path.of(fileBase+"config-ds-plain-1.ttl");
                {
                    httpPost(ServerCtl.urlRoot()+"$/"+opDatasets,
                             WebContent.contentTypeTurtle+"; charset="+WebContent.charsetUTF8,
                             BodyPublishers.ofFile(f));
                }
                // Check exists.
                checkExists(dsTest);
                try {
                } catch (HttpException ex) {
                    httpPost(ServerCtl.urlRoot()+"$/"+opDatasets,
                             WebContent.contentTypeTurtle+"; charset="+WebContent.charsetUTF8,
                             BodyPublishers.ofFile(f));
                    assertEquals(HttpSC.CONFLICT_409, ex.getStatusCode());
                }
            } catch (IOException ex) { IO.exception(ex); return; }
        });
        // Check exists.
        checkExists(dsTest);
        deleteDataset(dsTest);
    }

    @Test public void add_delete_dataset_3() {
        checkNotThere(dsTest);
        addTestDataset();
        checkExists(dsTest);
        deleteDataset(dsTest);
        checkNotThere(dsTest);
        addTestDataset();
        checkExists(dsTest);
        deleteDataset(dsTest);
    }

    @Test public void add_delete_dataset_4() {
        checkNotThere(dsTest);
        checkNotThere(dsTestInf);
        addTestDatasetInf();
        checkNotThere(dsTest);
        checkExists(dsTestInf);

        deleteDataset(dsTestInf);
        checkNotThere(dsTestInf);
        addTestDatasetInf();
        checkExists(dsTestInf);
        deleteDataset(dsTestInf);
    }

    @Test public void add_delete_dataset_5() {
        // New style operations : cause two fuseki:names
        addTestDataset(fileBase+"config-ds-plain-2.ttl");
        checkExists("test-ds2");
    }

    @Test public void add_delete_dataset_6() {
        String testDB = dsTestTdb2a;
        assumeNotWindows();

        checkNotThere(testDB);

        addTestDatasetTDB2(testDB);

        // Check exists.
        checkExists(testDB);

        // Remove it.
        deleteDataset(testDB);
        checkNotThere(testDB);
    }

    @Test public void add_delete_dataset_TDB_1() {
            String testDB = dsTestTdb2a;
            assumeNotWindows();

            checkNotThere(testDB);

            addTestDatasetTDB2(testDB);

            // Check exists.
            checkExists(testDB);

            // Remove it.
            deleteDataset(testDB);
            checkNotThere(testDB);
    }

    @Test public void add_delete_dataset_TDB_2() {
            // This has location "--mem--"
            String testDB = dsTestTdb2b;
            checkNotThere(testDB);
            addTestDatasetTDB2(testDB);
            // Check exists.
            checkExists(testDB);
            // Remove it.
            deleteDataset(testDB);
            checkNotThere(testDB);
    }

    @Test public void add_error_1() {
        HttpTest.execWithHttpException(HttpSC.BAD_REQUEST_400,
                                         ()-> addTestDataset(fileBase+"config-ds-bad-name-1.ttl"));
    }

    @Test public void add_error_2() {
        HttpTest.execWithHttpException(HttpSC.BAD_REQUEST_400,
                                         ()-> addTestDataset(fileBase+"config-ds-bad-name-2.ttl"));
    }

    @Test public void add_error_3() {
        HttpTest.execWithHttpException(HttpSC.BAD_REQUEST_400,
                                         ()-> addTestDataset(fileBase+"config-ds-bad-name-3.ttl"));
    }

    @Test public void add_error_4() {
        HttpTest.execWithHttpException(HttpSC.BAD_REQUEST_400,
                                         ()-> addTestDataset(fileBase+"config-ds-bad-name-4.ttl"));
    }

    @Test public void noOverwriteExistingConfigFile() throws IOException {
        var workingDir = Paths.get("").toAbsolutePath();
        var path = workingDir.resolve(TS_FusekiWebapp.FusekiTestBase+"/configuration/test-ds0-empty.ttl");
        var dbConfig = path.toFile();
        dbConfig.createNewFile();
        try {
            // refresh the file system so that the file exists
            dbConfig = path.toFile();
            assertTrue (dbConfig.exists());
            assertEquals(0, dbConfig.length());

            // Try to override the file with a new configuration.
            String ct = WebContent.contentTypeHTMLForm;
            String body = "dbName=test-ds0-empty&dbType=mem";
            HttpException ex = assertThrows(org.apache.jena.atlas.web.HttpException.class,
                                            ()-> httpPost(ServerCtl.urlRoot()+"$/" + opDatasets, ct, body));
            assertEquals(HttpSC.CONFLICT_409, ex.getStatusCode());
            // refresh the file system
            dbConfig = path.toFile();
            assertTrue(dbConfig.exists());
            assertEquals("File should be still empty", 0, dbConfig.length());
        }
        finally {
            // Clean up the file.
            if (Files.exists(path)) {
                Files.delete(path);
            }
        }
    }

    @Test public void delete_dataset_1() {
        String name = "NoSuchDataset";
        HttpTest.expect404( ()-> httpDelete(ServerCtl.urlRoot()+"$/"+opDatasets+"/"+name) );
    }

    // ---- Backup

    @Test public void create_backup_1() {
        String id = null;
        try {
            JsonValue v = httpPostRtnJSON(ServerCtl.urlRoot() + "$/" + opBackup + "/" + ServerCtl.datasetName());
            id = v.getAsObject().getString("taskId");
        } finally {
            waitForTasksToFinish(1000, 10, 20000);
        }
        Assert.assertNotNull(id);
        checkInTasks(id);

        // Check a backup was created
        try ( TypedInputStream in = httpGet(ServerCtl.urlRoot()+"$/"+opListBackups) ) {
            assertEqualsIgnoreCase(WebContent.contentTypeJSON, in.getContentType());
            JsonValue v = JSON.parseAny(in);
            assertNotNull(v.getAsObject().get("backups"));
            JsonArray a = v.getAsObject().get("backups").getAsArray();
            Assert.assertEquals(1, a.size());
        }

        JsonValue task = getTask(id);
        Assert.assertNotNull(id);
        // Expect task success
        Assert.assertTrue("Expected task to be marked as successful", task.getAsObject().getBoolean(JsonConstCtl.success));
    }

    @Test
    public void create_backup_2() {
        HttpTest.expect400(()->{
            JsonValue v = httpPostRtnJSON(ServerCtl.urlRoot() + "$/" + opBackup + "/noSuchDataset");
        });
    }

    @Test public void list_backups_1() {
        try ( TypedInputStream in = httpGet(ServerCtl.urlRoot()+"$/"+opListBackups) ) {
            assertEqualsIgnoreCase(WebContent.contentTypeJSON, in.getContentType());
            JsonValue v = JSON.parseAny(in);
            assertNotNull(v.getAsObject().get("backups"));
        }
    }

    // ---- Compact

    @Test public void compact_01() {
        assumeNotWindows();

        String testDB = dsTestTdb2b;
        try {
            checkNotThere(testDB);
            addTestDatasetTDB2(testDB);
            checkExists(testDB);

            String id = null;
            try {
                JsonValue v = httpPostRtnJSON(ServerCtl.urlRoot() + "$/" + opCompact + "/" + testDB);
                id = v.getAsObject().getString(JsonConstCtl.taskId);
            } finally {
                waitForTasksToFinish(1000, 500, 20_000);
            }
            Assert.assertNotNull(id);
            checkInTasks(id);

            JsonValue task = getTask(id);
            // ----
            // The result assertion is throwing NPE occasionally on some heavily loaded CI servers.
            // This may be because of server or test code encountering a very long wait.
            // These next statements check the assumed structure of the return.
            Assert.assertNotNull("Task value", task);
            JsonObject obj = task.getAsObject();
            Assert.assertNotNull("Task.getAsObject()", obj);
            // Provoke code to get a stacktrace.
            obj.getBoolean(JsonConstCtl.success);
            // ----
            // The assertion we really wanted to check.
            // Check task success
            Assert.assertTrue("Expected task to be marked as successful", task.getAsObject().getBoolean(JsonConstCtl.success));
        } finally {
            deleteDataset(testDB);
        }
    }

    @Test public void compact_02() {
        HttpTest.expect400(()->{
            JsonValue v = httpPostRtnJSON(ServerCtl.urlRoot() + "$/" + opCompact + "/noSuchDataset");
        });
    }

    private void assumeNotWindows() {
        if (SystemUtils.IS_OS_WINDOWS)
            throw new AssumptionViolatedException("Test may be unstable on Windows due to inability to delete memory-mapped files");
    }

    @Test public void stats_1() {
        JsonValue v = execGetJSON(ServerCtl.urlRoot()+"$/"+opStats);
        checkJsonStatsAll(v);
    }

    @Test public void stats_2() {
        addTestDataset();
        JsonValue v = execGetJSON(ServerCtl.urlRoot()+"$/"+opStats+ServerCtl.datasetPath());
        checkJsonStatsAll(v);
        deleteDataset(dsTest);
    }

    @Test public void stats_3() {
        addTestDataset();
        HttpTest.expect404(()-> execGetJSON(ServerCtl.urlRoot()+"$/"+opStats+"/DoesNotExist"));
        deleteDataset(dsTest);
    }

    @Test public void stats_4() {
        JsonValue v = execPostJSON(ServerCtl.urlRoot()+"$/"+opStats);
        checkJsonStatsAll(v);
    }

    @Test public void stats_5() {
        addTestDataset();
        JsonValue v = execPostJSON(ServerCtl.urlRoot()+"$/"+opStats+ServerCtl.datasetPath());
        checkJsonStatsAll(v);
        deleteDataset(dsTest);
    }

    // Async task testing

    private void assertEqualsIgnoreCase(String contenttypejson, String contentType) {}

    private static JsonValue getTask(String taskId) {
        String url = ServerCtl.urlRoot()+"$/tasks/"+taskId;
        return httpGetJson(url);
    }

    private static JsonValue getDatasetDescription(String dsName) {
        if ( dsName.startsWith("/") )
            dsName = dsName.substring(1);
        try (TypedInputStream in = httpGet(ServerCtl.urlRoot() + "$/" + opDatasets + "/" + dsName)) {
            AssertExtra.assertEqualsIgnoreCase(WebContent.contentTypeJSON, in.getContentType());
            JsonValue v = JSON.parse(in);
            return v;
        }
    }

    // -- Add

    private static void addTestDataset() {
        addTestDataset(fileBase+"config-ds-plain-1.ttl");
    }

    private static void addTestDatasetInf() {
        addTestDataset(fileBase+"config-ds-inf.ttl");
    }

    private static void addTestDatasetTDB2(String DBname) {
        Objects.nonNull(DBname);
        if ( DBname.equals(dsTestTdb2a) ) {
            addTestDataset(fileBase+"config-tdb2a.ttl");
            return;
        }
        if ( DBname.equals(dsTestTdb2b) ) {
            addTestDataset(fileBase+"config-tdb2b.ttl");
            return;
        }
        throw new IllegalArgumentException("No configuration for "+DBname);
    }

    private static void addTestDataset(String filename) {
        withFileEnabled(()->{
            addTestDatasetPerform(filename);
        });
    }

    private static void addTestDatasetPerform(String filename) {
        try {
            Path f = Path.of(filename);
            BodyPublisher body = BodyPublishers.ofFile(f);
            String ct = WebContent.contentTypeTurtle;
            httpPost(ServerCtl.urlRoot()+"$/"+opDatasets, ct, body);
        } catch (FileNotFoundException e) {
            IO.exception(e);
        }
    }

    private static void deleteDataset(String name) {
        httpDelete(ServerCtl.urlRoot()+"$/"+opDatasets+"/"+name);
    }

    private static void checkTask(JsonValue v) {
        assertNotNull(v);
        assertTrue(v.isObject());
        //System.out.println(v);
        JsonObject obj = v.getAsObject();
        try {
            assertTrue(obj.hasKey("task"));
            assertTrue(obj.hasKey("taskId"));
            // Not present until it runs : "started"
        } catch (AssertionError ex) {
            System.out.println(obj);
            throw ex;
        }
    }

   private static void checkInTasks(String x) {
       String url = ServerCtl.urlRoot()+"$/tasks";
       JsonValue v = httpGetJson(url);
       assertTrue(v.isArray());
       JsonArray array = v.getAsArray();
       int found = 0;
       for ( int i = 0; i < array.size(); i++ ) {
           JsonValue jv = array.get(i);
           assertTrue(jv.isObject());
           JsonObject obj = jv.getAsObject();
           checkTask(obj);
           if ( obj.getString("taskId").equals(x) ) {
               found++;
           }
        }
       assertEquals("Occurrence of taskId count", 1, found);
    }

   private static List<String> runningTasks(String... x) {
       String url = ServerCtl.urlRoot()+"$/tasks";
       JsonValue v = httpGetJson(url);
       assertTrue(v.isArray());
       JsonArray array = v.getAsArray();
       List<String> running = new ArrayList<>();
       for ( int i = 0; i < array.size(); i++ ) {
           JsonValue jv = array.get(i);
           assertTrue(jv.isObject());
           JsonObject obj = jv.getAsObject();
           if ( isRunning(obj) )
               running.add(obj.getString("taskId"));
       }
       return running;
   }

   /**
    * Wait for tasks to all finish.
    * Algorithm: wait for {@code pause}, then start polling for upto {@code maxWaitMillis}.
    * Intervals in milliseconds.
    * @param pauseMillis
    * @param pollInterval
    * @param maxWaitMillis
    * @return
    */
   private static boolean waitForTasksToFinish(int pauseMillis, int pollInterval, int maxWaitMillis) {
       // Wait for them to finish.
       // Divide into chunks
       if ( pauseMillis > 0 )
           Lib.sleep(pauseMillis);
       long start = System.currentTimeMillis();
       long endTime = start + maxWaitMillis;
       final int intervals = maxWaitMillis/pollInterval;
       long now = start;
       for (int i = 0 ; i < intervals ; i++ ) {
           // May have waited (much) longer than the pollInterval : heavily loaded build systems.
           if ( now-start > maxWaitMillis )
               break;
           List<String> x = runningTasks();
           if ( x.isEmpty() )
               return true;
           Lib.sleep(pollInterval);
           now = System.currentTimeMillis();
       }
       return false;
   }

   private static boolean isRunning(JsonObject taskObj) {
       checkTask(taskObj);
       return taskObj.hasKey("started") &&  ! taskObj.hasKey("finished");
   }

    private static void askPing(String name) {
        if ( name.startsWith("/") )
            name = name.substring(1);
        try ( TypedInputStream in = httpGet(ServerCtl.urlRoot()+name+"/sparql?query=ASK%7B%7D") ) {
            IO.skipToEnd(in);
        }
    }

    private static void adminPing(String name) {
        try ( TypedInputStream in = httpGet(ServerCtl.urlRoot()+"$/"+opDatasets+"/"+name) ) {
            IO.skipToEnd(in);
        }
    }

    private static void checkExists(String name)  {
        adminPing(name);
        askPing(name);
    }

    private static void checkNotThere(String name) {
        String n = (name.startsWith("/")) ? name.substring(1) : name;
        // Check gone exists.
        HttpTest.expect404(()->  adminPing(n) );
        HttpTest.expect404(() -> askPing(n) );
    }

    private static void checkJsonDatasetsAll(JsonValue v) {
        assertNotNull(v.getAsObject().get("datasets"));
        JsonArray a = v.getAsObject().get("datasets").getAsArray();
        for ( JsonValue v2 : a )
            checkJsonDatasetsOne(v2);
    }

    private static void checkJsonDatasetsOne(JsonValue v) {
        assertTrue(v.isObject());
        JsonObject obj = v.getAsObject();
        assertNotNull(obj.get("ds.name"));
        assertNotNull(obj.get("ds.services"));
        assertNotNull(obj.get("ds.state"));
        assertTrue(obj.get("ds.services").isArray());
    }

    private static void checkJsonStatsAll(JsonValue v) {
        assertNotNull(v.getAsObject().get("datasets"));
        JsonObject a = v.getAsObject().get("datasets").getAsObject();
        for ( String dsname : a.keys() ) {
            JsonValue obj = a.get(dsname).getAsObject();
            checkJsonStatsOne(obj);
        }
    }

    private static void checkJsonStatsOne(JsonValue v) {
        checkJsonStatsCounters(v);
        JsonObject obj1 = v.getAsObject().get("endpoints").getAsObject();
        for ( String srvName : obj1.keys() ) {
            JsonObject obj2 = obj1.get(srvName).getAsObject();
            assertTrue(obj2.hasKey("description"));
            assertTrue(obj2.hasKey("operation"));
            checkJsonStatsCounters(obj2);
        }
    }

    private static void checkJsonStatsCounters(JsonValue v) {
        JsonObject obj = v.getAsObject();
        assertTrue(obj.hasKey("Requests"));
        assertTrue(obj.hasKey("RequestsGood"));
        assertTrue(obj.hasKey("RequestsBad"));
    }

    private static JsonValue execGetJSON(String url) {
        try ( TypedInputStream in = httpGet(url) ) {
            AssertExtra.assertEqualsIgnoreCase(WebContent.contentTypeJSON, in.getContentType());
            return JSON.parse(in);
        }
    }

    private static JsonValue execPostJSON(String url) {
        try ( TypedInputStream in = httpPostStream(url, null, null, null) ) {
            AssertExtra.assertEqualsIgnoreCase(WebContent.contentTypeJSON, in.getContentType());
            return JSON.parse(in);
        }
    }

    static int count(RDFConnection conn) {
        try ( QueryExecution qExec = conn.query("SELECT (count(*) AS ?C) { ?s ?p ?o }")) {
            return qExec.execSelect().next().getLiteral("C").getInt();
        }
    }
}
