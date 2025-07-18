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

package org.apache.jena.sparql.sse.builders;

import java.util.HashMap;
import java.util.Map;

import org.apache.jena.graph.Node;
import org.apache.jena.sparql.ARQInternalErrorException;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.core.VarExprList;
import org.apache.jena.sparql.expr.*;
import org.apache.jena.sparql.expr.aggregate.Aggregator;
import org.apache.jena.sparql.expr.aggregate.AggregatorFactory;
import org.apache.jena.sparql.sse.Item;
import org.apache.jena.sparql.sse.ItemList;
import org.apache.jena.sparql.sse.Tags;
import org.apache.jena.sparql.util.NodeUtils;

public class BuilderExpr
{
    // Build an expr list when they may also be a single expression.
    // Because expressions are themselves lists, this requires ExprLists to be explicitly tagged

    // (exprlist ...)
    // ((expr1) (expr2))
    // (expr))
    public static ExprList buildExprOrExprList(Item item) {
        if ( item.isTagged(Tags.tagExprList) )
            return buildExprList(item);

        if ( listOfLists(item) )
            return buildExprListUntagged(item.getList(), 0);

        Expr expr = buildExpr(item);
        ExprList exprList = new ExprList(expr);
        return exprList;
    }

    private static boolean listOfLists(Item item) {
        // Atom
        if ( !item.isList() )
            return false;
        // List of atom (inc tagged)
        if ( !item.getList().car().isList() )
            return false;
        // List of lists
        return true;
    }

    public static ExprList buildExprList(Item item) {
        if ( !item.isTagged(Tags.tagExprList) )
            BuilderLib.broken(item, "Not tagged exprlist");

        ItemList list = item.getList();
        return buildExprListUntagged(list, 1);
    }

    private static ExprList buildExprListUntagged(Item item) {
        return buildExprListUntagged(item.getList(), 0);
    }

    private static ExprList buildExprListUntagged(ItemList list, int idx) {
        ExprList exprList = new ExprList();
        for ( int i = idx ; i < list.size() ; i++ ) {
            Item item = list.get(i);
            exprList.add(buildExpr(item));
        }
        return exprList;
    }

    public static VarExprList buildNamedExprOrExprList(Item item) {
        if ( !item.isList() )
            BuilderLib.broken(item, "Not a var expr list");

        ItemList list = item.getList();

        if ( list.isEmpty() )
            return new VarExprList();

        if ( list.car().isList() )
            // List of lists
            return buildNamedExprList(list);
        // One item
        return buildNamedExpr(item);
    }

    public static VarExprList buildNamedExprList(ItemList list) {
        VarExprList x = new VarExprList();
        for ( Item item : list )
            buildNamedExpr(item, x);
        return x;
    }

    public static VarExprList buildNamedExpr(Item item) {
        VarExprList varExprList = new VarExprList();
        buildNamedExpr(item, varExprList);
        return varExprList;
    }

    public static boolean isDefined(String tag) {
        return dispatch.containsKey(tag);
    }

    public static Expr buildExpr(ItemList list)
    {
        if ( list.size() == 0 )
            BuilderLib.broken(list, "Empty list for expression");

        Item item = list.get(0);
        String tag = item.getSymbol();
        if ( tag == null )
            BuilderLib.broken(item, "Null tag");

        Build b = findBuild(tag);
        if ( b == null )
            BuilderLib.broken(item, "No known symbol for "+tag);
        return b.make(list);
    }

    private static void buildNamedExpr(Item item, VarExprList varExprList) {
        if ( item.isNode() ) {
            Var v = BuilderNode.buildVar(item);
            varExprList.add(v);
            return;
        }
        if ( !item.isList() || item.getList().size() != 2 )
            BuilderLib.broken(item, "Not a var or var/expression pair");

        ItemList list = item.getList();

        if ( list.size() == 1 ) {
            Var v = BuilderNode.buildVar(list.car());
            varExprList.add(v);
            return;
        }

        if ( list.size() != 2 )
            BuilderLib.broken(list, "Not a var or var/expression pair");
        Var var = BuilderNode.buildVar(list.get(0));
        Expr expr = BuilderExpr.buildExpr(list.get(1));
        varExprList.add(var, expr);
    }

    // Initialized at end of file.
    private final static Map<String, Build> dispatch;

    public static Expr buildExpr(Item item) {
        // Before testing for a list because of there are RDF terms that are lists: (qtriple).
        if ( item.isNode() )
            return ExprLib.nodeToExpr(item.getNode());

        Expr expr = null;

        if ( item.isList() ) {
            ItemList list = item.getList();

            if ( list.size() == 0 )
                BuilderLib.broken(item, "Empty list for expression");

            Item head = list.get(0);

            if ( head.isNode() ) {
                if ( head.getNode().isVariable() && list.size() == 1 )
                    // The case of (?z)
                    return new ExprVar(Var.alloc(head.getNode()));
                return buildFunctionCall(list);
            } else if ( head.isList() )
                BuilderLib.broken(item, "Head is a list");
            else if ( head.isSymbol() ) {
                if ( item.isTagged(Tags.tagExpr) ) {
                    BuilderLib.checkLength(2, list, "Wrong length: " + item.shortString());
                    item = list.get(1);
                    return buildExpr(item);
                }

                return buildExpr(list);
            }
            throw new ARQInternalErrorException();
        }

        if ( item.isSymbolIgnoreCase(Tags.tagTrue) )
            return NodeValue.TRUE;
        if ( item.isSymbolIgnoreCase(Tags.tagFalse) )
            return NodeValue.FALSE;

        BuilderLib.broken(item, "Not a list or a node or recognized symbol: " + item);
        return null;
    }

