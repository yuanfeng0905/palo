// Copyright (c) 2017, Baidu.com, Inc. All Rights Reserved

// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.baidu.palo.master;

import com.baidu.palo.catalog.Catalog;
import com.baidu.palo.catalog.Database;
import com.baidu.palo.catalog.MaterializedIndex;
import com.baidu.palo.catalog.OlapTable;
import com.baidu.palo.catalog.Partition;
import com.baidu.palo.catalog.Replica;
import com.baidu.palo.catalog.Replica.ReplicaState;
import com.baidu.palo.catalog.Tablet;
import com.baidu.palo.catalog.TabletInvertedIndex;
import com.baidu.palo.clone.CloneChecker;
import com.baidu.palo.common.MetaNotFoundException;
import com.baidu.palo.common.util.Daemon;
import com.baidu.palo.persist.ReplicaPersistInfo;
import com.baidu.palo.system.Backend;
import com.baidu.palo.task.AgentBatchTask;
import com.baidu.palo.task.AgentTask;
import com.baidu.palo.task.AgentTaskExecutor;
import com.baidu.palo.task.AgentTaskQueue;
import com.baidu.palo.task.DropReplicaTask;
import com.baidu.palo.task.MasterTask;
import com.baidu.palo.task.PushTask;
import com.baidu.palo.task.StorageMediaMigrationTask;
import com.baidu.palo.thrift.TBackend;
import com.baidu.palo.thrift.TDisk;
import com.baidu.palo.thrift.TMasterResult;
import com.baidu.palo.thrift.TPushType;
import com.baidu.palo.thrift.TReportRequest;
import com.baidu.palo.thrift.TStatus;
import com.baidu.palo.thrift.TStatusCode;
import com.baidu.palo.thrift.TStorageMedium;
import com.baidu.palo.thrift.TTablet;
import com.baidu.palo.thrift.TTabletInfo;
import com.baidu.palo.thrift.TTaskType;

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Queues;

import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.thrift.TException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;

public class ReportHandler extends Daemon {
    private static final Logger LOG = LogManager.getLogger(ReportHandler.class);

    private BlockingQueue<ReportTask> reportQueue = Queues.newLinkedBlockingQueue();

    public ReportHandler() {
    }

    public TMasterResult handleReport(TReportRequest request) throws TException {
        TMasterResult result = new TMasterResult();
        TStatus tStatus = new TStatus(TStatusCode.OK);
        result.setStatus(tStatus);

        // get backend
        TBackend tBackend = request.getBackend();
        String host = tBackend.getHost();
        int bePort = tBackend.getBe_port();
        Backend backend = Catalog.getCurrentSystemInfo().getBackendWithBePort(host, bePort);
        if (backend == null) {
            tStatus.setStatus_code(TStatusCode.INTERNAL_ERROR);
            List<String> errorMsgs = Lists.newArrayList();
            errorMsgs.add("backend[" + host + ":" + bePort + "] does not exist.");
            tStatus.setError_msgs(errorMsgs);
            return result;
        }

        long beId = backend.getId();
        Map<TTaskType, Set<Long>> tasks = null;
        Map<String, TDisk> disks = null;
        Map<Long, TTablet> tablets = null;
        long reportVersion = -1;

        if (request.isSetTasks()) {
            tasks = request.getTasks();
        }
        if (request.isSetDisks()) {
            disks = request.getDisks();
        }
        if (request.isSetTablets()) {
            tablets = request.getTablets();
            reportVersion = request.getReport_version();
        }

        ReportTask reportTask = new ReportTask(beId, tasks, disks, tablets, reportVersion);

        try {
            reportQueue.put(reportTask);
        } catch (InterruptedException e) {
            tStatus.setStatus_code(TStatusCode.INTERNAL_ERROR);
            List<String> errorMsgs = Lists.newArrayList();
            errorMsgs.add("failed to put report task to queue. queue size: " + reportQueue.size());
            errorMsgs.add("err: " + e.getMessage());
            tStatus.setError_msgs(errorMsgs);
            return result;
        }
        LOG.info("receive report from be {}. current queue size: {}", backend.getId(), reportQueue.size());
        return result;
    }

