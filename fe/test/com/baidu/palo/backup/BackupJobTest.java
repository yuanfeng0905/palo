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

import com.baidu.palo.analysis.TableName;
import com.baidu.palo.analysis.TableRef;
import com.baidu.palo.backup.BackupJob.BackupJobState;
import com.baidu.palo.catalog.Catalog;
import com.baidu.palo.catalog.Database;
import com.baidu.palo.catalog.OlapTable;
import com.baidu.palo.common.Config;
import com.baidu.palo.common.FeMetaVersion;
import com.baidu.palo.common.util.UnitTestUtil;
import com.baidu.palo.persist.EditLog;
import com.baidu.palo.task.AgentBatchTask;
import com.baidu.palo.task.AgentTask;
import com.baidu.palo.task.AgentTaskExecutor;
import com.baidu.palo.task.AgentTaskQueue;
import com.baidu.palo.task.SnapshotTask;
import com.baidu.palo.task.UploadTask;
import com.baidu.palo.thrift.TBackend;
import com.baidu.palo.thrift.TFinishTaskRequest;
import com.baidu.palo.thrift.TStatus;
import com.baidu.palo.thrift.TStatusCode;
import com.baidu.palo.thrift.TTaskType;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import mockit.Expectations;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import mockit.internal.startup.Startup;

public class BackupJobTest {

    private BackupJob job;
    private Database db;

    private long dbId = 1;
    private long tblId = 2;
    private long partId = 3;
    private long idxId = 4;
    private long tabletId = 5;
    private long backendId = 10000;
    private long version = 6;
    private long versionHash = 7;

    private long repoId = 20000;
    private AtomicLong id = new AtomicLong(50000);

    @Mocked
    private Catalog catalog;
    @Mocked
    private BackupHandler backupHandler;
    @Mocked
    private RepositoryMgr repoMgr;
    @Mocked
    private EditLog editLog;

    private Repository repo = new Repository(repoId, "repo", false, "my_repo",
            new BlobStorage("broker", Maps.newHashMap()));

    static {
        Startup.initializeIfPossible();
    }

    @BeforeClass
    public static void start() {
        Config.tmp_dir = "./";
        File backupDir = new File(BackupHandler.BACKUP_ROOT_DIR.toString());
        backupDir.mkdirs();
    }
    
