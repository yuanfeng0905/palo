// Copyright (c) 2017, Baidu.com, Inc. All Rights Reserved

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

package com.baidu.palo.backup;

import com.baidu.palo.alter.RollupHandler;
import com.baidu.palo.alter.SchemaChangeHandler;
import com.baidu.palo.catalog.AggregateType;
import com.baidu.palo.catalog.Catalog;
import com.baidu.palo.catalog.Column;
import com.baidu.palo.catalog.ColumnType;
import com.baidu.palo.catalog.DataProperty;
import com.baidu.palo.catalog.Database;
import com.baidu.palo.catalog.DistributionInfo;
import com.baidu.palo.catalog.HashDistributionInfo;
import com.baidu.palo.catalog.KeysType;
import com.baidu.palo.catalog.MaterializedIndex;
import com.baidu.palo.catalog.MaterializedIndex.IndexState;
import com.baidu.palo.catalog.MysqlTable;
import com.baidu.palo.catalog.OlapTable;
import com.baidu.palo.catalog.Partition;
import com.baidu.palo.catalog.PartitionInfo;
import com.baidu.palo.catalog.PartitionKey;
import com.baidu.palo.catalog.PrimitiveType;
import com.baidu.palo.catalog.RandomDistributionInfo;
import com.baidu.palo.catalog.RangePartitionInfo;
import com.baidu.palo.catalog.Replica;
import com.baidu.palo.catalog.Replica.ReplicaState;
import com.baidu.palo.catalog.SinglePartitionInfo;
import com.baidu.palo.catalog.Tablet;
import com.baidu.palo.catalog.TabletMeta;
import com.baidu.palo.common.AnalysisException;
import com.baidu.palo.common.DdlException;
import com.baidu.palo.common.util.Util;
import com.baidu.palo.load.Load;
import com.baidu.palo.mysql.privilege.PaloAuth;
import com.baidu.palo.mysql.privilege.PrivPredicate;
import com.baidu.palo.persist.EditLog;
import com.baidu.palo.qe.ConnectContext;
import com.baidu.palo.system.SystemInfoService;
import com.baidu.palo.thrift.TStorageMedium;
import com.baidu.palo.thrift.TStorageType;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Range;

import org.easymock.EasyMock;

import java.util.List;
import java.util.Map;

public class CatalogMocker {
    // user
    public static final String ROOTUSER = "root";
    public static final String SUPERUSER = "superuser";
    public static final String TESTUSER = "testuser";
    public static final String BLOCKUSER = "blockuser";

    // backend
    public static final long BACKEND1_ID = 10000;
    public static final long BACKEND2_ID = 10001;
    public static final long BACKEND3_ID = 10002;

    // db
    public static final String TEST_DB_NAME = "test_db";
    public static final long TEST_DB_ID = 20000;
    
    // single partition olap table
    public static final String TEST_TBL_NAME = "test_tbl";
    public static final long TEST_TBL_ID = 30000;

    public static final String TEST_SINGLE_PARTITION_NAME = TEST_TBL_NAME;
    public static final long TEST_SINGLE_PARTITION_ID = 40000;
    public static final long TEST_TABLET0_ID = 60000;
    public static final long TEST_REPLICA0_ID = 70000;
    public static final long TEST_REPLICA1_ID = 70001;
    public static final long TEST_REPLICA2_ID = 70002;

    // mysql table
    public static final long TEST_MYSQL_TABLE_ID = 30001;
    public static final String MYSQL_TABLE_NAME = "test_mysql";
    public static final String MYSQL_HOST = "mysql-host";
    public static final long MYSQL_PORT = 8321;
    public static final String MYSQL_USER = "mysql-user";
    public static final String MYSQL_PWD = "mysql-pwd";
    public static final String MYSQL_DB = "mysql-db";
    public static final String MYSQL_TBL = "mysql-tbl";
    
    // partition olap table with a rollup
    public static final String TEST_TBL2_NAME = "test_tbl2";
    public static final long TEST_TBL2_ID = 30002;

