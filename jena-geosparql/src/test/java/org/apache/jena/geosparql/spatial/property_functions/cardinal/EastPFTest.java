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
package org.apache.jena.geosparql.spatial.property_functions.cardinal;

import java.util.*;

import org.apache.jena.geosparql.configuration.GeoSPARQLConfig;
import org.apache.jena.geosparql.implementation.GeometryWrapper;
import org.apache.jena.geosparql.spatial.CardinalDirection;
import org.apache.jena.geosparql.spatial.SearchEnvelope;
import org.apache.jena.geosparql.spatial.SpatialIndex;
import org.apache.jena.geosparql.spatial.SpatialIndexTestData;
import org.apache.jena.graph.Node;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Resource;
import org.junit.After;
import org.junit.AfterClass;
import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 *
 *
 */
public class EastPFTest {

    public EastPFTest() {
    }

    @BeforeClass
    public static void setUpClass() {
        GeoSPARQLConfig.setupNoIndex();
    }

    @AfterClass
    public static void tearDownClass() {
    }

    @Before
    public void setUp() {
    }

    @After
    public void tearDown() {
    }

    /**
     * Test of buildSearchEnvelope method, of class EastPF.
     */
    @Test
    public void testBuildSearchEnvelope() {

        GeometryWrapper geometryWrapper = SpatialIndexTestData.PARIS_GEOMETRY_WRAPPER;
        EastPF instance = new EastPF();
        SearchEnvelope expResult = SearchEnvelope.build(geometryWrapper, SpatialIndexTestData.WGS_84_SRS_INFO, CardinalDirection.EAST);
        SearchEnvelope result = instance.buildSearchEnvelope(geometryWrapper, SpatialIndexTestData.WGS_84_SRS_INFO);
        assertEquals(expResult, result);
    }

    /**
     * Test of checkSearchEnvelope method, of class EastPF.
     */
    @Test
    public void testCheckSearchEnvelope_no_wrap() {

        SpatialIndex spatialIndex = SpatialIndexTestData.createTestIndex();

        //Search Envelope
        GeometryWrapper geometryWrapper = SpatialIndexTestData.HONOLULU_GEOMETRY_WRAPPER;
        EastPF instance = new EastPF();
        SearchEnvelope searchEnvelope = instance.buildSearchEnvelope(geometryWrapper, SpatialIndexTestData.WGS_84_SRS_INFO); //Needed to initialise the search.
        Set<Node> expResult = SpatialIndexTestData.asNodes(List.of(SpatialIndexTestData.LONDON_FEATURE, SpatialIndexTestData.HONOLULU_FEATURE, SpatialIndexTestData.NEW_YORK_FEATURE));
        Collection<Node> result = searchEnvelope.check(spatialIndex);
        assertEquals(expResult, result);
    }

    /**
     * Test of checkSearchEnvelope method, of class EastPF.
     */
    @Test
    public void testCheckSearchEnvelope_wrap() {

        SpatialIndex spatialIndex = SpatialIndexTestData.createTestIndex();

        //Search Envelope
        GeometryWrapper geometryWrapper = SpatialIndexTestData.PERTH_GEOMETRY_WRAPPER;
        EastPF instance = new EastPF();
        SearchEnvelope searchEnvelope = instance.buildSearchEnvelope(geometryWrapper, SpatialIndexTestData.WGS_84_SRS_INFO); //Needed to initialise the search.
        Set<Node> expResult = SpatialIndexTestData.asNodes(List.of(SpatialIndexTestData.AUCKLAND_FEATURE, SpatialIndexTestData.PERTH_FEATURE, SpatialIndexTestData.HONOLULU_FEATURE, SpatialIndexTestData.NEW_YORK_FEATURE));
        Collection<Node> result = searchEnvelope.check(spatialIndex);
        assertEquals(expResult, result);
    }

    /**
     * Test of execEvaluated method, of class EastPF.
     */
    @Test
    public void testExecEvaluated() {


        Dataset dataset = SpatialIndexTestData.createTestDataset();

        String query = "PREFIX spatial: <http://jena.apache.org/spatial#>\n"
                + "\n"
                + "SELECT ?subj\n"
                + "WHERE{\n"
                + "    ?subj spatial:east(48.857487 2.373047) .\n"
                + "}ORDER by ?subj";

        List<Resource> result = new ArrayList<>();
        try (QueryExecution qe = QueryExecutionFactory.create(query, dataset)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution qs = rs.nextSolution();
                Resource feature = qs.getResource("subj");
                result.add(feature);
            }
        }

        List<Resource> expResult = Arrays.asList(SpatialIndexTestData.AUCKLAND_FEATURE, SpatialIndexTestData.PERTH_FEATURE);
        assertEquals(expResult, result);
    }

}