    @AfterClass
    public static void end() throws IOException {
        Config.tmp_dir = "./";
        File backupDir = new File(BackupHandler.BACKUP_ROOT_DIR.toString());
        if (backupDir.exists()) {
            Files.walk(BackupHandler.BACKUP_ROOT_DIR,
                       FileVisitOption.FOLLOW_LINKS).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }

    @Before
    public void setUp() {
        new MockUp<Catalog>() {
            @Mock
            public BackupHandler getBackupHandler() {
                return backupHandler;
            }

            @Mock
            public Database getDb(long dbId) {
                return db;
            }

            @Mock
            public int getCurrentCatalogJournalVersion() {
                return FeMetaVersion.VERSION_42;
            }

            @Mock
            public long getNextId() {
                return id.getAndIncrement();
            }

            @Mock
            public EditLog getEditLog() {
                return editLog;
            }
        };

        new MockUp<BackupHandler>() {
            @Mock
            public RepositoryMgr getRepoMgr() {
                return repoMgr;
            }
        };

        new MockUp<RepositoryMgr>() {
            @Mock
            public Repository getRepo(long repoId) {
                return repo;
            }
        };

        new MockUp<EditLog>() {
            @Mock
            public void logBackupJob(BackupJob job) {
                System.out.println("log backup job: " + job);
            }
        };

        new MockUp<AgentTaskExecutor>() {
            @Mock
            public void submit(AgentBatchTask task) {
                return;
            }
        };

        new Expectations(Repository.class) {
            {
                repo.upload(anyString, anyString);
                minTimes = 0;
                result = Status.OK;
            }
        };

        db = UnitTestUtil.createDb(dbId, tblId, partId, idxId, tabletId, backendId, version, versionHash);
        List<TableRef> tableRefs = Lists.newArrayList();
        tableRefs.add(new TableRef(new TableName(UnitTestUtil.DB_NAME, UnitTestUtil.TABLE_NAME), null));
        job = new BackupJob("label", dbId, UnitTestUtil.DB_NAME, tableRefs, 13600 * 1000, catalog, repo.getId());
    }

    @Test
    public void testRunNormal() {
        // 1.pending
        Assert.assertEquals(BackupJobState.PENDING, job.getState());
        job.run();
        Assert.assertEquals(Status.OK, job.getStatus());
        Assert.assertEquals(BackupJobState.SNAPSHOTING, job.getState());
        
        BackupMeta backupMeta = job.getBackupMeta();
        Assert.assertEquals(1, backupMeta.getTables().size());
        OlapTable backupTbl = (OlapTable) backupMeta.getTable(UnitTestUtil.TABLE_NAME);
        List<String> partNames = Lists.newArrayList(backupTbl.getPartitionNames());
        Assert.assertNotNull(backupTbl);
        Assert.assertEquals(backupTbl.getSignature(BackupHandler.SIGNATURE_VERSION, partNames),
                            ((OlapTable) db.getTable(tblId)).getSignature(BackupHandler.SIGNATURE_VERSION, partNames));
        Assert.assertEquals(1, AgentTaskQueue.getTaskNum());
        AgentTask task = AgentTaskQueue.getTask(backendId, TTaskType.MAKE_SNAPSHOT, tabletId);
        Assert.assertTrue(task instanceof SnapshotTask);
        SnapshotTask snapshotTask = (SnapshotTask) task;
        
        // 2. snapshoting
        job.run();
        Assert.assertEquals(Status.OK, job.getStatus());
        Assert.assertEquals(BackupJobState.SNAPSHOTING, job.getState());
        
        // 3. snapshot finished
        String snapshotPath = "/path/to/snapshot";
        List<String> snapshotFiles = Lists.newArrayList();
        snapshotFiles.add("1.dat");
        snapshotFiles.add("1.idx");
        snapshotFiles.add("1.hdr");
        TStatus task_status = new TStatus(TStatusCode.OK);
        TBackend tBackend = new TBackend("", 0, 1);
        TFinishTaskRequest request = new TFinishTaskRequest(tBackend, TTaskType.MAKE_SNAPSHOT,
                snapshotTask.getSignature(), task_status);
        request.setSnapshot_files(snapshotFiles);
        request.setSnapshot_path(snapshotPath);
        Assert.assertTrue(job.finishTabletSnapshotTask(snapshotTask, request));
        job.run();
        Assert.assertEquals(Status.OK, job.getStatus());
        Assert.assertEquals(BackupJobState.UPLOAD_SNAPSHOT, job.getState());

        // 4. upload snapshots
        AgentTaskQueue.clearAllTasks();
        job.run();
        Assert.assertEquals(Status.OK, job.getStatus());
        Assert.assertEquals(BackupJobState.UPLOADING, job.getState());
        Assert.assertEquals(1, AgentTaskQueue.getTaskNum());
        task = AgentTaskQueue.getTask(backendId, TTaskType.UPLOAD, id.get() - 1);
        Assert.assertTrue(task instanceof UploadTask);
        UploadTask upTask = (UploadTask) task;
        
        Assert.assertEquals(job.getJobId(), upTask.getJobId());
        Map<String, String> srcToDest = upTask.getSrcToDestPath();
        Assert.assertEquals(1, srcToDest.size());
        System.out.println(srcToDest);
        String dest = srcToDest.get(snapshotPath + "/" + tabletId + "/" + 0);
        Assert.assertNotNull(dest);

        // 5. uploading
        job.run();
        Assert.assertEquals(Status.OK, job.getStatus());
        Assert.assertEquals(BackupJobState.UPLOADING, job.getState());
        Map<Long, List<String>> tabletFileMap = Maps.newHashMap();
        request = new TFinishTaskRequest(tBackend, TTaskType.UPLOAD,
                upTask.getSignature(), task_status);
        request.setTablet_files(tabletFileMap);

        Assert.assertFalse(job.finishSnapshotUploadTask(upTask, request));
        List<String> tabletFiles = Lists.newArrayList();
        tabletFileMap.put(tabletId, tabletFiles);
        Assert.assertFalse(job.finishSnapshotUploadTask(upTask, request));
        tabletFiles.add("1.dat.4f158689243a3d6030352fec3cfd3798");
        tabletFiles.add("wrong_files.idx.4f158689243a3d6030352fec3cfd3798");
        tabletFiles.add("wrong_files.hdr.4f158689243a3d6030352fec3cfd3798");
        Assert.assertFalse(job.finishSnapshotUploadTask(upTask, request));
        tabletFiles.clear();
        tabletFiles.add("1.dat.4f158689243a3d6030352fec3cfd3798");
        tabletFiles.add("1.idx.4f158689243a3d6030352fec3cfd3798");
        tabletFiles.add("1.hdr.4f158689243a3d6030352fec3cfd3798");
        Assert.assertTrue(job.finishSnapshotUploadTask(upTask, request));
        job.run();
        Assert.assertEquals(Status.OK, job.getStatus());
        Assert.assertEquals(BackupJobState.SAVE_META, job.getState());

        // 6. save meta
        job.run();
        Assert.assertEquals(Status.OK, job.getStatus());
        Assert.assertEquals(BackupJobState.UPLOAD_INFO, job.getState());
        File metaInfo = new File(job.getLocalMetaInfoFilePath());
        Assert.assertTrue(metaInfo.exists());
        File jobInfo = new File(job.getLocalJobInfoFilePath());
        Assert.assertTrue(jobInfo.exists());

        BackupMeta restoreMetaInfo = null;
        BackupJobInfo restoreJobInfo = null;
        try {
            restoreMetaInfo = BackupMeta.fromFile(job.getLocalMetaInfoFilePath());
            Assert.assertEquals(1, restoreMetaInfo.getTables().size());
            OlapTable olapTable = (OlapTable) restoreMetaInfo.getTable(tblId);
            Assert.assertNotNull(olapTable);
            Assert.assertNotNull(restoreMetaInfo.getTable(UnitTestUtil.TABLE_NAME));
            List<String> names = Lists.newArrayList(olapTable.getPartitionNames());
            Assert.assertEquals(((OlapTable) db.getTable(tblId)).getSignature(BackupHandler.SIGNATURE_VERSION, names),
                                olapTable.getSignature(BackupHandler.SIGNATURE_VERSION, names));

            restoreJobInfo = BackupJobInfo.fromFile(job.getLocalJobInfoFilePath());
            Assert.assertEquals(UnitTestUtil.DB_NAME, restoreJobInfo.dbName);
            Assert.assertEquals(job.getLabel(), restoreJobInfo.name);
            Assert.assertEquals(1, restoreJobInfo.tables.size());
        } catch (IOException e) {
            e.printStackTrace();
            Assert.fail();
        }

        Assert.assertNull(job.getBackupMeta());
        Assert.assertNull(job.getJobInfo());

        // 7. upload_info
        job.run();
        Assert.assertEquals(Status.OK, job.getStatus());
        Assert.assertEquals(BackupJobState.FINISHED, job.getState());
    }

    @Test
    public void testRunAbnormal() {
        // 1.pending
        AgentTaskQueue.clearAllTasks();

        List<TableRef> tableRefs = Lists.newArrayList();
        tableRefs.add(new TableRef(new TableName(UnitTestUtil.DB_NAME, "unknown_tbl"), null));
        job = new BackupJob("label", dbId, UnitTestUtil.DB_NAME, tableRefs, 13600 * 1000, catalog, repo.getId());
        job.run();
        Assert.assertEquals(Status.ErrCode.NOT_FOUND, job.getStatus().getErrCode());
        Assert.assertEquals(BackupJobState.CANCELLED, job.getState());
    }
}
