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

package org.apache.jena.tdb1.store.nodetable;

import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;

import org.apache.jena.atlas.lib.ByteBufferLib;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.sse.SSE;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class TestNodec
{
    static private final String asciiBase             = "abc";
    static private final String latinBase             = "Àéíÿ";
    static private final String latinExtraBase        = "ỹﬁﬂ";      // fi-ligature, fl-ligature
    static private final String greekBase             = "αβγ";
    static private final String hewbrewBase           = "אבג";
    static private final String arabicBase            = "ءآأ";
    static private final String symbolsBase           = "☺☻♪♫";
    static private final String chineseBase           = "孫子兵法"; // The Art of War
    static private final String japaneseBase          = "日本";     // Japanese

    @Parameters(name="{0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{{"NodecSSE", new NodecSSE()}});
    }

    private Nodec nodec;

    public TestNodec(String name, Nodec nodec) { this.nodec = nodec; }

    @Test public void nodec_lit_01()    { test ("''"); }
    @Test public void nodec_lit_02()    { test ("'a'"); }
    @Test public void nodec_lit_03()    { test ("'ab'"); }
    @Test public void nodec_lit_04()    { test ("'abc'"); }
    @Test public void nodec_lit_05()    { test ("'abcd'"); }

    @Test public void nodec_lit_06()    { test ("''@e"); }
    @Test public void nodec_lit_07()    { test ("''@en"); }
    @Test public void nodec_lit_08()    { test ("''@EN-uk"); }
    @Test public void nodec_lit_09()    { test ("'\\n'@EN-uk"); }

    @Test public void nodec_lit_10()    { test ("'"+latinBase+"'"); }
    @Test public void nodec_lit_11()    { test ("'"+latinExtraBase+"'"); }
    @Test public void nodec_lit_12()    { test ("'"+greekBase+"'"); }
    @Test public void nodec_lit_13()    { test ("'"+hewbrewBase+"'"); }
    @Test public void nodec_lit_14()    { test ("'"+arabicBase+"'"); }
    @Test public void nodec_lit_15()    { test ("'"+symbolsBase+"'"); }
    @Test public void nodec_lit_16()    { test ("'"+chineseBase+"'"); }
    @Test public void nodec_lit_17()    { test ("'"+japaneseBase+"'"); }

    @Test public void nodec_lit_20()    { test ("1"); }
    @Test public void nodec_lit_21()    { test ("12.3"); }
    @Test public void nodec_lit_22()    { test ("''^^<>"); }

    // Bad Unicode.
    static private final String binaryStr3  = "\u0000";                 // A zero character

    @Test public void nodec_lit_32()    { test ("'"+binaryStr3+"'"); }

    @Test public void nodec_lit_33()    { test("'\uFFFD'"); }
    @Test public void nodec_lit_34()    { test("'''abc\\uFFFDdef'''"); }

    @Test public void nodec_uri_01()    { test ("<>"); }
    @Test public void nodec_uri_02()    { test ("<http://example/>"); }

    // Jena anon ids can have a string form including ":"
    @Test public void nodec_blank_01()  { test (NodeFactory.createBlankNode("a")); }
    @Test public void nodec_blank_02()  { test (NodeFactory.createBlankNode("a:b:c-d")); }
    @Test public void nodec_blank_03()  { test (NodeFactory.createBlankNode()); }

    @Test public void nodec_triple_terms_01()  {
        Node s = NodeFactory.createBlankNode("a");
        Node p = NodeFactory.createURI("http://ex/p");
        Node o = NodeFactory.createLiteralLang("testcase", "en");
        Node t = NodeFactory.createTripleTerm(s, p, o);
        test (t);
    }

    private void test(String sseString) {
        Node n = SSE.parseNode(sseString); // Does not use TextTokenizer.
        test(n);
    }

    private void test(Node n) {
        int maxSize = nodec.maxSize(n);
        ByteBuffer bb = ByteBuffer.allocate(maxSize);
        int x = nodec.encode(n, bb, null);
        int bbLen = bb.limit() - bb.position();
        assertEquals(bbLen, x);
        assertEquals(0, bb.position());

        ByteBuffer bb2 = ByteBufferLib.copyOf(bb);
        Node n2 = nodec.decode(bb2, null);
        assertEquals(n, n2);
    }
}