    public static final String TEST_PARTITION1_NAME = "p1";
    public static final long TEST_PARTITION1_ID = 40001;
    public static final String TEST_PARTITION2_NAME = "p2";
    public static final long TEST_PARTITION2_ID = 40002;
    public static final long TEST_BASE_TABLET_P1_ID = 60001;
    public static final long TEST_REPLICA3_ID = 70003;
    public static final long TEST_REPLICA4_ID = 70004;
    public static final long TEST_REPLICA5_ID = 70005;

    public static final long TEST_BASE_TABLET_P2_ID = 60002;
    public static final long TEST_REPLICA6_ID = 70006;
    public static final long TEST_REPLICA7_ID = 70007;
    public static final long TEST_REPLICA8_ID = 70008;

    public static final String TEST_ROLLUP_NAME = "test_rollup";
    public static final long TEST_ROLLUP_ID = 50000;

    public static final long TEST_ROLLUP_TABLET_P1_ID = 60003;
    public static final long TEST_REPLICA9_ID = 70009;
    public static final long TEST_REPLICA10_ID = 70010;
    public static final long TEST_REPLICA11_ID = 70011;

    public static final long TEST_ROLLUP_TABLET_P2_ID = 60004;
    public static final long TEST_REPLICA12_ID = 70012;
    public static final long TEST_REPLICA13_ID = 70013;
    public static final long TEST_REPLICA14_ID = 70014;

    public static final String WRONG_DB = "wrong_db";

    // schema
    public static List<Column> TEST_TBL_BASE_SCHEMA = Lists.newArrayList();
    public static int SCHEMA_HASH;
    public static int ROLLUP_SCHEMA_HASH;

    public static List<Column> TEST_MYSQL_SCHEMA = Lists.newArrayList();
    public static List<Column> TEST_ROLLUP_SCHEMA = Lists.newArrayList();

    static {
        Column k1 = new Column("k1", new ColumnType(PrimitiveType.TINYINT, -1, -1, -1), true, null, "", "key1");
        Column k2 = new Column("k2", new ColumnType(PrimitiveType.SMALLINT, -1, -1, -1), true, null, "", "key2");
        Column k3 = new Column("k3", new ColumnType(PrimitiveType.INT, -1, -1, -1), true, null, "", "key3");
        Column k4 = new Column("k4", new ColumnType(PrimitiveType.BIGINT, -1, -1, -1), true, null, "", "key4");
        Column k5 = new Column("k5", new ColumnType(PrimitiveType.LARGEINT, -1, -1, -1), true, null, "", "key5");
        Column k6 = new Column("k6", new ColumnType(PrimitiveType.DATE, -1, -1, -1), true, null, "", "key6");
        Column k7 = new Column("k7", new ColumnType(PrimitiveType.DATETIME, -1, -1, -1), true, null, "", "key7");
        Column k8 = new Column("k8", new ColumnType(PrimitiveType.DECIMAL, -1, 10, 3), true, null, "", "key8");
        k1.setIsKey(true);
        k2.setIsKey(true);
        k3.setIsKey(true);
        k4.setIsKey(true);
        k5.setIsKey(true);
        k6.setIsKey(true);
        k7.setIsKey(true);
        k8.setIsKey(true);

        Column v1 = new Column("v1", new 
                        ColumnType(PrimitiveType.CHAR, 10, -1, -1), false, AggregateType.REPLACE, "none", " value1");
        Column v2 = new Column("v2", new 
                        ColumnType(PrimitiveType.FLOAT, -1, -1, -1), false, AggregateType.MAX, "none", " value2");
        Column v3 = new Column("v3", new 
                        ColumnType(PrimitiveType.DOUBLE, -1, -1, -1), false, AggregateType.MIN, "none", " value3");
        Column v4 = new Column("v4", new 
                        ColumnType(PrimitiveType.INT, -1, -1, -1), false, AggregateType.SUM, "", " value4");

        TEST_TBL_BASE_SCHEMA.add(k1);
        TEST_TBL_BASE_SCHEMA.add(k2);
        TEST_TBL_BASE_SCHEMA.add(k3);
        TEST_TBL_BASE_SCHEMA.add(k4);
        TEST_TBL_BASE_SCHEMA.add(k5);
        TEST_TBL_BASE_SCHEMA.add(k6);
        TEST_TBL_BASE_SCHEMA.add(k7);
        TEST_TBL_BASE_SCHEMA.add(k8);

        TEST_TBL_BASE_SCHEMA.add(v1);
        TEST_TBL_BASE_SCHEMA.add(v2);
        TEST_TBL_BASE_SCHEMA.add(v3);
        TEST_TBL_BASE_SCHEMA.add(v4);

        TEST_MYSQL_SCHEMA.add(k1);
        TEST_MYSQL_SCHEMA.add(k2);
        TEST_MYSQL_SCHEMA.add(k3);
        TEST_MYSQL_SCHEMA.add(k4);
        TEST_MYSQL_SCHEMA.add(k5);
        TEST_MYSQL_SCHEMA.add(k6);
        TEST_MYSQL_SCHEMA.add(k7);
        TEST_MYSQL_SCHEMA.add(k8);

        TEST_ROLLUP_SCHEMA.add(k1);
        TEST_ROLLUP_SCHEMA.add(k2);
        TEST_ROLLUP_SCHEMA.add(v1);

        SCHEMA_HASH = Util.schemaHash(0, TEST_TBL_BASE_SCHEMA, null, 0);
        ROLLUP_SCHEMA_HASH = Util.schemaHash(0, TEST_ROLLUP_SCHEMA, null, 0);
    }