    private BuilderExpr() { }

    @FunctionalInterface
    private interface Build { Expr make(ItemList list); }

    private static int numArgs(ItemList list) {
        return list.size() - 1;
    }

    private static Build findBuild(String str) { return dispatch.get(str) ; }

    private static Expr buildFunctionCall(ItemList list)
    {
        Item head = list.get(0);
        Node node = head.getNode();
        if ( node.isBlank() )
            BuilderLib.broken(head, "Blank node for function call!");
        if ( node.isLiteral() )
            BuilderLib.broken(head, "Literal node for function call!");
        ExprList args = buildExprListUntagged(list, 1);
        // Args
        return new E_Function(node.getURI(), args);
    }

    // ---- Dispatch objects
    // Can assume the tag is right (i.e. dispatched correctly)
    // Specials

    private static Build buildRegex = (ItemList list) -> {
        BuilderLib.checkLength(3, 4, list, "Regex: wanted 2 or 3 arguments");
        Expr expr = buildExpr(list.get(1));
        Expr pattern = buildExpr(list.get(2));
        Expr flags = null;
        if ( list.size() != 3 )
            flags = buildExpr(list.get(3));

        return new E_Regex(expr, pattern, flags);
    };

    private static Build buildPlus = (ItemList list) -> {
        BuilderLib.checkLength(2, 3, list, "+: wanted 1 or 2 arguments");
        if ( list.size() == 2 )
        {
            Expr ex = buildExpr(list.get(1));
            return new E_UnaryPlus(ex);
        }

        Expr left = buildExpr(list.get(1));
        Expr right = buildExpr(list.get(2));
        return new E_Add(left, right);
    };

