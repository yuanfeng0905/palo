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

import com.baidu.palo.backup.CatalogMocker;
import com.baidu.palo.catalog.Catalog;
import com.baidu.palo.catalog.Database;
import com.baidu.palo.catalog.TabletInvertedIndex;
import com.baidu.palo.common.AnalysisException;
import com.baidu.palo.common.InternalException;
import com.baidu.palo.mysql.privilege.PaloAuth;
import com.baidu.palo.mysql.privilege.PrivPredicate;
import com.baidu.palo.qe.ConnectContext;
import com.baidu.palo.system.SystemInfoService;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import mockit.Mocked;
import mockit.NonStrictExpectations;
import mockit.internal.startup.Startup;

public class ShowDataStmtTest {

    @Mocked
    private PaloAuth auth;
    @Mocked
    private Analyzer analyzer;
    @Mocked
    private Catalog catalog;
    @Mocked
    private ConnectContext ctx;
    @Mocked
    private TabletInvertedIndex invertedIndex;

    private Database db;

    static {
        Startup.initializeIfPossible();
    }

    @Before
    public void setUp() throws AnalysisException {
        auth = new PaloAuth();

        

        new NonStrictExpectations() {
            {
                Catalog.getCurrentInvertedIndex();
                result = invertedIndex;
            }
        };

        db = CatalogMocker.mockDb();

        new NonStrictExpectations() {
            {
                analyzer.getClusterName();
                result = SystemInfoService.DEFAULT_CLUSTER;

                analyzer.getDefaultDb();
                result = "testCluster:testDb";

                Catalog.getCurrentCatalog();
                result = catalog;

                Catalog.getInstance();
                result = catalog;

                Catalog.getCurrentInvertedIndex();
                result = invertedIndex;

                catalog.getAuth();
                result = auth;

                catalog.getDb(anyString);
                result = db;

                ConnectContext.get();
                result = ctx;

                ctx.getQualifiedUser();
                result = "root";

                ctx.getRemoteIP();
                result = "192.168.1.1";
            }
        };
        

        new NonStrictExpectations() {
            {
                auth.checkGlobalPriv((ConnectContext) any, (PrivPredicate) any);
                result = true;

                auth.checkDbPriv((ConnectContext) any, anyString, (PrivPredicate) any);
                result = true;

                auth.checkTblPriv((ConnectContext) any, anyString, anyString, (PrivPredicate) any);
                result = true;
            }
        };
        
        AccessTestUtil.fetchAdminAccess();
    }

    @Test
    public void testNormal() throws AnalysisException, InternalException {
        ShowDataStmt stmt = new ShowDataStmt(null, null);
        stmt.analyze(analyzer);
        Assert.assertEquals("SHOW DATA FROM `testCluster:testDb`", stmt.toString());
        Assert.assertEquals(2, stmt.getMetaData().getColumnCount());
        Assert.assertEquals(false, stmt.hasTable());
        
        stmt = new ShowDataStmt("testDb", "test_tbl");
        stmt.analyze(analyzer);
        Assert.assertEquals("SHOW DATA FROM `default_cluster:testDb`.`test_tbl`", stmt.toString());
        Assert.assertEquals(3, stmt.getMetaData().getColumnCount());
        Assert.assertEquals(true, stmt.hasTable());
    }
}