    private class ReportTask extends MasterTask {
        private long beId;
        private Map<TTaskType, Set<Long>> tasks;
        private Map<String, TDisk> disks;
        private Map<Long, TTablet> tablets;
        private long reportVersion;

        public ReportTask(long beId, Map<TTaskType, Set<Long>> tasks,
                Map<String, TDisk> disks,
                Map<Long, TTablet> tablets, long reportVersion) {
            this.beId = beId;
            this.tasks = tasks;
            this.disks = disks;
            this.tablets = tablets;
            this.reportVersion = reportVersion;
        }

        @Override
        protected void exec() {
            if (tasks != null) {
                ReportHandler.taskReport(beId, tasks);
            }

            if (disks != null) {
                ReportHandler.diskReport(beId, disks);
            }

            if (tablets != null) {
                long backendReportVersion = Catalog.getCurrentSystemInfo().getBackendReportVersion(beId);
                if (reportVersion < backendReportVersion) {
                    LOG.warn("out of date report version {} from backend[{}]. current report version[{}]",
                             reportVersion, beId, backendReportVersion);
                } else {
                    ReportHandler.tabletReport(beId, tablets, reportVersion);
                }
            }
        }
    }

    private static void taskReport(long backendId, Map<TTaskType, Set<Long>> runningTasks) {
        LOG.info("begin to handle task report from backend {}", backendId);
        long start = System.currentTimeMillis();

        for (TTaskType type : runningTasks.keySet()) {
            Set<Long> taskSet = runningTasks.get(type);
            if (!taskSet.isEmpty()) {
                String signatures = StringUtils.join(taskSet, ", ");
                LOG.debug("backend task[{}]: {}", type.name(), signatures);
            }
        }

        List<AgentTask> diffTasks = AgentTaskQueue.getDiffTasks(backendId, runningTasks);

        AgentBatchTask batchTask = new AgentBatchTask();
        for (AgentTask task : diffTasks) {
            // these tasks donot need to do diff
            // 1. CREATE
            // 2. SYNC DELETE
            // 3. CHECK_CONSISTENCY
            if (task.getTaskType() == TTaskType.CREATE
                    || (task.getTaskType() == TTaskType.PUSH && ((PushTask) task).getPushType() == TPushType.DELETE
                    && ((PushTask) task).isSyncDelete())
                    || task.getTaskType() == TTaskType.CHECK_CONSISTENCY) {
                continue;
            }
            batchTask.addTask(task);
        }

        LOG.debug("get {} diff task(s) to resend", batchTask.getTaskNum());
        if (batchTask.getTaskNum() > 0) {
            AgentTaskExecutor.submit(batchTask);
        }

        LOG.info("finished to handle task report from backend {}, diff task num: {}. cost: {} ms",
                 backendId, batchTask.getTaskNum(), (System.currentTimeMillis() - start));
    }

    private static void diskReport(long backendId, Map<String, TDisk> backendDisks) {
        LOG.info("begin to handle disk report from backend {}", backendId);
        long start = System.currentTimeMillis();

        Backend backend = Catalog.getCurrentSystemInfo().getBackend(backendId);
        if (backend == null) {
            LOG.warn("backend doesn't exist. id: " + backendId);
            return;
        }
        
        backend.updateDisks(backendDisks);
        LOG.info("finished to handle disk report from backend {}, cost: {} ms",
                 backendId, (System.currentTimeMillis() - start));
    }