    private static Build buildUnaryPlus = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "unaryplus: wanted 1 argument");
        Expr ex = buildExpr(list.get(1));
        return new E_UnaryPlus(ex);
    };

    private static Build buildMinus = (ItemList list) -> {
        BuilderLib.checkLength(2, 3, list, "-: wanted 1 or 2 arguments");
        if ( list.size() == 2 )
        {
            Expr ex = buildExpr(list.get(1));
            return new E_UnaryMinus(ex);
        }

        Expr left = buildExpr(list.get(1));
        Expr right = buildExpr(list.get(2));
        return new E_Subtract(left, right);
    };

    private static Build buildUnaryMinus = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "unaryminus: wanted 1 argument");
        Expr ex = buildExpr(list.get(1));
        return new E_UnaryMinus(ex);
    };

    private static Build buildEQ = (ItemList list) -> {
        BuilderLib.checkLength(3, list, "=: wanted 2 arguments: got :"+numArgs(list));
        Expr left = buildExpr(list.get(1));
        Expr right = buildExpr(list.get(2));
        return new E_Equals(left, right);
    };

    private static Build buildNE = (ItemList list) -> {
        BuilderLib.checkLength(3, list, "!=: wanted 2 arguments: got :"+numArgs(list));
        Expr left = buildExpr(list.get(1));
        Expr right = buildExpr(list.get(2));
        return new E_NotEquals(left, right);
    };

    private static Build buildGT = (ItemList list) -> {
        BuilderLib.checkLength(3, list, ">: wanted 2 arguments: got :"+numArgs(list));
        Expr left = buildExpr(list.get(1));
        Expr right = buildExpr(list.get(2));
        return new E_GreaterThan(left, right);
    };

    private static Build buildLT = (ItemList list) -> {
        BuilderLib.checkLength(3, list, "<: wanted 2 arguments: got :"+numArgs(list));
        Expr left = buildExpr(list.get(1));
        Expr right = buildExpr(list.get(2));
        return new E_LessThan(left, right);
    };

    private static Build buildLE = (ItemList list) -> {
        BuilderLib.checkLength(3, list, "<=: wanted 2 arguments: got :"+numArgs(list));
        Expr left = buildExpr(list.get(1));
        Expr right = buildExpr(list.get(2));
        return new E_LessThanOrEqual(left, right);
    };

    private static Build buildGE = (ItemList list) -> {
        BuilderLib.checkLength(3, list, ">=: wanted 2 arguments: got :"+numArgs(list));
        Expr left = buildExpr(list.get(1));
        Expr right = buildExpr(list.get(2));
        return new E_GreaterThanOrEqual(left, right);
    };

    private static Build buildOr = (ItemList list) -> {
        BuilderLib.checkLength(3, list, "||: wanted 2 arguments: got :"+numArgs(list));
        Expr left = buildExpr(list.get(1));
        Expr right = buildExpr(list.get(2));
        return new E_LogicalOr(left, right);
    };

    private static Build buildAnd = (ItemList list) -> {
        BuilderLib.checkLength(3, list, "&&: wanted 2 arguments: got :"+numArgs(list));
        Expr left = buildExpr(list.get(1));
        Expr right = buildExpr(list.get(2));
        return new E_LogicalAnd(left, right);
    };

    private static Build buildMult = (ItemList list) -> {
        BuilderLib.checkLength(3, list, "*: wanted 2 arguments: got :"+numArgs(list));
        Expr left = buildExpr(list.get(1));
        Expr right = buildExpr(list.get(2));
        return new E_Multiply(left, right);
    };

    private static Build buildDiv = (ItemList list) -> {
        BuilderLib.checkLength(3, list, "/: wanted 2 arguments: got :"+numArgs(list));
        Expr left = buildExpr(list.get(1));
        Expr right = buildExpr(list.get(2));
        return new E_Divide(left, right);
    };

    private static Build buildIDiv = (ItemList list) -> {
        BuilderLib.checkLength(3, list, "idiv: wanted 2 arguments: got :"+numArgs(list));
        Expr left = buildExpr(list.get(1));
        Expr right = buildExpr(list.get(2));
        return new E_OpNumericIntegerDivide(left, right);
    };

    private static Build buildMod = (ItemList list) -> {
        BuilderLib.checkLength(3, list, "mod: wanted 2 arguments: got :"+numArgs(list));
        Expr left = buildExpr(list.get(1));
        Expr right = buildExpr(list.get(2));
        return new E_OpNumericMod(left, right);
    };

    private static Build buildNot = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "!: wanted 1 argument: got :"+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_LogicalNot(ex);
    };

    private static Build buildStr = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "str: wanted 1 argument: got :"+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_Str(ex);
    };

    private static Build buildStrLang = (ItemList list) -> {
        BuilderLib.checkLength(3, list, "strlang: wanted 2 arguments: got :"+numArgs(list));
        Expr ex1 = buildExpr(list.get(1));
        Expr ex2 = buildExpr(list.get(2));
        return new E_StrLang(ex1, ex2);
    };

    private static Build buildStrLangDir = (ItemList list) -> {
        BuilderLib.checkLength(4, list, "strlangdir: wanted 3 arguments: got :"+numArgs(list));
        Expr ex1 = buildExpr(list.get(1));
        Expr ex2 = buildExpr(list.get(2));
        Expr ex3 = buildExpr(list.get(3));
        return new E_StrLangDir(ex1, ex2, ex3);
    };

    private static Build buildStrDatatype = (ItemList list) -> {
        BuilderLib.checkLength(3, list, "strlang: wanted 2 arguments: got :"+numArgs(list));
        Expr ex1 = buildExpr(list.get(1));
        Expr ex2 = buildExpr(list.get(2));
        return new E_StrDatatype(ex1, ex2);
    };

    private static Build buildRand = (ItemList list) -> {
        BuilderLib.checkLength(1, list, "rand: wanted 0 arguments: got: "+numArgs(list));
        return new E_Random();
    };

    private static Build buildYear = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "year: wanted 1 argument: got: "+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_DateTimeYear(ex);
    };

    private static Build buildMonth = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "month: wanted 1 argument: got: "+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_DateTimeMonth(ex);
    };

    private static Build buildDay = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "day: wanted 1 argument: got: "+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_DateTimeDay(ex);
    };

    private static Build buildHours = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "hours: wanted 1 argument: got: "+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_DateTimeHours(ex);
    };

    private static Build buildMinutes = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "minutes: wanted 1 argument: got: "+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_DateTimeMinutes(ex);
    };

    private static Build buildSeconds = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "seconds: wanted 1 argument: got: "+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_DateTimeSeconds(ex);
    };

    private static Build buildTimezone = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "timezone: wanted 1 argument: got: "+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_DateTimeTimezone(ex);
    };

    private static Build buildTZ = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "TZ: wanted 1 argument: got: "+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_DateTimeTZ(ex);
    };

    private static Build buildAdjust = (ItemList list) -> {
        BuilderLib.checkLength(2, 3, list, "adjust: wanted 1 or 2 arguments: got: "+numArgs(list));
        Expr expr1 = buildExpr(list.get(1));
        Expr expr2 = null;
        if ( list.size() > 2 )
            expr2 = buildExpr(list.get(2));
        return new E_AdjustToTimezone(expr1, expr2);
    };

    private static Build buildNow = (ItemList list) -> {
        BuilderLib.checkLength(1, list, "now: wanted 0 arguments: got: "+numArgs(list));
        return new E_Now();
    };

    private static Build buildUUID = (ItemList list) -> {
        BuilderLib.checkLength(1, list, "uuid: wanted 0 arguments: got: "+numArgs(list));
        return new E_UUID();
    };

    private static Build buildStrUUID = (ItemList list) -> {
        BuilderLib.checkLength(1, list, "struuid: wanted 0 arguments: got: "+numArgs(list));
        return new E_StrUUID();
    };

    private static Build buildVersion = (ItemList list) -> {
        BuilderLib.checkLength(1, list, "version: wanted 0 arguments: got: "+numArgs(list));
        return new E_Version();
    };

    private static Build buildMD5 = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "md5: wanted 1 argument: got: "+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_MD5(ex);
    };

    private static Build buildSHA1 = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "md5: wanted 1 argument: got: "+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_SHA1(ex);
    };

    private static Build buildSHA224 = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "md5: wanted 1 argument: got: "+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_SHA224(ex);
    };

    private static Build buildSHA256 = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "md5: wanted 1 argument: got: "+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_SHA256(ex);
    };

    private static Build buildSHA384 = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "md5: wanted 1 argument: got: "+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_SHA384(ex);
    };

    private static Build buildSHA512 = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "md5: wanted 1 argument: got: "+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_SHA512(ex);
    };

    private static Build buildStrlen = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "strlen: wanted 1 argument: got: "+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_StrLength(ex);
    };

    private static Build buildSubstr = (ItemList list) -> {
        BuilderLib.checkLength(3,4, list, "substr: wanted 2 or 3 arguments: got: "+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        Expr x = buildExpr(list.get(2));
        Expr y = null;
        if ( list.size() == 4 )
            y = buildExpr(list.get(3));
        return new E_StrSubstring(ex, x, y);
    };

    private static Build buildStrReplace = (ItemList list) -> {
        BuilderLib.checkLength(4, 5, list, "replace: wanted 3 or 4 arguments: got: "+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        Expr x = buildExpr(list.get(2));
        Expr y = buildExpr(list.get(3));
        Expr z = null;
        if ( list.size() == 5 )
            z = buildExpr(list.get(4));
        return new E_StrReplace(ex, x, y, z);
    };

    private static Build buildStrUppercase = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "ucase: wanted 1 argument: got: "+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_StrUpperCase(ex);
    };

    private static Build buildStrLowercase = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "lcase: wanted 1 argument: got: "+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_StrLowerCase(ex);
    };

    private static Build buildStrEnds = (ItemList list) -> {
        BuilderLib.checkLength(3, list, "strends: wanted 2 arguments: got: "+numArgs(list));
        Expr ex1 = buildExpr(list.get(1));
        Expr ex2 = buildExpr(list.get(2));
        return new E_StrEndsWith(ex1, ex2);
    };

    private static Build buildStrStarts = (ItemList list) -> {
        BuilderLib.checkLength(3, list, "strstarts: wanted 2 arguments: got: "+numArgs(list));
        Expr ex1 = buildExpr(list.get(1));
        Expr ex2 = buildExpr(list.get(2));
        return new E_StrStartsWith(ex1, ex2);
    };

    private static Build buildStrBefore = (ItemList list) -> {
        BuilderLib.checkLength(3, list, "strbefore: wanted 2 arguments: got: "+numArgs(list));
        Expr ex1 = buildExpr(list.get(1));
        Expr ex2 = buildExpr(list.get(2));
        return new E_StrBefore(ex1, ex2);
    };

    private static Build buildStrAfter = (ItemList list) -> {
        BuilderLib.checkLength(3, list, "strafter: wanted 2 arguments: got: "+numArgs(list));
        Expr ex1 = buildExpr(list.get(1));
        Expr ex2 = buildExpr(list.get(2));
        return new E_StrAfter(ex1, ex2);
    };

    private static Build buildStrContains = (ItemList list) -> {
        BuilderLib.checkLength(3, list, "contains: wanted 2 arguments: got: "+numArgs(list));
        Expr ex1 = buildExpr(list.get(1));
        Expr ex2 = buildExpr(list.get(2));
        return new E_StrContains(ex1, ex2);
    };

    private static Build buildStrEncode = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "encode: wanted 1 argument: got: "+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_StrEncodeForURI(ex);
    };

    private static Build buildNumAbs = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "abs: wanted 1 argument: got: "+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_NumAbs(ex);
    };

    private static Build buildNumRound = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "round: wanted 1 argument: got: "+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_NumRound(ex);
    };

    private static Build buildNumCeiling = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "ceiling: wanted 1 argument: got: "+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_NumCeiling(ex);
    };

    private static Build buildNumFloor = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "floor: wanted 1 argument: got: "+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_NumFloor(ex);
    };

    private static Build buildLang = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "lang: wanted 1 argument: got :"+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_Lang(ex);
    };

    private static Build buildLangDir = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "langdir: wanted 1 argument: got :"+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_LangDir(ex);
    };
    private static Build buildLangMatches = (ItemList list) -> {
        BuilderLib.checkLength(3, list, "langmatches: wanted 2 arguments: got :"+numArgs(list));
        Expr left = buildExpr(list.get(1));
        Expr right = buildExpr(list.get(2));
        return new E_LangMatches(left, right);
    };

    private static Build buildSameTerm = (ItemList list) -> {
        BuilderLib.checkLength(3, list, "sameterm: wanted 2 arguments: got :"+numArgs(list));
        Expr left = buildExpr(list.get(1));
        Expr right = buildExpr(list.get(2));
        return new E_SameTerm(left, right);
    };

    private static Build buildSameValue = (ItemList list) -> {
        BuilderLib.checkLength(3, list, "samevalue: wanted 2 arguments: got :"+numArgs(list));
        Expr left = buildExpr(list.get(1));
        Expr right = buildExpr(list.get(2));
        return new E_SameValue(left, right);
    };

    private static Build buildDatatype = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "datatype: wanted 1 argument: got :"+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_Datatype(ex);
    };

    private static Build buildBound = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "bound: wanted 1 argument: got :"+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_Bound(ex);
    };

    private static Build buildCoalesce = (ItemList list) -> {
        ExprList exprs = buildExprListUntagged(list, 1);
        return new E_Coalesce(exprs);
    };

    private static Build buildConcat = (ItemList list) -> {
        ExprList exprs = buildExprListUntagged(list, 1);
        return new E_StrConcat(exprs);
    };

    private static Build buildConditional = (ItemList list) -> {
        BuilderLib.checkLength(4, list, "IF: wanted 3 arguments: got :"+numArgs(list));
        Expr ex1 = buildExpr(list.get(1));
        Expr ex2 = buildExpr(list.get(2));
        Expr ex3 = buildExpr(list.get(3));
        return new E_Conditional(ex1, ex2, ex3);
    };

    private static Build buildIsIRI = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "isIRI: wanted 1 argument: got :"+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_IsIRI(ex);
    };

    private static Build buildIsURI = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "isURI: wanted 1 argument: got :"+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_IsURI(ex);
    };

    private static Build buildIsBlank = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "isBlank: wanted 1 argument: got: "+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_IsBlank(ex);
    };

    private static Build buildIsLiteral = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "isLiteral: wanted 1 argument: got: "+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_IsLiteral(ex);
    };

    private static Build buildIsNumeric = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "isNumeric: wanted 1 argument: got: "+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_IsNumeric(ex);
    };

    private static Build buildIsLang = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "isLang: wanted 1 argument: got: "+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_HasLang(ex);
    };

    private static Build buildIsLangDir = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "isLangDir: wanted 1 argument: got: "+numArgs(list));
        Expr ex = buildExpr(list.get(1));
        return new E_HasLangDir(ex);
    };

    private static Build buildExists = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "exists: wanted 1 argument: got: "+numArgs(list));
        Op op = BuilderOp.build(list.get(1));
        return new E_Exists(op);
    };

    private static Build buildNotExists = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "notexists: wanted 1 argument: got: "+numArgs(list));
        Op op = BuilderOp.build(list.get(1));
        return new E_NotExists(op);
    };

    private static Build buildBNode = (ItemList list) -> {
        BuilderLib.checkLength(1, 2, list, "bnode: wanted 0 or 1 argument: got: "+numArgs(list));
        if ( list.size() == 1 )
            return E_BNode.create();
        Expr expr = buildExpr(list.get(1));
        return E_BNode.create(expr);
    };


    private static String asString(Item item) {
        Node n = item.getNode();
        if ( ! NodeUtils.isSimpleString(n) )
            BuilderLib.broken(item, "Need string: "+item);
        return n.getLiteralLexicalForm();
    }

    // BASE hidden argument.
    private static Build buildIri = (ItemList list) -> {
        BuilderLib.checkLength(2, 3, list, "iri: wanted 1 or 2 arguments: got: "+numArgs(list));
        if ( numArgs(list) == 1 ) {
            Expr expr = buildExpr(list.get(1));
            return new E_IRI(null, expr);
        }
        String str = asString(list.get(1));
        Expr expr2 = buildExpr(list.get(2));
        return new E_IRI(str, expr2);
    };

    private static Build buildUri = (ItemList list) -> {
        BuilderLib.checkLength(2, 3, list, "uri: wanted 1 or 2 arguments: got: "+numArgs(list));
        if ( numArgs(list) == 1 ) {
            Expr expr = buildExpr(list.get(1));
            return new E_URI(null, expr);
        }
        String str = asString(list.get(1));
        Expr expr2 = buildExpr(list.get(2));
        return new E_URI(str, expr2);
    };

    // IRI extension - IRI(base, rel)
    private static Build buildIri2 = (ItemList list) -> {
        BuilderLib.checkLength(3, 4, list, "iri2: wanted 2 or 3 arguments: got: "+numArgs(list));
        if ( numArgs(list) == 2 ) {
            Expr expr1 = buildExpr(list.get(1));
            Expr expr2 = buildExpr(list.get(2));
            return new E_IRI2(expr1, null, expr2);
        }
        String baseStr = asString(list.get(1));
        Expr expr1 = buildExpr(list.get(2));
        Expr expr2 = buildExpr(list.get(3));
        return new E_IRI2(expr1, baseStr, expr2);
    };

    private static Build buildUri2 = (ItemList list) -> {
        BuilderLib.checkLength(3, 4, list, "iri2: wanted 2 or 3 arguments: got: "+numArgs(list));
        if ( numArgs(list) == 2 ) {
            Expr expr1 = buildExpr(list.get(1));
            Expr expr2 = buildExpr(list.get(2));
            return new E_URI2(expr1, expr2);
        }
        String baseStr = asString(list.get(1));
        Expr expr1 = buildExpr(list.get(2));
        Expr expr2 = buildExpr(list.get(3));
        return new E_URI2(expr1, baseStr, expr2);
    };


    private static Build buildIn = (ItemList list) -> {
        BuilderLib.checkLengthAtLeast(1, list, "in: wanted 1 or more arguments: got: "+numArgs(list));
        Item lhs = list.car();
        Expr expr = buildExpr(list.get(1));
        ExprList eList = buildExprListUntagged(list, 2);
        return new E_OneOf(expr, eList);
    };

    private static Build buildNotIn = (ItemList list) -> {
        BuilderLib.checkLengthAtLeast(1, list, "notin: wanted 1 or more arguments: got: "+numArgs(list));
        Item lhs = list.car();
        Expr expr = buildExpr(list.get(1));
        ExprList eList = buildExprListUntagged(list, 2);
        return new E_NotOneOf(expr, eList);
    };

    private static Build buildSubject = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "subject: wanted 1 argument: got: "+numArgs(list));
        Expr expr = buildExpr(list.get(1));
        return new E_TripleSubject(expr);
    };

    private static Build buildPredicate = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "predicate: wanted 1 argument: got: "+numArgs(list));
        Expr expr = buildExpr(list.get(1));
        return new E_TriplePredicate(expr);
    };

    private static Build buildObject = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "object: wanted 1 argument: got: "+numArgs(list));
        Expr expr = buildExpr(list.get(1));
        return new E_TripleObject(expr);
    };

    private static Build buildTripleFn = (ItemList list) -> {
        BuilderLib.checkLength(4, list, "triple: wanted 3 arguments: got: "+numArgs(list));
        Expr expr1 = buildExpr(list.get(1));
        Expr expr2 = buildExpr(list.get(2));
        Expr expr3 = buildExpr(list.get(3));
        return new E_TripleFn(expr1, expr2, expr3);
    };

    private static Build buildIsTriple = (ItemList list) -> {
        BuilderLib.checkLength(2, list, "isTriple: wanted 1 argument: got: "+numArgs(list));
        Expr expr = buildExpr(list.get(1));
        return new E_IsTriple(expr);
    };

    private static Build buildCall = (ItemList list) -> {
        ExprList args = buildExprListUntagged(list, 1);
        // Args
        return new E_Call(args);
    };

    // ---- Aggregate functions
    // (count)
    // (count distinct)
    // (count ?var)
    // (count distinct ?var)
    // Need a canonical name for the variable that will be set by the aggregation.
    // Aggregator.getVarName.

    private static boolean startsWithDistinct(ItemList x) {
        if ( x.size() > 0 && x.car().isSymbol(Tags.tagDistinct) )
            return true;
        return false;
    }

    private static abstract class BuildAggCommon implements Build {
        @Override
        public Expr make(ItemList list) {
            ItemList x = list.cdr();    // drop "sum"
            boolean distinct = startsWithDistinct(x);
            if ( distinct )
                x = x.cdr();
            BuilderLib.checkLength(1, x, "Broken syntax: "+list.shortString());
            // (sum ?var)
            Expr expr = buildExpr(x.get(0));
            return make(distinct, expr);
        }

        public abstract Expr make(boolean distinct, Expr expr);
    }

    private static Build buildCount = (ItemList list) -> {
        ItemList x = list.cdr();    // drop "count"
        boolean distinct = startsWithDistinct(x);
        if ( distinct )
            x = x.cdr();

        BuilderLib.checkLength(0, 1, x, "Broken syntax: " + list.shortString());

        Aggregator agg = null;
        if ( x.size() == 0 )
            agg = AggregatorFactory.createCount(distinct);
        else {
            Expr expr = BuilderExpr.buildExpr(x.get(0));
            agg = AggregatorFactory.createCountExpr(distinct, expr);
        }
        return new ExprAggregator(null, agg);
    };

    private interface BuildAgg { Expr aggExpr(boolean distinct, Expr arg); }

    private static Build aggCommonOne(BuildAgg make) {
        return (ItemList list) -> {
            ItemList x = list.cdr();    // drop "sum", "min" etc
            boolean distinct = startsWithDistinct(x);
            if ( distinct )
                x = x.cdr();
            BuilderLib.checkLength(1, x, "Broken syntax: "+list.shortString());
            // (sum ?var)
            Expr expr = buildExpr(x.get(0));
            return make.aggExpr(distinct, expr);
        };
    }

    private static Build buildSum = aggCommonOne((distinct, expr)->{
        Aggregator agg = AggregatorFactory.createSum(distinct, expr);
        return new ExprAggregator(null, agg);
    });

    private static Build buildMin = aggCommonOne((distinct, expr)->{
        Aggregator agg = AggregatorFactory.createMin(distinct, expr);
        return new ExprAggregator(null, agg);
    });

    private static Build buildMax = aggCommonOne((distinct, expr)->{
        Aggregator agg = AggregatorFactory.createMax(distinct, expr);
        return new ExprAggregator(null, agg);
    });

    private static Build buildAvg = aggCommonOne((distinct, expr)->{
        Aggregator agg = AggregatorFactory.createAvg(distinct, expr);
        return new ExprAggregator(null, agg);
    });

    private static Build buildSample = aggCommonOne((distinct, expr)->{
        Aggregator agg = AggregatorFactory.createSample(distinct, expr);
        return new ExprAggregator(null, agg);
    });

    private static Build buildGroupConcat = (ItemList list)-> {
        ItemList x = list.cdr();    // drop "group_concat"
        boolean distinct = startsWithDistinct(x);
        if ( distinct )
            x = x.cdr();

        // Complex syntax:
        // (groupConcat (separator "string") expr )
        if ( x.size() == 0 )
            BuilderLib.broken(list, "Broken syntax: "+list.shortString());
        String separator = null;
        if ( x.get(0).isTagged(Tags.tagSeparator))
        {
            // What about ORDERED BY
            ItemList y = x.get(0).getList();
            BuilderLib.checkLength(2, y, "Broken syntax: "+list);
            Node n = y.get(1).getNode();
            if ( ! NodeUtils.isSimpleString(n) )
                BuilderLib.broken(y, "Need string for separator: "+y);
            separator = n.getLiteralLexicalForm();
            x = x.cdr();
        }

        Expr expr = buildExpr(x.get(0));
        Aggregator agg = AggregatorFactory.createGroupConcat(distinct, expr, separator, null);
        return new ExprAggregator(null, agg);
    };

    private static Build buildCustomAggregate = (ItemList list) -> {
        ItemList x = list.cdr();    // drop "agg"
        if ( x.size() == 0 )
            BuilderLib.broken(list, "Missing IRI for aggregate");

        Item z = x.car();
        if ( ! z.isNodeURI() )
            BuilderLib.broken(list, "Not an IRI for aggregate: "+z);
        x = x.cdr();
        boolean distinct = startsWithDistinct(x);
        if ( distinct )
            x = x.cdr();
        ExprList e = buildExprListUntagged(x, 0);
        Aggregator agg = AggregatorFactory.createCustom(z.getNode().getURI(), distinct, e);
        return new ExprAggregator(null, agg);
    };

    private static Build buildAggNull = (ItemList list) -> {
        BuilderLib.checkLength(1, list, "Broken syntax: "+list.shortString());
        return new ExprAggregator(null, AggregatorFactory.createAggNull());
    };

    private static Map<String, Build> createDispatchTable() {
        Map<String, Build> dispatchMap = new HashMap<>();
        dispatch(dispatchMap, Tags.tagRegex, buildRegex);
        dispatch(dispatchMap, Tags.symEQ, buildEQ);
        dispatch(dispatchMap, Tags.tagEQ, buildEQ);
        dispatch(dispatchMap, Tags.symNE, buildNE);
        dispatch(dispatchMap, Tags.tagNE, buildNE);
        dispatch(dispatchMap, Tags.symGT, buildGT);
        dispatch(dispatchMap, Tags.tagGT, buildGT);
        dispatch(dispatchMap, Tags.symLT, buildLT);
        dispatch(dispatchMap, Tags.tagLT, buildLT);
        dispatch(dispatchMap, Tags.symLE, buildLE);
        dispatch(dispatchMap, Tags.tagLE, buildLE);
        dispatch(dispatchMap, Tags.symGE, buildGE);
        dispatch(dispatchMap, Tags.tagGE, buildGE);
        dispatch(dispatchMap, Tags.symOr, buildOr);
        dispatch(dispatchMap, Tags.tagOr, buildOr);
        dispatch(dispatchMap, Tags.symAnd, buildAnd);
        dispatch(dispatchMap, Tags.tagAnd, buildAnd);
        dispatch(dispatchMap, Tags.symPlus, buildPlus);
        dispatch(dispatchMap, Tags.tagAdd,  buildPlus);
        dispatch(dispatchMap, Tags.symMinus, buildMinus);
        dispatch(dispatchMap, Tags.tagSubtract, buildMinus);
        dispatch(dispatchMap, Tags.tagMinus, buildMinus);    // Not to be confused with Op for SPARQL MINUS

        dispatch(dispatchMap, Tags.tagUnaryPlus, buildUnaryPlus);
        dispatch(dispatchMap, Tags.tagUnaryMinus, buildUnaryMinus);

        dispatch(dispatchMap, Tags.symMult, buildMult);
        dispatch(dispatchMap, Tags.tagMultiply, buildMult);

        dispatch(dispatchMap, Tags.symDiv, buildDiv);
        dispatch(dispatchMap, Tags.tagDivide, buildDiv);

        dispatch(dispatchMap, Tags.tagIDiv, buildIDiv);
        dispatch(dispatchMap, Tags.tagMod, buildMod);

        dispatch(dispatchMap, Tags.tagNot, buildNot);   // Same builders for (not ..) and (! ..)
        dispatch(dispatchMap, Tags.symNot, buildNot);

        dispatch(dispatchMap, Tags.tagStr, buildStr);
        dispatch(dispatchMap, Tags.tagStrLang, buildStrLang);
        dispatch(dispatchMap, Tags.tagStrLangDir, buildStrLangDir);
        dispatch(dispatchMap, Tags.tagStrDatatype, buildStrDatatype);

        dispatch(dispatchMap, Tags.tagYear, buildYear);
        dispatch(dispatchMap, Tags.tagMonth, buildMonth);
        dispatch(dispatchMap, Tags.tagDay, buildDay);
        dispatch(dispatchMap, Tags.tagHours, buildHours);
        dispatch(dispatchMap, Tags.tagMinutes, buildMinutes);
        dispatch(dispatchMap, Tags.tagSeconds, buildSeconds);
        dispatch(dispatchMap, Tags.tagTimezone, buildTimezone);
        dispatch(dispatchMap, Tags.tagTZ, buildTZ);
        dispatch(dispatchMap, Tags.tagAdjust, buildAdjust);

        dispatch(dispatchMap, Tags.tagRand, buildRand);
        dispatch(dispatchMap, Tags.tagNow, buildNow);
        dispatch(dispatchMap, Tags.tagUUID, buildUUID);
        dispatch(dispatchMap, Tags.tagStrUUID, buildStrUUID);
        dispatch(dispatchMap, Tags.tagVersion, buildVersion);

        dispatch(dispatchMap, Tags.tagMD5, buildMD5);
        dispatch(dispatchMap, Tags.tagSHA1, buildSHA1);
        dispatch(dispatchMap, Tags.tagSHA224, buildSHA224);
        dispatch(dispatchMap, Tags.tagSHA256, buildSHA256);
        dispatch(dispatchMap, Tags.tagSHA384, buildSHA384);
        dispatch(dispatchMap, Tags.tagSHA512, buildSHA512);

        dispatch(dispatchMap, Tags.tagStrlen, buildStrlen);
        dispatch(dispatchMap, Tags.tagSubstr, buildSubstr);
        dispatch(dispatchMap, Tags.tagReplace, buildStrReplace);
        dispatch(dispatchMap, Tags.tagStrUppercase, buildStrUppercase);
        dispatch(dispatchMap, Tags.tagStrLowercase, buildStrLowercase);
        dispatch(dispatchMap, Tags.tagStrEnds, buildStrEnds);
        dispatch(dispatchMap, Tags.tagStrStarts, buildStrStarts);
        dispatch(dispatchMap, Tags.tagStrBefore, buildStrBefore);
        dispatch(dispatchMap, Tags.tagStrAfter, buildStrAfter);
        dispatch(dispatchMap, Tags.tagStrContains, buildStrContains);
        dispatch(dispatchMap, Tags.tagStrEncodeForURI, buildStrEncode);

        dispatch(dispatchMap, Tags.tagNumAbs, buildNumAbs);
        dispatch(dispatchMap, Tags.tagNumRound, buildNumRound);
        dispatch(dispatchMap, Tags.tagNumCeiling, buildNumCeiling);
        dispatch(dispatchMap, Tags.tagNumFloor, buildNumFloor);

        dispatch(dispatchMap, Tags.tagLang, buildLang);
        dispatch(dispatchMap, Tags.tagLangDir, buildLangDir);
        dispatch(dispatchMap, Tags.tagLangMatches, buildLangMatches);

        dispatch(dispatchMap, Tags.tagSameTerm, buildSameTerm);
        dispatch(dispatchMap, Tags.tagSameValue, buildSameValue);
        dispatch(dispatchMap, Tags.tagDatatype, buildDatatype);
        dispatch(dispatchMap, Tags.tagBound, buildBound);
        dispatch(dispatchMap, Tags.tagCoalesce, buildCoalesce);
        dispatch(dispatchMap, Tags.tagConcat, buildConcat);
        dispatch(dispatchMap, Tags.tagIf, buildConditional);
        dispatch(dispatchMap, Tags.tagIsIRI, buildIsIRI);
        dispatch(dispatchMap, Tags.tagIsURI, buildIsURI);
        dispatch(dispatchMap, Tags.tagIsBlank, buildIsBlank);
        dispatch(dispatchMap, Tags.tagIsLiteral, buildIsLiteral);

        dispatch(dispatchMap, Tags.tagIsNumeric, buildIsNumeric);
        dispatch(dispatchMap, Tags.tagHasLang, buildIsLang);
        dispatch(dispatchMap, Tags.tagHasLangDir, buildIsLangDir);

        dispatch(dispatchMap, Tags.tagExists, buildExists);
        dispatch(dispatchMap, Tags.tagNotExists, buildNotExists);

        dispatch(dispatchMap, Tags.tagBNode, buildBNode);
        dispatch(dispatchMap, Tags.tagIri, buildIri);
        dispatch(dispatchMap, Tags.tagUri, buildUri);
        dispatch(dispatchMap, Tags.tagIri2, buildIri2);
        dispatch(dispatchMap, Tags.tagUri2, buildUri2);

        dispatch(dispatchMap, Tags.tagIn, buildIn);
        dispatch(dispatchMap, Tags.tagNotIn, buildNotIn);

        dispatch(dispatchMap, Tags.tagSubject, buildSubject);
        dispatch(dispatchMap, Tags.tagPredicate, buildPredicate);
        dispatch(dispatchMap, Tags.tagObject, buildObject);
        dispatch(dispatchMap, Tags.tagFnTriple, buildTripleFn);
        dispatch(dispatchMap, Tags.tagIsTriple, buildIsTriple);

        dispatch(dispatchMap, Tags.tagCall, buildCall);

        dispatch(dispatchMap, Tags.tagCount, buildCount);
        dispatch(dispatchMap, Tags.tagSum, buildSum);
        dispatch(dispatchMap, Tags.tagMin, buildMin);
        dispatch(dispatchMap, Tags.tagMax, buildMax);
        dispatch(dispatchMap, Tags.tagAvg, buildAvg);
        dispatch(dispatchMap, Tags.tagSample, buildSample);
        dispatch(dispatchMap, Tags.tagGroupConcat, buildGroupConcat);
        dispatch(dispatchMap, Tags.tagAgg,  buildCustomAggregate);
        return dispatchMap;
    }

    private static void dispatch(Map<String, Build> dispatchMap, String tag, Build build) {
        if ( dispatchMap.containsKey(tag) )
            throw new ARQInternalErrorException("Multiple dispatch entry for "+tag);
        dispatchMap.put(tag, build);
    }

    //  --------
    // After constants!
    static { dispatch = createDispatchTable(); }
}