    private static PaloAuth fetchAdminAccess() {
        PaloAuth auth = EasyMock.createMock(PaloAuth.class);
        EasyMock.expect(auth.checkGlobalPriv(EasyMock.isA(ConnectContext.class),
                                             EasyMock.isA(PrivPredicate.class))).andReturn(true).anyTimes();
        EasyMock.expect(auth.checkDbPriv(EasyMock.isA(ConnectContext.class), EasyMock.isA(String.class),
                                         EasyMock.isA(PrivPredicate.class))).andReturn(true).anyTimes();
        EasyMock.expect(auth.checkTblPriv(EasyMock.isA(ConnectContext.class), EasyMock.isA(String.class),
                                          EasyMock.isA(String.class),
                                          EasyMock.isA(PrivPredicate.class))).andReturn(true).anyTimes();
        EasyMock.replay(auth);
        return auth;
    }

    public static SystemInfoService fetchSystemInfoService() {
        SystemInfoService clusterInfo = EasyMock.createMock(SystemInfoService.class);
        EasyMock.replay(clusterInfo);
        return clusterInfo;
    }

    public static Database mockDb() throws AnalysisException {
        // mock all meta obj
        Database db = new Database(TEST_DB_ID, TEST_DB_NAME);

        // 1. single partition olap table
        MaterializedIndex baseIndex = new MaterializedIndex(TEST_TBL_ID, IndexState.NORMAL);
        DistributionInfo distributionInfo = new RandomDistributionInfo(32);
        Partition partition =
                new Partition(TEST_SINGLE_PARTITION_ID, TEST_SINGLE_PARTITION_NAME, baseIndex, distributionInfo);
        PartitionInfo partitionInfo = new SinglePartitionInfo();
        partitionInfo.setReplicationNum(TEST_SINGLE_PARTITION_ID, (short) 3);
        DataProperty dataProperty = new DataProperty(TStorageMedium.HDD);
        partitionInfo.setDataProperty(TEST_SINGLE_PARTITION_ID, dataProperty);
        OlapTable olapTable = new OlapTable(TEST_TBL_ID, TEST_TBL_NAME, TEST_TBL_BASE_SCHEMA,
                                            KeysType.AGG_KEYS, partitionInfo, distributionInfo);

        Tablet tablet0 = new Tablet(TEST_TABLET0_ID);
        TabletMeta tabletMeta = new TabletMeta(TEST_DB_ID, TEST_TBL_ID, TEST_SINGLE_PARTITION_ID,
                                               TEST_TBL_ID, SCHEMA_HASH);
        baseIndex.addTablet(tablet0, tabletMeta);
        Replica replica0 = new Replica(TEST_REPLICA0_ID, BACKEND1_ID, ReplicaState.NORMAL);
        Replica replica1 = new Replica(TEST_REPLICA1_ID, BACKEND2_ID, ReplicaState.NORMAL);
        Replica replica2 = new Replica(TEST_REPLICA2_ID, BACKEND3_ID, ReplicaState.NORMAL);

        tablet0.addReplica(replica0);
        tablet0.addReplica(replica1);
        tablet0.addReplica(replica2);

        olapTable.setIndexSchemaInfo(TEST_TBL_ID, TEST_TBL_NAME, TEST_TBL_BASE_SCHEMA, 0, SCHEMA_HASH, (short) 1);
        olapTable.setStorageTypeToIndex(TEST_TBL_ID, TStorageType.COLUMN);
        olapTable.addPartition(partition);
        db.createTable(olapTable);

        // 2. mysql table
        Map<String, String> mysqlProp = Maps.newHashMap();
        mysqlProp.put("host", MYSQL_HOST);
        mysqlProp.put("port", String.valueOf(MYSQL_PORT));
        mysqlProp.put("user", MYSQL_USER);
        mysqlProp.put("password", MYSQL_PWD);
        mysqlProp.put("database", MYSQL_DB);
        mysqlProp.put("table", MYSQL_TBL);
        MysqlTable mysqlTable = null;
        try {
            mysqlTable = new MysqlTable(TEST_MYSQL_TABLE_ID, MYSQL_TABLE_NAME, TEST_MYSQL_SCHEMA, mysqlProp);
        } catch (DdlException e) {
            e.printStackTrace();
        }
        db.createTable(mysqlTable);

        // 3. range partition olap table
        MaterializedIndex baseIndexP1 = new MaterializedIndex(TEST_TBL2_ID, IndexState.NORMAL);
        MaterializedIndex baseIndexP2 = new MaterializedIndex(TEST_TBL2_ID, IndexState.NORMAL);
        DistributionInfo distributionInfo2 =
                new HashDistributionInfo(32, Lists.newArrayList(TEST_TBL_BASE_SCHEMA.get(1)));
        Partition partition1 =
                new Partition(TEST_PARTITION1_ID, TEST_PARTITION1_NAME, baseIndexP1, distributionInfo2);
        Partition partition2 =
                new Partition(TEST_PARTITION2_ID, TEST_PARTITION2_NAME, baseIndexP2, distributionInfo2);
        RangePartitionInfo rangePartitionInfo = new RangePartitionInfo(Lists.newArrayList(TEST_TBL_BASE_SCHEMA.get(0)));
        
        PartitionKey rangeP1Lower =
                PartitionKey.createInfinityPartitionKey(Lists.newArrayList(TEST_TBL_BASE_SCHEMA.get(0)), false);
        PartitionKey rangeP1Upper =
                PartitionKey.createPartitionKey(Lists.newArrayList("10"),
                                                Lists.newArrayList(TEST_TBL_BASE_SCHEMA.get(0)));
        Range<PartitionKey> rangeP1 = Range.closedOpen(rangeP1Lower, rangeP1Upper);
        rangePartitionInfo.setRange(TEST_PARTITION1_ID, rangeP1);

        PartitionKey rangeP2Lower =
                PartitionKey.createPartitionKey(Lists.newArrayList("10"),
                                                Lists.newArrayList(TEST_TBL_BASE_SCHEMA.get(0)));
        PartitionKey rangeP2Upper =
                PartitionKey.createPartitionKey(Lists.newArrayList("20"),
                                                Lists.newArrayList(TEST_TBL_BASE_SCHEMA.get(0)));
        Range<PartitionKey> rangeP2 = Range.closedOpen(rangeP2Lower, rangeP2Upper);
        rangePartitionInfo.setRange(TEST_PARTITION2_ID, rangeP2);

        rangePartitionInfo.setReplicationNum(TEST_PARTITION1_ID, (short) 3);
        rangePartitionInfo.setReplicationNum(TEST_PARTITION2_ID, (short) 3);
        DataProperty dataPropertyP1 = new DataProperty(TStorageMedium.HDD);
        DataProperty dataPropertyP2 = new DataProperty(TStorageMedium.HDD);
        rangePartitionInfo.setDataProperty(TEST_PARTITION1_ID, dataPropertyP1);
        rangePartitionInfo.setDataProperty(TEST_PARTITION2_ID, dataPropertyP2);

        OlapTable olapTable2 = new OlapTable(TEST_TBL2_ID, TEST_TBL2_NAME, TEST_TBL_BASE_SCHEMA,
                                             KeysType.AGG_KEYS, rangePartitionInfo, distributionInfo2);

        Tablet baseTabletP1 = new Tablet(TEST_BASE_TABLET_P1_ID);
        TabletMeta tabletMetaBaseTabletP1 = new TabletMeta(TEST_DB_ID, TEST_TBL2_ID, TEST_PARTITION1_ID,
                                                           TEST_TBL2_ID, SCHEMA_HASH);
        baseIndexP1.addTablet(baseTabletP1, tabletMetaBaseTabletP1);
        Replica replica3 = new Replica(TEST_REPLICA3_ID, BACKEND1_ID, ReplicaState.NORMAL);
        Replica replica4 = new Replica(TEST_REPLICA4_ID, BACKEND2_ID, ReplicaState.NORMAL);
        Replica replica5 = new Replica(TEST_REPLICA5_ID, BACKEND3_ID, ReplicaState.NORMAL);

        baseTabletP1.addReplica(replica3);
        baseTabletP1.addReplica(replica4);
        baseTabletP1.addReplica(replica5);

        Tablet baseTabletP2 = new Tablet(TEST_BASE_TABLET_P2_ID);
        TabletMeta tabletMetaBaseTabletP2 = new TabletMeta(TEST_DB_ID, TEST_TBL2_ID, TEST_PARTITION2_ID,
                                                           TEST_TBL2_ID, SCHEMA_HASH);
        baseIndexP2.addTablet(baseTabletP2, tabletMetaBaseTabletP2);
        Replica replica6 = new Replica(TEST_REPLICA6_ID, BACKEND1_ID, ReplicaState.NORMAL);
        Replica replica7 = new Replica(TEST_REPLICA7_ID, BACKEND2_ID, ReplicaState.NORMAL);
        Replica replica8 = new Replica(TEST_REPLICA8_ID, BACKEND3_ID, ReplicaState.NORMAL);

        baseTabletP2.addReplica(replica6);
        baseTabletP2.addReplica(replica7);
        baseTabletP2.addReplica(replica8);


        olapTable2.setIndexSchemaInfo(TEST_TBL2_ID, TEST_TBL2_NAME, TEST_TBL_BASE_SCHEMA, 0, SCHEMA_HASH, (short) 1);
        olapTable2.setStorageTypeToIndex(TEST_TBL2_ID, TStorageType.COLUMN);
        olapTable2.addPartition(partition1);
        olapTable2.addPartition(partition2);

        // rollup index p1
        MaterializedIndex rollupIndexP1 = new MaterializedIndex(TEST_ROLLUP_ID, IndexState.NORMAL);
        Tablet rollupTabletP1 = new Tablet(TEST_ROLLUP_TABLET_P1_ID);
        TabletMeta tabletMetaRollupTabletP1 = new TabletMeta(TEST_DB_ID, TEST_TBL2_ID, TEST_PARTITION1_ID,
                                                             TEST_ROLLUP_TABLET_P1_ID, ROLLUP_SCHEMA_HASH);
        rollupIndexP1.addTablet(rollupTabletP1, tabletMetaRollupTabletP1);
        Replica replica9 = new Replica(TEST_REPLICA9_ID, BACKEND1_ID, ReplicaState.NORMAL);
        Replica replica10 = new Replica(TEST_REPLICA10_ID, BACKEND2_ID, ReplicaState.NORMAL);
        Replica replica11 = new Replica(TEST_REPLICA11_ID, BACKEND3_ID, ReplicaState.NORMAL);
        
        rollupTabletP1.addReplica(replica9);
        rollupTabletP1.addReplica(replica10);
        rollupTabletP1.addReplica(replica11);
        
        partition1.createRollupIndex(rollupIndexP1);

        // rollup index p2
        MaterializedIndex rollupIndexP2 = new MaterializedIndex(TEST_ROLLUP_ID, IndexState.NORMAL);
        Tablet rollupTabletP2 = new Tablet(TEST_ROLLUP_TABLET_P2_ID);
        TabletMeta tabletMetaRollupTabletP2 = new TabletMeta(TEST_DB_ID, TEST_TBL2_ID, TEST_PARTITION1_ID,
                                                             TEST_ROLLUP_TABLET_P2_ID, ROLLUP_SCHEMA_HASH);
        rollupIndexP2.addTablet(rollupTabletP2, tabletMetaRollupTabletP2);
        Replica replica12 = new Replica(TEST_REPLICA12_ID, BACKEND1_ID, ReplicaState.NORMAL);
        Replica replica13 = new Replica(TEST_REPLICA13_ID, BACKEND2_ID, ReplicaState.NORMAL);
        Replica replica14 = new Replica(TEST_REPLICA14_ID, BACKEND3_ID, ReplicaState.NORMAL);
        rollupTabletP2.addReplica(replica12);
        rollupTabletP2.addReplica(replica13);
        rollupTabletP2.addReplica(replica14);

        partition2.createRollupIndex(rollupIndexP2);

        olapTable2.setIndexSchemaInfo(TEST_ROLLUP_ID, TEST_ROLLUP_NAME, TEST_ROLLUP_SCHEMA, 0, ROLLUP_SCHEMA_HASH,
                                      (short) 1);
        olapTable2.setStorageTypeToIndex(TEST_ROLLUP_ID, TStorageType.COLUMN);
        db.createTable(olapTable2);

        return db;
    }