    private static void tabletReport(long backendId, Map<Long, TTablet> backendTablets, long backendReportVersion) {
        LOG.info("begin to handle tablet report from backend {}, tablet num: {}, report version: {}",
                 backendId, backendTablets.size(), backendReportVersion);
        long start = System.currentTimeMillis();

        // storage medium map
        HashMap<Long, TStorageMedium> storageMediumMap = Catalog.getInstance().getPartitionIdToStorageMediumMap();

        // db id -> tablet id
        ListMultimap<Long, Long> tabletSyncMap = LinkedListMultimap.create();
        // db id -> tablet id
        ListMultimap<Long, Long> tabletDeleteFromMeta = LinkedListMultimap.create();
        // tablet ids which schema hash is valid
        Set<Long> foundTabletsWithValidSchema = new HashSet<Long>();
        // tablet ids which schema hash is invalid
        Map<Long, TTabletInfo> foundTabletsWithInvalidSchema = new HashMap<Long, TTabletInfo>();
        // storage medium -> tablet id
        ListMultimap<TStorageMedium, Long> tabletMigrationMap = LinkedListMultimap.create();

        // 1. do the diff. find out (intersection) / (be - meta) / (meta - be)
        Catalog.getCurrentInvertedIndex().tabletReport(backendId, backendTablets, storageMediumMap,
                                                       tabletSyncMap,
                                                       tabletDeleteFromMeta,
                                                       foundTabletsWithValidSchema,
                                                       foundTabletsWithInvalidSchema,
                                                       tabletMigrationMap);

        // 2. sync
        sync(backendTablets, tabletSyncMap, backendId, backendReportVersion);

        // 3. delete (meta - be)
        // BE will automatically drop defective tablets. these tablets should also be dropped in catalog
        deleteFromMeta(tabletDeleteFromMeta, backendId, backendReportVersion);

        // 4. handle (be - meta)
        deleteFromBackend(backendTablets, foundTabletsWithValidSchema, foundTabletsWithInvalidSchema, backendId);

        // 5. migration (ssd <-> hdd)
        handleMigration(tabletMigrationMap, backendId);

        LOG.info("finished to handle tablet report from backend {}, cost: {} ms",
                 backendId, (System.currentTimeMillis() - start));
    }

    private static void sync(Map<Long, TTablet> backendTablets, ListMultimap<Long, Long> tabletSyncMap,
                             long backendId, long backendReportVersion) {
        TabletInvertedIndex invertedIndex = Catalog.getCurrentInvertedIndex();
        for (Long dbId : tabletSyncMap.keySet()) {
            Database db = Catalog.getInstance().getDb(dbId);
            if (db == null) {
                continue;
            }
            db.writeLock();
            try {
                int syncCounter = 0;
                List<Long> tabletIds = tabletSyncMap.get(dbId);
                LOG.info("before sync tablets in db[{}]. report num: {}. backend[{}]",
                         dbId, tabletIds.size(), backendId);
                for (Long tabletId : tabletIds) {
                    long tableId = invertedIndex.getTableId(tabletId);
                    OlapTable olapTable = (OlapTable) db.getTable(tableId);
                    if (olapTable == null) {
                        continue;
                    }

                    long partitionId = invertedIndex.getPartitionId(tabletId);
                    Partition partition = olapTable.getPartition(partitionId);
                    if (partition == null) {
                        continue;
                    }

                    long indexId = invertedIndex.getIndexId(tabletId);
                    MaterializedIndex index = partition.getIndex(indexId);
                    if (index == null) {
                        continue;
                    }
                    long schemaHash = olapTable.getSchemaHashByIndexId(indexId);

                    Tablet tablet = index.getTablet(tabletId);
                    if (tablet == null) {
                        continue;
                    }

                    Replica replica = tablet.getReplicaByBackendId(backendId);
                    if (replica == null) {
                        continue;
                    }

                    if (replica.getState() == ReplicaState.NORMAL) {
                        long metaVersion = replica.getVersion();
                        long metaVersionHash = replica.getVersionHash();
                        long backendVersion = -1L;
                        long backendVersionHash = -1L;
                        long rowCount = -1L;
                        long dataSize = -1L;

                        for (TTabletInfo tabletInfo : backendTablets.get(tabletId).getTablet_infos()) {
                            if (tabletInfo.getSchema_hash() == schemaHash) {
                                backendVersion = tabletInfo.getVersion();
                                backendVersionHash = tabletInfo.getVersion_hash();
                                rowCount = tabletInfo.getRow_count();
                                dataSize = tabletInfo.getData_size();
                                break;
                            }
                        }
                        if (backendVersion == -1L || backendVersionHash == -1L) {
                            continue;
                        }

                        if (metaVersion < backendVersion
                                || (metaVersion == backendVersion && metaVersionHash != backendVersionHash)) {

                            if (backendReportVersion < Catalog.getCurrentSystemInfo()
                                    .getBackendReportVersion(backendId)) {
                                continue;
                            }

                            // happens when PUSH finished in BE but failed or not yet report to FE
                            replica.updateInfo(backendVersion, backendVersionHash, dataSize, rowCount);
                            
                            ++syncCounter;
                            LOG.debug("sync replica[{}] in db[{}].", replica.getId(), dbId);
                        } else {
                            LOG.debug("replica[{}] version is changed between check and real sync."
                                    + " meta[{}-{}]. backend[{}-{}]",
                                      replica.getId(), metaVersion, metaVersionHash,
                                      backendVersion, backendVersionHash);
                        }
                    }
                } // end for tabletMetaSyncMap
                LOG.info("sync {} tablets in db[{}]. backend[{}]", syncCounter, dbId, backendId);
            } finally {
                db.writeUnlock();
            }
        } // end for dbs
    }

