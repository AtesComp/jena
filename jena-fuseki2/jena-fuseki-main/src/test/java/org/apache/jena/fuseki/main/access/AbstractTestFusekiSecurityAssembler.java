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

package org.apache.jena.fuseki.main.access;

import static org.apache.jena.fuseki.main.access.AccessTestLib.assertSeen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.apache.jena.atlas.iterator.Iter;
import org.apache.jena.atlas.lib.SetUtils;
import org.apache.jena.atlas.lib.StrUtils;
import org.apache.jena.atlas.logging.LogCtl;
import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.fuseki.Fuseki;
import org.apache.jena.fuseki.main.FusekiLib;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.fuseki.main.sys.InitFusekiMain;
import org.apache.jena.graph.Node;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.sse.SSE;
import org.apache.jena.system.Txn;

/**
 * Tests on the assembler for data access control.
 * <ul>
 * <li>assem-security.ttl - two services "/database" and "/plain" each with their own dataset.
 * <li>assem-security-shared.ttl - two services "/database" and "/plain" with a shared dataset.
 * </ul>
 *
 * @see TestSecurityFilterFuseki TestSecurityFilterFuseki for other HTTP tests.
 */

public abstract class AbstractTestFusekiSecurityAssembler {
    static { InitFusekiMain.init(); }
    static final String DIR = "testing/Access/";

    private final String assemblerFile;
    private static AtomicReference<String> user = new AtomicReference<>();

    private static FusekiServer server;
    private FusekiServer getServer() {
        if ( server == null )
            server = setup(assemblerFile, false);
        return server;
    }

    @AfterAll
    public static void afterClass() {
        server.stop();
        server = null;
        user.set(null);
    }

    @BeforeEach
    public void before() {
        user.set(null);
    }

    private String getURL() {
        FusekiServer server = getServer();
        return server.datasetURL("/database");
    }

    private static FusekiServer setup(String assembler, boolean sharedDatabase) {
        // This will have a warning because authentication is not set (no password
        // file, no security handler) and that's what we want - no authentication -
        // because we use "user.get()"in the tests.
        //
        // Altering the logging level is simply to avoid the Fuseki.configLog message
        // in "build()" without turning warnings off everywhere.

        // -- Start log manipulation.
        String level = LogCtl.getLevel(Fuseki.configLog.getName());
        LogCtl.disable(Fuseki.configLog);

        // In case Fuseki.configLog is active - make sure the test log shows the build()
        // message is expected.
        Fuseki.configLog.warn("  (Expect one warning here)");
        FusekiServer server = FusekiServer.create()
            .port(0)
            .parseConfigFile(assembler)
            .build();
        // Special way to get the servlet remote user (the authorized principle).
        FusekiLib.modifyForAccessCtl(server.getDataAccessPointRegistry(), (a)->user.get());
        server.start();

        LogCtl.setLevel(Fuseki.configLog, level);
        // -- End log manipulation.

        if ( sharedDatabase ) {
            String data = StrUtils.strjoinNL
                ("PREFIX : <http://example/>"
                ,"INSERT DATA {"
                ,"   :s0 :p :o ."
                ,"   GRAPH <http://host/graphname1> {:s1 :p :o}"
                ,"   GRAPH <http://host/graphname3> {:s3 :p :o}"
                ,"   GRAPH <http://host/graphname9> {:s9 :p :o}"
                ,"}"
                );
            String plainUrl = server.datasetURL("/plain");
            try(RDFConnection conn = RDFConnection.connect(plainUrl)) {
                conn.update(data);
            }
        } else {
            DatasetGraph dsg = server.getDataAccessPointRegistry().get("/database").getDataService().getDataset();
            Txn.executeWrite(dsg,  ()->{
                dsg.add(SSE.parseQuad("(<http://host/graphname1> :s1 :p :o)"));
                dsg.add(SSE.parseQuad("(<http://host/graphname3> :s3 :p :o)"));
                dsg.add(SSE.parseQuad("(<http://host/graphname9> :s9 :p :o)"));
            });
        }
        return server;
    }

    protected AbstractTestFusekiSecurityAssembler(String assemberFile) {
        this.assemblerFile = assemberFile;
    }

    private static Node s1 = SSE.parseNode(":s1");
    private static Node s2 = SSE.parseNode(":s2");
    private static Node s3 = SSE.parseNode(":s3");
    private static Node s9 = SSE.parseNode(":s9");

        // The access controlled dataset.

//        { SecurityRegistry
//            user1 -> dft:false / [http://host/graphname2, http://host/graphname1, http://host/graphname3]
//            user2 -> dft:false / [http://host/graphname9]
//            userZ -> dft:false / [http://host/graphnameZ]
//            user3 -> dft:false / [http://host/graphname4, http://host/graphname3, http://host/graphname5]
//          }


    @Test public void query_user1() {
        user.set("user1");
        try(RDFConnection conn = RDFConnection.connect(getURL())) {
            Set<Node> visible = query(conn, "SELECT * { GRAPH ?g { ?s ?p ?o }}");
            assertSeen(visible, s1, s3);
        }
    }