    public static Catalog fetchAdminCatalog() {
        try {
            Catalog catalog = EasyMock.createMock(Catalog.class);
            EasyMock.expect(catalog.getAuth()).andReturn(fetchAdminAccess()).anyTimes();

            Database db = mockDb();

            EasyMock.expect(catalog.getDb(TEST_DB_NAME)).andReturn(db).anyTimes();
            EasyMock.expect(catalog.getDb(WRONG_DB)).andReturn(null).anyTimes();
            EasyMock.expect(catalog.getDb(TEST_DB_ID)).andReturn(db).anyTimes();
            EasyMock.expect(catalog.getDb(EasyMock.isA(String.class))).andReturn(new Database()).anyTimes();
            EasyMock.expect(catalog.getDbNames()).andReturn(Lists.newArrayList(TEST_DB_NAME)).anyTimes();
            EasyMock.expect(catalog.getLoadInstance()).andReturn(new Load()).anyTimes();
            EasyMock.expect(catalog.getSchemaChangeHandler()).andReturn(new SchemaChangeHandler()).anyTimes();
            EasyMock.expect(catalog.getRollupHandler()).andReturn(new RollupHandler()).anyTimes();
            EasyMock.expect(catalog.getEditLog()).andReturn(EasyMock.createMock(EditLog.class)).anyTimes();
            catalog.changeDb(EasyMock.isA(ConnectContext.class), EasyMock.eq(WRONG_DB));
            EasyMock.expectLastCall().andThrow(new DdlException("failed.")).anyTimes();
            catalog.changeDb(EasyMock.isA(ConnectContext.class), EasyMock.isA(String.class));
            EasyMock.expectLastCall().anyTimes();
            EasyMock.replay(catalog);
            return catalog;
        } catch (DdlException | AnalysisException e) {
            return null;
        }
    }

    public static PaloAuth fetchBlockAccess() {
        PaloAuth auth = EasyMock.createMock(PaloAuth.class);
        EasyMock.expect(auth.checkGlobalPriv(EasyMock.isA(ConnectContext.class),
                                             EasyMock.isA(PrivPredicate.class))).andReturn(false).anyTimes();
        EasyMock.expect(auth.checkDbPriv(EasyMock.isA(ConnectContext.class), EasyMock.isA(String.class),
                                         EasyMock.isA(PrivPredicate.class))).andReturn(false).anyTimes();
        EasyMock.expect(auth.checkTblPriv(EasyMock.isA(ConnectContext.class), EasyMock.isA(String.class),
                                          EasyMock.isA(String.class),
                                          EasyMock.isA(PrivPredicate.class))).andReturn(false).anyTimes();
        EasyMock.replay(auth);
        return auth;
    }
}