    private static void deleteFromMeta(ListMultimap<Long, Long> tabletDeleteFromMeta, long backendId,
                                       long backendReportVersion) {
        TabletInvertedIndex invertedIndex = Catalog.getCurrentInvertedIndex();
        for (Long dbId : tabletDeleteFromMeta.keySet()) {
            Database db = Catalog.getInstance().getDb(dbId);
            if (db == null) {
                continue;
            }
            db.writeLock();
            try {
                int deleteCounter = 0;
                List<Long> tabletIds = tabletDeleteFromMeta.get(dbId);
                for (Long tabletId : tabletIds) {
                    long tableId = invertedIndex.getTableId(tabletId);
                    OlapTable olapTable = (OlapTable) db.getTable(tableId);
                    if (olapTable == null) {
                        continue;
                    }

                    long partitionId = invertedIndex.getPartitionId(tabletId);
                    Partition partition = olapTable.getPartition(partitionId);
                    if (partition == null) {
                        continue;
                    }

                    short replicationNum = olapTable.getPartitionInfo().getReplicationNum(partition.getId());

                    long indexId = invertedIndex.getIndexId(tabletId);
                    MaterializedIndex index = partition.getIndex(indexId);
                    if (index == null) {
                        continue;
                    }

                    Tablet tablet = index.getTablet(tabletId);
                    if (tablet == null) {
                        continue;
                    }

                    Replica replica = tablet.getReplicaByBackendId(backendId);
                    if (replica == null) {
                        continue;
                    }
                    
                    // check report version again
                    if (backendReportVersion < Catalog.getCurrentSystemInfo().getBackendReportVersion(backendId)) {
                        continue;
                    }

                    ReplicaState state = replica.getState();
                    if (state == ReplicaState.NORMAL || state == ReplicaState.SCHEMA_CHANGE) {
                        // if state is PENDING / ROLLUP / CLONE
                        // it's normal that the replica is not created in BE but exists in meta.
                        // so we do not delete it.
                        List<Replica> replicas = tablet.getReplicas();
                        if (replicas.size() <= 1) {
                            LOG.error("invalid situation. tablet[{}] has few replica[{}]", tabletId, replicas.size());
                            continue;
                        }

                        tablet.deleteReplicaByBackendId(backendId);
                        ++deleteCounter;
                        
                        // handle related task
                        Catalog.getInstance().handleJobsWhenDeleteReplica(tableId, partitionId, indexId, tabletId,
                                                                          replica.getId(), backendId);

                        // write edit log
                        ReplicaPersistInfo info = ReplicaPersistInfo.createForDelete(dbId, tableId, partitionId,
                                                                                     indexId, tabletId, backendId);

                        Catalog.getInstance().getEditLog().logDeleteReplica(info);
                        LOG.warn("delete replica[{}] in tablet[{}] from meta. backend[{}]",
                                 replica.getId(), tabletId, backendId);
                        
                        // check for clone
                        replicas = tablet.getReplicas();
                        if (replicas.size() == 0) {
                            LOG.error("invalid situation. tablet[{}] is empty", tabletId);
                        } else if (replicas.size() < replicationNum) {
                            CloneChecker.getInstance().checkTabletForSupplement(dbId, tableId,
                                                                                partitionId,
                                                                                indexId, tabletId);
                        }
                    }
                } // end for tabletMetas
                LOG.info("delete {} replica(s) from catalog in db[{}]", deleteCounter, dbId);
            } finally {
                db.writeUnlock();
            }
        } // end for dbs
    }