    @Test public void query_userX() {
        user.set("userX"); // No such user in the registry
        try(RDFConnection conn = RDFConnection.connect(getURL())) {
            Set<Node> visible = query(conn, "SELECT * { GRAPH ?g { ?s ?p ?o }}");
            assertSeen(visible);
        }
    }

    @Test public void query_no_user() {
        user.set(null); // No user.
        try(RDFConnection conn = RDFConnection.connect(getURL())) {
            Set<Node> visible = query(conn, "SELECT * { GRAPH ?g { ?s ?p ?o }}");
            assertSeen(visible);
        }
    }

    @Test public void query_user2() {
        user.set("user2");
        try(RDFConnection conn = RDFConnection.connect(getURL())) {
            Set<Node> visible = query(conn, "SELECT * { GRAPH ?g { ?s ?p ?o }}");
            assertSeen(visible, s9);
        }
    }

    @Test public void query_userZ() {
        user.set("userZ"); // No graphs with data.
        try(RDFConnection conn = RDFConnection.connect(getURL())) {
            Set<Node> visible = query(conn, "SELECT * { GRAPH ?g { ?s ?p ?o }}");
            assertSeen(visible);
        }
    }

    // GSP. "http://host/graphname1"
    @Test public void gsp_dft_user1() {
        user.set("user1");
        try(RDFConnection conn = RDFConnection.connect(getURL())) {
            Set<Node> visible = gsp(conn, null);
            assertSeen(visible);
        }
    }

    @Test public void gsp_ng_user1() {
        user.set("user1");
        try(RDFConnection conn = RDFConnection.connect(getURL())) {
            Set<Node> visible = gsp(conn, "http://host/graphname1");
            assertSeen(visible, s1);
        }
    }

    @Test public void gsp_dft_user2() {
        user.set("user2");
        try(RDFConnection conn = RDFConnection.connect(getURL())) {
            gsp404(conn, null);
        }
    }

    @Test public void gsp_ng_user2() {
        user.set("user2");
        try(RDFConnection conn = RDFConnection.connect(getURL())) {
            gsp404(conn, "http://host/graphname1");
        }
    }

    @Test public void gsp_dft_userX() {
        user.set("userX");
        try(RDFConnection conn = RDFConnection.connect(getURL())) {
            gsp404(conn, null);
        }
    }

    @Test public void gsp_ng_userX() {
        user.set("userX");
        try(RDFConnection conn = RDFConnection.connect(getURL())) {
            gsp404(conn, "http://host/graphname1");
        }
    }

    @Test public void gsp_dft_user_null() {
        user.set(null);
        try(RDFConnection conn = RDFConnection.connect(getURL())) {
            gsp404(conn, null);
        }
    }

    @Test public void gsp_ng_user_null() {
        try(RDFConnection conn = RDFConnection.connect(getURL())) {
            gsp404(conn, "http://host/graphname1");
        }
    }

//        // Quads
//        user.set("user1");
//        try(RDFConnection conn = RDFConnection.connect(getURL())) {
//            Set<Node> visible = dataset(conn);
//            assertSeen(visible, s1, s3);
//        }
//        user.set("user2");
//        try(RDFConnection conn = RDFConnection.connect(getURL())) {
//            Set<Node> visible = dataset(conn);
//            assertSeen(visible, s9);
//        }
//        user.set("userX");
//        try(RDFConnection conn = RDFConnection.connect(getURL())) {
//            Set<Node> visible = dataset(conn);
//            assertSeen(visible);
//        }
//        user.set(null);
//        try(RDFConnection conn = RDFConnection.connect(getURL())) {
//            Set<Node> visible = dataset(conn);
//            assertSeen(visible);
//        }


    private Set<Node> gsp(RDFConnection conn, String graphName) {
        Set<Node> results = new HashSet<>();
        Model model = graphName == null ? conn.fetch() : conn.fetch(graphName);
        // Extract subjects.
        Set<Node> seen =
            SetUtils.toSet(
                Iter.asStream(model.listSubjects())
                    .map(Resource::asNode)
                );
        return seen;
    }

    private void gsp404(RDFConnection conn, String graphName) {
        gspHttp(conn, 404, graphName);
    }

    private void gspHttp(RDFConnection conn, int statusCode, String graphName) {
        try {
            gsp(conn, graphName);
            if ( statusCode < 200 && statusCode > 299 )
                fail("Should have responded with "+statusCode);
        } catch (HttpException ex) {
            assertEquals(statusCode, ex.getStatusCode());
        }
    }

    private Set<Node> dataset(RDFConnection conn) {
        Dataset ds = conn.fetchDataset();
        Set<Node> seen =
            SetUtils.toSet(
                Iter.asStream(ds.asDatasetGraph().find())
                    .map(Quad::getSubject)
                    );
        return seen;
    }

    private Set<Node> query(RDFConnection conn, String queryString) {
        Set<Node> results = new HashSet<>();
        conn.queryResultSet(queryString, rs->{
            List<QuerySolution> list = Iter.toList(rs);
            list.stream()
                .map(qs->qs.get("s"))
                .filter(Objects::nonNull)
                .map(RDFNode::asNode)
                .forEach(n->results.add(n));
        });
        return results;
    }
}
