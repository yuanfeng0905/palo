// Modifications copyright (C) 2017, Baidu.com, Inc.
// Copyright 2017 The Apache Software Foundation

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.baidu.palo.analysis;

import com.baidu.palo.analysis.BinaryPredicate.Operator;
import com.baidu.palo.common.AnalysisException;
import com.baidu.palo.common.InternalException;
import com.baidu.palo.mysql.privilege.MockedAuth;
import com.baidu.palo.mysql.privilege.PaloAuth;
import com.baidu.palo.qe.ConnectContext;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import mockit.Mocked;
import mockit.internal.startup.Startup;

public class DeleteStmtTest {

    Analyzer analyzer;

    @Mocked
    private PaloAuth auth;
    @Mocked
    private ConnectContext ctx;

    static {
        Startup.initializeIfPossible();
    }

    @Before
    public void setUp() {
        analyzer = AccessTestUtil.fetchAdminAnalyzer(false);
        MockedAuth.mockedAuth(auth);
        MockedAuth.mockedConnectContext(ctx, "root", "192.168.1.1");
    }

    @Test
    public void getMethodTest() {
        BinaryPredicate wherePredicate = new BinaryPredicate(Operator.EQ, new SlotRef(null, "k1"),
                                                             new StringLiteral("abc"));
        DeleteStmt deleteStmt = new DeleteStmt(new TableName("testDb", "testTbl"), "partition", wherePredicate, null);

        Assert.assertEquals("testDb", deleteStmt.getDbName());
        Assert.assertEquals("testTbl", deleteStmt.getTableName());
        Assert.assertEquals("partition", deleteStmt.getPartitionName());
        Assert.assertEquals("DELETE FROM `testDb`.`testTbl` PARTITION partition WHERE `k1` = 'abc'",
                            deleteStmt.toSql());
    }

    @Test
    public void testAnalyze() {
        // case 1
        LikePredicate likePredicate = new LikePredicate(com.baidu.palo.analysis.LikePredicate.Operator.LIKE,
                                                        new SlotRef(null, "k1"),
                                                        new StringLiteral("abc"));
        DeleteStmt deleteStmt = new DeleteStmt(new TableName("testDb", "testTbl"), "partition", likePredicate, null);
        try {
            deleteStmt.analyze(analyzer);
        } catch (AnalysisException | InternalException e) {
            Assert.assertTrue(e.getMessage().contains("should be compound or binary predicate"));
        }

        // case 2
        BinaryPredicate binaryPredicate = new BinaryPredicate(Operator.EQ, new SlotRef(null, "k1"),
                                                              new StringLiteral("abc"));
        CompoundPredicate compoundPredicate =
                new CompoundPredicate(com.baidu.palo.analysis.CompoundPredicate.Operator.OR, binaryPredicate,
                                      binaryPredicate);

        deleteStmt = new DeleteStmt(new TableName("testDb", "testTbl"), "partition", compoundPredicate, null);

        try {
            deleteStmt.analyze(analyzer);
        } catch (AnalysisException | InternalException e) {
            Assert.assertTrue(e.getMessage().contains("should be AND"));
        }

        // case 3
        compoundPredicate = new CompoundPredicate(com.baidu.palo.analysis.CompoundPredicate.Operator.AND,
                                                  binaryPredicate,
                                                  likePredicate);

        deleteStmt = new DeleteStmt(new TableName("testDb", "testTbl"), "partition", compoundPredicate, null);
        try {
            deleteStmt.analyze(analyzer);
        } catch (AnalysisException | InternalException e) {
            Assert.assertTrue(e.getMessage().contains("should be compound or binary predicate"));
        }

        // case 4
        binaryPredicate = new BinaryPredicate(Operator.EQ, new SlotRef(null, "k1"),
                                              new SlotRef(null, "k1"));
        compoundPredicate = new CompoundPredicate(com.baidu.palo.analysis.CompoundPredicate.Operator.AND,
                                                  binaryPredicate,
                                                  binaryPredicate);

        deleteStmt = new DeleteStmt(new TableName("testDb", "testTbl"), "partition", compoundPredicate, null);
        try {
            deleteStmt.analyze(analyzer);
        } catch (AnalysisException | InternalException e) {
            Assert.assertTrue(e.getMessage().contains("Right expr should be value"));
        }

        // case 5
        binaryPredicate = new BinaryPredicate(Operator.EQ, new StringLiteral("abc"),
                                              new SlotRef(null, "k1"));
        compoundPredicate = new CompoundPredicate(com.baidu.palo.analysis.CompoundPredicate.Operator.AND,
                                                  binaryPredicate,
                                                  binaryPredicate);

        deleteStmt = new DeleteStmt(new TableName("testDb", "testTbl"), "partition", compoundPredicate, null);
        try {
            deleteStmt.analyze(analyzer);
        } catch (AnalysisException | InternalException e) {
            Assert.assertTrue(e.getMessage().contains("Left expr should be column name"));
        }
        
        // case 6 partition is null
        binaryPredicate = new BinaryPredicate(Operator.EQ, new StringLiteral("abc"),
                                              new SlotRef(null, "k1"));
        compoundPredicate = new CompoundPredicate(com.baidu.palo.analysis.CompoundPredicate.Operator.AND,
                                                  binaryPredicate,
                                                  binaryPredicate);

        deleteStmt = new DeleteStmt(new TableName("testDb", "testTbl"), null, compoundPredicate, null);
        try {
            deleteStmt.analyze(analyzer);
        } catch (AnalysisException | InternalException e) {
            Assert.assertTrue(e.getMessage().contains("Partition is not set"));
        }

        // normal
        binaryPredicate = new BinaryPredicate(Operator.EQ, new SlotRef(null, "k1"),
                                              new StringLiteral("abc"));
        CompoundPredicate compoundPredicate2 = 
                new CompoundPredicate(com.baidu.palo.analysis.CompoundPredicate.Operator.AND,
                                      binaryPredicate,
                                      binaryPredicate);
        compoundPredicate = new CompoundPredicate(com.baidu.palo.analysis.CompoundPredicate.Operator.AND,
                                                  binaryPredicate,
                                                  compoundPredicate2);

        deleteStmt = new DeleteStmt(new TableName("testDb", "testTbl"), "partition", compoundPredicate, null);
        try {
            deleteStmt.analyze(analyzer);
        } catch (AnalysisException | InternalException e) {
            Assert.fail();
        }
    }

}