    private static void deleteFromBackend(Map<Long, TTablet> backendTablets,
                                          Set<Long> foundTabletsWithValidSchema,
                                          Map<Long, TTabletInfo> foundTabletsWithInvalidSchema,
                                          long backendId) {
        int deleteFromBackendCounter = 0;
        int addToMetaCounter = 0;
        AgentBatchTask batchTask = new AgentBatchTask();
        for (Long tabletId : backendTablets.keySet()) {
            TTablet backendTablet = backendTablets.get(tabletId);

            for (TTabletInfo backendTabletInfo : backendTablet.getTablet_infos()) {
                boolean needDelete = false;
                if (!foundTabletsWithValidSchema.contains(tabletId)) {
                    // if this tablet is not in meta. try adding it.
                    // if add failed. delete this tablet from backend.
                    try {
                        addReplica(tabletId, backendTabletInfo, backendId);
                        // update counter
                        needDelete = false;
                        ++addToMetaCounter;
                    } catch (MetaNotFoundException e) {
                        LOG.warn("failed add to meta. tablet[{}], backend[{}]. {}",
                                 tabletId, backendId, e.getMessage());
                        needDelete = true;
                    }
                }

                if (needDelete) {
                    // drop replica
                    DropReplicaTask task = new DropReplicaTask(backendId, tabletId, backendTabletInfo.getSchema_hash());
                    batchTask.addTask(task);
                    LOG.warn("delete tablet[" + tabletId + " - " + backendTabletInfo.getSchema_hash()
                            + "] from backend[" + backendId + "] because not found in meta");
                    ++deleteFromBackendCounter;
                }
            } // end for tabletInfos

            if (foundTabletsWithInvalidSchema.containsKey(tabletId)) {
                // this tablet is found in meta but with invalid schema hash.
                // delete it.
                int schemaHash = foundTabletsWithInvalidSchema.get(tabletId).getSchema_hash();
                DropReplicaTask task = new DropReplicaTask(backendId, tabletId, schemaHash);
                batchTask.addTask(task);
                LOG.warn("delete tablet[" + tabletId + " - " + schemaHash + "] from backend[" + backendId
                        + "] because invalid schema hash");
                ++deleteFromBackendCounter;
            }
        } // end for backendTabletIds
        AgentTaskExecutor.submit(batchTask);
        
        LOG.info("delete {} tablet(s) from backend[{}]", deleteFromBackendCounter, backendId);
        LOG.info("add {} replica(s) to meta. backend[{}]", addToMetaCounter, backendId);
    }

    private static void handleMigration(ListMultimap<TStorageMedium, Long> tabletMetaMigrationMap,
                                        long backendId) {
        TabletInvertedIndex invertedIndex = Catalog.getCurrentInvertedIndex();
        AgentBatchTask batchTask = new AgentBatchTask();
        for (TStorageMedium storageMedium : tabletMetaMigrationMap.keySet()) {
            List<Long> tabletIds = tabletMetaMigrationMap.get(storageMedium);
            for (Long tabletId : tabletIds) {
                StorageMediaMigrationTask task = new StorageMediaMigrationTask(backendId, tabletId,
                                                      invertedIndex.getEffectiveSchemaHash(tabletId),
                                                      storageMedium);
                batchTask.addTask(task);
            }
        }

        AgentTaskExecutor.submit(batchTask);
    }

