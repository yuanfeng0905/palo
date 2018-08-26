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

package com.baidu.palo.task;

import com.baidu.palo.catalog.Catalog;
import com.baidu.palo.catalog.Database;
import com.baidu.palo.catalog.OlapTable;
import com.baidu.palo.catalog.Partition;
import com.baidu.palo.common.Config;
import com.baidu.palo.common.util.UnitTestUtil;
import com.baidu.palo.load.DppConfig;
import com.baidu.palo.load.DppScheduler;
import com.baidu.palo.load.EtlSubmitResult;
import com.baidu.palo.load.Load;
import com.baidu.palo.load.LoadJob;
import com.baidu.palo.load.LoadJob.JobState;
import com.baidu.palo.load.PartitionLoadInfo;
import com.baidu.palo.load.Source;
import com.baidu.palo.load.TableLoadInfo;
import com.baidu.palo.persist.EditLog;
import com.baidu.palo.thrift.TStatus;
import com.baidu.palo.thrift.TStatusCode;

import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.easymock.PowerMock;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(PowerMockRunner.class)
@PrepareForTest({ HadoopLoadPendingTask.class, Catalog.class })
public class LoadPendingTaskTest {
    private long dbId;
    private long tableId;
    private long partitionId;
    private long indexId;
    private long tabletId;
    private long backendId;

    private String label;
    
    private Catalog catalog;
    private Load load;
    private Database db;

    @Before
    public void setUp() {
        dbId = 0L;
        tableId = 0L;
        partitionId = 0L;
        indexId = 0L;
        tabletId = 0L;
        backendId = 0L;
        
        label = "test_label";
        UnitTestUtil.initDppConfig();
    }
    
    @Test
    public void testRunPendingTask() throws Exception {
        // mock catalog
        db = UnitTestUtil.createDb(dbId, tableId, partitionId, indexId, tabletId, backendId, 1L, 0L);
        catalog = EasyMock.createNiceMock(Catalog.class);
        EasyMock.expect(catalog.getDb(dbId)).andReturn(db).anyTimes();
        EasyMock.expect(catalog.getDb(db.getFullName())).andReturn(db).anyTimes();
        // mock editLog
        EditLog editLog = EasyMock.createMock(EditLog.class);
        EasyMock.expect(catalog.getEditLog()).andReturn(editLog).anyTimes();
        // mock static getInstance
        PowerMock.mockStatic(Catalog.class);
        EasyMock.expect(Catalog.getInstance()).andReturn(catalog).anyTimes();
        PowerMock.replay(Catalog.class);
        
        // create job
        LoadJob job = new LoadJob(label);
        job.setState(JobState.PENDING);
        job.setDbId(dbId);
        String cluster = Config.dpp_default_cluster;
        job.setClusterInfo(cluster, Load.clusterToDppConfig.get(cluster));
        // set partition load infos
        OlapTable table = (OlapTable) db.getTable(tableId);
        Partition partition = table.getPartition(partitionId);
        Source source = new Source(new ArrayList<String>());
        List<Source> sources = new ArrayList<Source>();
        sources.add(source);
        PartitionLoadInfo partitionLoadInfo = new PartitionLoadInfo(sources);
        Map<Long, PartitionLoadInfo> idToPartitionLoadInfo = new HashMap<Long, PartitionLoadInfo>();
        idToPartitionLoadInfo.put(partitionId, partitionLoadInfo);
        TableLoadInfo tableLoadInfo = new TableLoadInfo(idToPartitionLoadInfo);
        tableLoadInfo.addIndexSchemaHash(partition.getBaseIndex().getId(), 0);
        Map<Long, TableLoadInfo> idToTableLoadInfo = new HashMap<Long, TableLoadInfo>();
        idToTableLoadInfo.put(tableId, tableLoadInfo);
        job.setIdToTableLoadInfo(idToTableLoadInfo);

        // mock load
        load = EasyMock.createMock(Load.class);
        EasyMock.expect(load.updateLoadJobState(job, JobState.ETL)).andReturn(true).times(1);
        EasyMock.expect(load.getLoadErrorHubInfo()).andReturn(null).times(1);
        EasyMock.replay(load);
        EasyMock.expect(catalog.getLoadInstance()).andReturn(load).times(1);
        EasyMock.replay(catalog);
        
        // mock dppscheduler
        DppScheduler dppScheduler = EasyMock.createMock(DppScheduler.class);
        EasyMock.expect(dppScheduler.submitEtlJob(EasyMock.anyLong(), EasyMock.anyString(), EasyMock.anyString(),
                                                  EasyMock.anyString(), EasyMock.isA(Map.class), EasyMock.anyInt()))
                .andReturn(new EtlSubmitResult(new TStatus(TStatusCode.OK), "job_123456")).times(1);
        EasyMock.replay(dppScheduler);
        PowerMock.expectNew(DppScheduler.class, EasyMock.anyObject(DppConfig.class)).andReturn(dppScheduler).times(1);
        PowerMock.replay(DppScheduler.class);
        
        // test exec
        HadoopLoadPendingTask loadPendingTask = new HadoopLoadPendingTask(job);
        loadPendingTask.exec();
        
        // verify
        Assert.assertEquals(job.getId(), loadPendingTask.getSignature());
        EasyMock.verify(dppScheduler);
        EasyMock.verify(load);
        EasyMock.verify(catalog);
        PowerMock.verify(DppScheduler.class);
    }
}