    private static void addReplica(long tabletId, TTabletInfo backendTabletInfo, long backendId)
            throws MetaNotFoundException {
        TabletInvertedIndex invertedIndex = Catalog.getCurrentInvertedIndex();
        long dbId = invertedIndex.getDbId(tabletId);
        long tableId = invertedIndex.getTableId(tabletId);
        long partitionId = invertedIndex.getPartitionId(tabletId);
        long indexId = invertedIndex.getIndexId(tabletId);
        
        int schemaHash = backendTabletInfo.getSchema_hash();
        long version = backendTabletInfo.getVersion();
        long versionHash = backendTabletInfo.getVersion_hash();
        long dataSize = backendTabletInfo.getData_size();
        long rowCount = backendTabletInfo.getRow_count();

        Database db = Catalog.getInstance().getDb(dbId);
        if (db == null) {
            throw new MetaNotFoundException("db[" + dbId + "] does not exist");
        }
        db.writeLock();
        try {
            OlapTable olapTable = (OlapTable) db.getTable(tableId);
            if (olapTable == null) {
                throw new MetaNotFoundException("table[" + tableId + "] does not exist");
            }

            Partition partition = olapTable.getPartition(partitionId);
            if (partition == null) {
                throw new MetaNotFoundException("partition[" + partitionId + "] does not exist");
            }
            short replicationNum = olapTable.getPartitionInfo().getReplicationNum(partition.getId());

            MaterializedIndex materializedIndex = partition.getIndex(indexId);
            if (materializedIndex == null) {
                throw new MetaNotFoundException("index[" + indexId + "] does not exist");
            }
            Tablet tablet = materializedIndex.getTablet(tabletId);
            if (tablet == null) {
                throw new MetaNotFoundException("tablet[" + tabletId + "] does not exist");
            }

            long committedVersion = partition.getCommittedVersion();
            long committedVersionHash = partition.getCommittedVersionHash();

            // check replica version
            if (version < committedVersion || (version == committedVersion && versionHash != committedVersionHash)) {
                throw new MetaNotFoundException("version is invalid. tablet[" + version + "-" + versionHash + "]"
                        + ", committed[" + committedVersion + "-" + committedVersionHash + "]");
            }
            
            // check schema hash
            if (schemaHash != olapTable.getSchemaHashByIndexId(indexId)) {
                throw new MetaNotFoundException("schema hash is diff[" + schemaHash + "-"
                        + olapTable.getSchemaHashByIndexId(indexId) + "]");
            }

            List<Replica> replicas = tablet.getReplicas();
            int replicationOnLine = 0;
            for (Replica replica : replicas) {
                final long id = replica.getBackendId();
                final Backend backend = Catalog.getCurrentSystemInfo().getBackend(id);
                if (backend != null && backend.isAlive() && !backend.isDecommissioned()
                        && replica.getState() == ReplicaState.NORMAL) {
                    replicationOnLine++;
                }
            }
            
            if (replicationOnLine < replicationNum) {
                long replicaId = Catalog.getInstance().getNextId();
                Replica replica = new Replica(replicaId, backendId, version, versionHash, 
                                              dataSize, rowCount, ReplicaState.NORMAL);
                tablet.addReplica(replica);
                
                // write edit log
                ReplicaPersistInfo info = ReplicaPersistInfo.createForAdd(dbId, tableId, partitionId, indexId,
                                                                          tabletId, backendId, replicaId,
                                                                          version, versionHash, dataSize, rowCount);

                Catalog.getInstance().getEditLog().logAddReplica(info);

                LOG.info("add replica[{}-{}] to catalog. backend[{}]", tabletId, replicaId, backendId);
            } else {
                // replica is enough. check if this tablet is already in meta
                // (status changed between 'tabletReport()' and 'addReplica()')
                for (Replica replica : replicas) {
                    if (replica.getBackendId() == backendId) {
                        // tablet is already in meta. return true
                        return;
                    }
                }

                throw new MetaNotFoundException("replica is enough[" + replicas.size() + "-" + replicationNum + "]");
            }
        } finally {
            db.writeUnlock();
        }
    }

    @Override
    protected void runOneCycle() {
        while (true) {
            ReportTask task = null;
            try {
                task = reportQueue.take();
                task.exec();
            } catch (InterruptedException e) {
                LOG.warn("got interupted exception when executing report", e);
                continue;
            }
        }
    }
}
