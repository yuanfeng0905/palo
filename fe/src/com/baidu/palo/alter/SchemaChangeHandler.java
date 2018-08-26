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

package com.baidu.palo.alter;

import com.baidu.palo.alter.AlterJob.JobState;
import com.baidu.palo.analysis.AddColumnClause;
import com.baidu.palo.analysis.AddColumnsClause;
import com.baidu.palo.analysis.AlterClause;
import com.baidu.palo.analysis.CancelAlterTableStmt;
import com.baidu.palo.analysis.CancelStmt;
import com.baidu.palo.analysis.ColumnPosition;
import com.baidu.palo.analysis.DropColumnClause;
import com.baidu.palo.analysis.ModifyColumnClause;
import com.baidu.palo.analysis.ModifyTablePropertiesClause;
import com.baidu.palo.analysis.ReorderColumnsClause;
import com.baidu.palo.catalog.AggregateType;
import com.baidu.palo.catalog.Catalog;
import com.baidu.palo.catalog.Column;
import com.baidu.palo.catalog.Database;
import com.baidu.palo.catalog.DistributionInfo;
import com.baidu.palo.catalog.DistributionInfo.DistributionInfoType;
import com.baidu.palo.catalog.HashDistributionInfo;
import com.baidu.palo.catalog.KeysType;
import com.baidu.palo.catalog.MaterializedIndex;
import com.baidu.palo.catalog.MaterializedIndex.IndexState;
import com.baidu.palo.catalog.OlapTable;
import com.baidu.palo.catalog.OlapTable.OlapTableState;
import com.baidu.palo.catalog.Partition;
import com.baidu.palo.catalog.Partition.PartitionState;
import com.baidu.palo.catalog.PartitionInfo;
import com.baidu.palo.catalog.PartitionType;
import com.baidu.palo.catalog.RangePartitionInfo;
import com.baidu.palo.catalog.Replica;
import com.baidu.palo.catalog.Replica.ReplicaState;
import com.baidu.palo.catalog.Tablet;
import com.baidu.palo.common.AnalysisException;
import com.baidu.palo.common.Config;
import com.baidu.palo.common.DdlException;
import com.baidu.palo.common.FeConstants;
import com.baidu.palo.common.util.ListComparator;
import com.baidu.palo.common.util.PropertyAnalyzer;
import com.baidu.palo.common.util.TimeUtils;
import com.baidu.palo.common.util.Util;
import com.baidu.palo.mysql.privilege.PrivPredicate;
import com.baidu.palo.qe.ConnectContext;
import com.baidu.palo.thrift.TResourceInfo;
import com.baidu.palo.thrift.TStorageType;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SchemaChangeHandler extends AlterHandler {
    private static final Logger LOG = LogManager.getLogger(SchemaChangeHandler.class);

    // delay delete SchemaChangeJob list
    private List<SchemaChangeJob> delayDeleteSchemaChangeJobs;

    public SchemaChangeHandler() {
        super("schema change");
        delayDeleteSchemaChangeJobs = new LinkedList<SchemaChangeJob>();
    }

    private void processAddColumn(AddColumnClause alterClause, OlapTable olapTable,
                                  Map<Long, LinkedList<Column>> indexSchemaMap) throws DdlException {
        Column column = alterClause.getCol();
        ColumnPosition columnPos = alterClause.getColPos();
        String targetIndexName = alterClause.getRollupName();
        checkIndexExists(olapTable, targetIndexName);

        if (column.isKey()) {
            checkKeyModificationIfInRandomDistributedTable(olapTable);
        }

        String baseIndexName = olapTable.getName();
        checkAssignedTargetIndexName(baseIndexName, targetIndexName);

        long baseIndexId = olapTable.getId();
        long targetIndexId = -1L;
        if (targetIndexName != null) {
            targetIndexId = olapTable.getIndexIdByName(targetIndexName);
        }

        addColumnInternal(olapTable, column, columnPos, targetIndexId, baseIndexId,
                          baseIndexName, indexSchemaMap);
    }

    private void processAddColumns(AddColumnsClause alterClause, OlapTable olapTable,
                                  Map<Long, LinkedList<Column>> indexSchemaMap) throws DdlException {
        List<Column> columns = alterClause.getColumns();
        String targetIndexName = alterClause.getRollupName();
        checkIndexExists(olapTable, targetIndexName);

        for (Column column : columns) {
            if (column.isKey()) {
                checkKeyModificationIfInRandomDistributedTable(olapTable);
            }
        }

        String baseIndexName = olapTable.getName();
        checkAssignedTargetIndexName(baseIndexName, targetIndexName);

        long baseIndexId = olapTable.getId();
        long targetIndexId = -1L;
        if (targetIndexName != null) {
            targetIndexId = olapTable.getIndexIdByName(targetIndexName);
        }

        for (Column columnDef : columns) {
            addColumnInternal(olapTable, columnDef, null, targetIndexId, baseIndexId,
                              baseIndexName, indexSchemaMap);
        }
    }

    private void processDropColumn(DropColumnClause alterClause, OlapTable olapTable,
                                  Map<Long, LinkedList<Column>> indexSchemaMap) throws DdlException {
        String dropColName = alterClause.getColName();
        String targetIndexName = alterClause.getRollupName();
        checkIndexExists(olapTable, targetIndexName);

        Column dropColumn = olapTable.getColumn(dropColName);
        if (dropColumn != null && dropColumn.isKey()) {
            checkKeyModificationIfInRandomDistributedTable(olapTable);
        }

        String baseIndexName = olapTable.getName();
        checkAssignedTargetIndexName(baseIndexName, targetIndexName);
        
        if (KeysType.UNIQUE_KEYS == olapTable.getKeysType()) {
            long baseIndexId = olapTable.getId();
            List<Column> baseSchema = indexSchemaMap.get(baseIndexId);
            boolean isKey = false;
            for (Column column : baseSchema) {
                if (column.isKey() && column.getName().equalsIgnoreCase(dropColName)) {
                    isKey = true;
                    break;
                }
            }
            
            if (isKey) {
                throw new DdlException("key column of unique key table cannot be droped");
            }
            
        } else if (KeysType.AGG_KEYS == olapTable.getKeysType()) {
            if (null == targetIndexName) {
                // drop column in base table
                long baseIndexId = olapTable.getId();
                List<Column> baseSchema = indexSchemaMap.get(baseIndexId);
                boolean isKey = false;
                boolean hasReplaceColumn = false;
                for (Column column : baseSchema) {
                    if (column.isKey() && column.getName().equalsIgnoreCase(dropColName)) {
                        isKey = true;
                    } else if (AggregateType.REPLACE == column.getAggregationType()) {
                        hasReplaceColumn = true;
                    }
                }
                if (isKey && hasReplaceColumn) {
                    throw new DdlException("key column of table with replace aggregation method cannot be droped");
                }
            } else {
                // drop column in rollup and basetable
                long targetIndexId = olapTable.getIndexIdByName(targetIndexName);
                // find column
                List<Column> targetIndexSchema = indexSchemaMap.get(targetIndexId);
                boolean isKey = false;
                boolean hasReplaceColumn = false;
                for (Column column : targetIndexSchema) {
                    if (column.isKey() && column.getName().equalsIgnoreCase(dropColName)) {
                        isKey = true;
                    } else if (AggregateType.REPLACE == column.getAggregationType()) {
                        hasReplaceColumn = true;
                    }
                }
                if (isKey && hasReplaceColumn) {
                    throw new DdlException("key column of table with replace aggregation method cannot be droped");
                }
            }
        }
        
        long baseIndexId = olapTable.getId();
        if (targetIndexName == null) {
            // drop base index and all rollup indices's column
            List<Long> indexIds = new ArrayList<Long>();
            indexIds.add(baseIndexId);
            for (long indexId : olapTable.getIndexIdToSchema().keySet()) {
                if (indexId == baseIndexId) {
                    continue;
                }
                indexIds.add(indexId);
            }

            // find column in base index and remove it
            List<Column> baseSchema = indexSchemaMap.get(baseIndexId);
            boolean found = false;
            Iterator<Column> baseIter = baseSchema.iterator();
            while (baseIter.hasNext()) {
                Column column = baseIter.next();
                if (column.getName().equalsIgnoreCase(dropColName)) {
                    baseIter.remove();
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new DdlException("Column[" + dropColName + "] does not exists");
            }

            // remove column in rollup index if exists (i = 1 to skip base index)
            for (int i = 1; i < indexIds.size(); i++) {
                List<Column> rollupSchema = indexSchemaMap.get(indexIds.get(i));
                Iterator<Column> iter = rollupSchema.iterator();
                while (iter.hasNext()) {
                    Column column = iter.next();
                    if (column.getName().equalsIgnoreCase(dropColName)) {
                        iter.remove();
                        break;
                    }
                }
            } // end for index names
        } else {
            // only drop column from specified rollup index
            long targetIndexId = olapTable.getIndexIdByName(targetIndexName);
            // find column
            List<Column> targetIndexSchema = indexSchemaMap.get(targetIndexId);
            boolean found = false;
            Iterator<Column> iter = targetIndexSchema.iterator();
            while (iter.hasNext()) {
                Column column = iter.next();
                if (column.getName().equalsIgnoreCase(dropColName)) {
                    iter.remove();
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new DdlException("Column[" + dropColName + "] does not exists");
            }
        }
    }

    private void processModifyColumn(ModifyColumnClause alterClause, OlapTable olapTable,
                                    Map<Long, LinkedList<Column>> indexSchemaMap) throws DdlException {
        Column modColumn = alterClause.getCol();
        if (KeysType.AGG_KEYS == olapTable.getKeysType()) {
            if (modColumn.isKey() && null != modColumn.getAggregationType()) {
                throw new DdlException("key column of aggregate key table cannot use aggregation method");
            } else if (null == modColumn.getAggregationType()) {
                // in aggregate key table, no aggreation method indicate key column
                modColumn.setIsKey(true);
            }
        } else if (KeysType.UNIQUE_KEYS == olapTable.getKeysType()) {
            if (null != modColumn.getAggregationType()) {
                throw new DdlException("column of unique key table cannot use aggregation method");
            }
            if (false == modColumn.isKey()) {
                modColumn.setAggregationType(AggregateType.REPLACE, true);
            }
        } else {
            if (null != modColumn.getAggregationType()) {
                throw new DdlException("column of duplicate key table cannot use aggregation method");
            }
            if (false == modColumn.isKey()) {
                modColumn.setAggregationType(AggregateType.NONE, true);
            }
        }
        ColumnPosition columnPos = alterClause.getColPos();
        String targetIndexName = alterClause.getRollupName();
        checkIndexExists(olapTable, targetIndexName);

        if (modColumn.isKey()) {
            checkKeyModificationIfInRandomDistributedTable(olapTable);
        }

        String baseIndexName = olapTable.getName();
        checkAssignedTargetIndexName(baseIndexName, targetIndexName);

        if (targetIndexName != null && columnPos == null) {
            throw new DdlException("Do not need to specify index name when just modifying column type");
        }

        String indexNameForFindingColumn = targetIndexName;
        if (indexNameForFindingColumn == null) {
            indexNameForFindingColumn = baseIndexName;
        }

        long indexIdForFindingColumn = olapTable.getIndexIdByName(indexNameForFindingColumn);

        // find modified column
        List<Column> schemaForFinding = indexSchemaMap.get(indexIdForFindingColumn);
        String newColName = modColumn.getName();
        boolean hasColPos = (columnPos != null && !columnPos.isFirst());
        boolean found = false;
        int modColIndex = -1;
        int lastColIndex = -1;
        for (int i = 0; i < schemaForFinding.size(); i++) {
            Column col = schemaForFinding.get(i);
            if (col.getName().equalsIgnoreCase(newColName)) {
                modColIndex = i;
                found = true;
            }
            if (hasColPos) {
                if (col.getName().equalsIgnoreCase(columnPos.getLastCol())) {
                    lastColIndex = i;
                }
            } else {
                // save the last Key position
                if (col.isKey()) {
                    lastColIndex = i;
                }
            }
        }
        // mod col not find
        if (!found) {
            throw new DdlException("Column[" + newColName + "] does not exists");
        }

        // last col not find
        if (hasColPos && lastColIndex == -1) {
            throw new DdlException("Column[" + columnPos.getLastCol() + "] does not exists");
        }

        // check if add to first
        if (columnPos != null && columnPos.isFirst()) {
            lastColIndex = -1;
            hasColPos = true;
        }

        Column oriColumn = schemaForFinding.get(modColIndex);
        // retain old column name
        modColumn.setName(oriColumn.getName());

        // handle the move operation in 'indexForFindingColumn' if has
        if (hasColPos) {
            // move col
            if (lastColIndex > modColIndex) {
                schemaForFinding.add(lastColIndex + 1, modColumn);
                schemaForFinding.remove(modColIndex);
            } else if (lastColIndex < modColIndex) {
                schemaForFinding.remove(modColIndex);
                schemaForFinding.add(lastColIndex + 1, modColumn);
            } else {
                throw new DdlException("Column[" + columnPos.getLastCol() + "] modify position is invalid");
            }
        } else {
            schemaForFinding.set(modColIndex, modColumn);
        }
        int temp = modColIndex;
        Column tempCol = schemaForFinding.get(temp);

        // check if column being mod
        if (!modColumn.equals(oriColumn)) {
            // column is mod. we have to mod this column in all indices

            // handle other indices
            // 1 find other indices which contain this column
            List<Long> otherIndexIds = new ArrayList<Long>();
            for (Map.Entry<Long, List<Column>> entry : olapTable.getIndexIdToSchema().entrySet()) {
                if (entry.getKey() == indexIdForFindingColumn) {
                    // skip the index we used to find column. it has been handled before
                    continue;
                }
                List<Column> schema = entry.getValue();
                for (Column column : schema) {
                    if (column.getName().equals(modColumn.getName())) {
                        otherIndexIds.add(entry.getKey());
                        break;
                    }
                }
            }

            if (KeysType.AGG_KEYS == olapTable.getKeysType()
                    || KeysType.UNIQUE_KEYS == olapTable.getKeysType()) {
                for (Long otherIndexId : otherIndexIds) {
                    List<Column> otherIndexSchema = indexSchemaMap.get(otherIndexId);
                    modColIndex = -1;
                    for (int i = 0; i < otherIndexSchema.size(); i++) {
                        if (otherIndexSchema.get(i).getName().equals(modColumn.getName())) {
                            modColIndex = i;
                            break;
                        }
                    }
                    Preconditions.checkState(modColIndex != -1);
                    // replace the old column
                    otherIndexSchema.set(modColIndex, modColumn);
                } //  end for other indices
            } else {
                for (Long otherIndexId : otherIndexIds) {
                    List<Column> otherIndexSchema = indexSchemaMap.get(otherIndexId);
                    modColIndex = -1;
                    for (int i = 0; i < otherIndexSchema.size(); i++) {
                        if (otherIndexSchema.get(i).getName().equals(modColumn.getName())) {
                            modColIndex = i;
                            break;
                        }
                    }
                    
                    Preconditions.checkState(modColIndex != -1);
                    // replace the old column
                    Column oldCol = otherIndexSchema.get(modColIndex);
                    Column otherCol = new Column(modColumn);
                    otherCol.setIsKey(oldCol.isKey());
                    if (null != oldCol.getAggregationType()) {
                        otherCol.setAggregationType(oldCol.getAggregationType(), oldCol.isAggregationTypeImplicit());
                    } else {
                        otherCol.setAggregationType(null, oldCol.isAggregationTypeImplicit());
                    }
                    otherIndexSchema.set(modColIndex, otherCol);
                }
                tempCol = schemaForFinding.get(temp);
            }
        } // end for handling other indices
    }

    private void processReorderColumn(ReorderColumnsClause alterClause, OlapTable olapTable,
                                     Map<Long, LinkedList<Column>> indexSchemaMap) throws DdlException {
        List<String> orderedColNames = alterClause.getColumnsByPos();
        String targetIndexName = alterClause.getRollupName();
        checkIndexExists(olapTable, targetIndexName);

        for (String colName : orderedColNames) {
            Column reorderdCol = olapTable.getColumn(colName);
            if (reorderdCol != null && reorderdCol.isKey()) {
                checkKeyModificationIfInRandomDistributedTable(olapTable);
            }
        }

        String baseIndexName = olapTable.getName();
        checkAssignedTargetIndexName(baseIndexName, targetIndexName);

        if (targetIndexName == null) {
            targetIndexName = baseIndexName;
        }

        long targetIndexId = olapTable.getIndexIdByName(targetIndexName);

        LinkedList<Column> newSchema = new LinkedList<Column>();
        LinkedList<Column> targetIndexSchema = indexSchemaMap.get(targetIndexId);

        // check and create new ordered column list
        Set<String> colNameSet = Sets.newTreeSet(String.CASE_INSENSITIVE_ORDER);
        for (String colName : orderedColNames) {
            Column oneCol = null;
            for (Column column : targetIndexSchema) {
                if (column.getName().equalsIgnoreCase(colName)) {
                    oneCol = column;
                    break;
                }
            }
            if (oneCol == null) {
                throw new DdlException("Column[" + colName + "] not exists");
            }
            newSchema.add(oneCol);
            if (colNameSet.contains(colName)) {
                throw new DdlException("Reduplicative column[" + colName + "]");
            } else {
                colNameSet.add(colName);
            }
        }
        if (newSchema.size() != targetIndexSchema.size()) {
            throw new DdlException("Reorder stmt should contains all columns");
        }
        // replace the old column list
        indexSchemaMap.put(targetIndexId, newSchema);
    }

    private void addColumnInternal(OlapTable olapTable, Column newColumn, ColumnPosition columnPos,
                                   long targetIndexId, long baseIndexId, String baseIndexName,
                                   Map<Long, LinkedList<Column>> indexSchemaMap) throws DdlException {
        
        if (KeysType.AGG_KEYS == olapTable.getKeysType()) {
            if (newColumn.isKey() && null != newColumn.getAggregationType()) {
                throw new DdlException("key column of aggregate key table cannot use aggregation method");
            } else if (null == newColumn.getAggregationType()) {
                // in aggregate key table, no aggreation method indicate key column
                newColumn.setIsKey(true);
            }
        } else if (KeysType.UNIQUE_KEYS == olapTable.getKeysType()) {
            if (null != newColumn.getAggregationType()) {
                throw new DdlException("column of unique key table cannot use aggregation method");
            }
            if (!newColumn.isKey()) {
                newColumn.setAggregationType(AggregateType.REPLACE, true);
            }
        } else {
            if (null != newColumn.getAggregationType()) {
                throw new DdlException("column of duplicate table cannot use aggregation method");
            }
            if (!newColumn.isKey()) {
                newColumn.setAggregationType(AggregateType.NONE, true);
            }
        }

        // hll must be used in agg_keys
        if (newColumn.getType().isHllType() && KeysType.AGG_KEYS != olapTable.getKeysType()) {
            throw new DdlException("HLL must be used in AGG_KEYS");
        }

        List<Column> baseSchema = olapTable.getBaseSchema();
        String newColName = newColumn.getName();
        boolean found = false;
        for (Column column : baseSchema) { 
            if (column.getName().equalsIgnoreCase(newColName)) {
                found = true;
                break;
            }
        }
        if (found) {
            throw new DdlException("Column[" + newColName + "] already exists in base index[" + baseIndexName + "]");
        }
        
        if (KeysType.UNIQUE_KEYS == olapTable.getKeysType()) {
            // check if has default value. this should be done in Analyze phase
            // 1. add to base index first
            // List<Column> modIndexSchema = indexSchemaMap.get(baseIndexId);
            // checkAndAddColumn(modIndexSchema, newColumn, columnPos);
            List<Column> modIndexSchema;
            if (newColumn.isKey()) {
                // add key column to unique key table, should add to all rollups
                // Column column = olapTable.getColumn(columnPos.getLastCol());  
                // add to all table including base and rollup
                for (Map.Entry<Long, LinkedList<Column>> entry : indexSchemaMap.entrySet()) {
                    modIndexSchema = entry.getValue();
                    checkAndAddColumn(modIndexSchema, newColumn, columnPos);
                }
            } else {
                // 1. add to base table
                modIndexSchema = indexSchemaMap.get(baseIndexId);
                checkAndAddColumn(modIndexSchema, newColumn, columnPos);
                if (targetIndexId == -1L) {
                    return;
                }

                // 2. add to rollup
                modIndexSchema = indexSchemaMap.get(targetIndexId);
                checkAndAddColumn(modIndexSchema, newColumn, columnPos); 
            } 
        } else if (KeysType.DUP_KEYS == olapTable.getKeysType()) {
            if (targetIndexId == -1L) {
                // check if has default value. this should be done in Analyze phase
                // 1. add to base index first
                List<Column> modIndexSchema = indexSchemaMap.get(baseIndexId);
                checkAndAddColumn(modIndexSchema, newColumn, columnPos);
                // no specified target index. return
                return;
            } else {
                // 2. add to rollup index
                List<Column> modIndexSchema = indexSchemaMap.get(targetIndexId);
                checkAndAddColumn(modIndexSchema, newColumn, columnPos);

                if (newColumn.isKey()) {
                    /*
                     * if add column in rollup is key, 
                     * then put the column in base table as end key
                     */
                    modIndexSchema = indexSchemaMap.get(baseIndexId);
                    checkAndAddColumn(modIndexSchema, newColumn, null);
                } else {
                    modIndexSchema = indexSchemaMap.get(baseIndexId);
                    checkAndAddColumn(modIndexSchema, newColumn, columnPos);
                }
            }
        } else {
            // check if has default value. this should be done in Analyze phase
            // 1. add to base index first
            List<Column> modIndexSchema = indexSchemaMap.get(baseIndexId);
            checkAndAddColumn(modIndexSchema, newColumn, columnPos);
            
            if (targetIndexId == -1L) {
                // no specified target index. return
                return;
            }

            // 2. add to rollup index
            modIndexSchema = indexSchemaMap.get(targetIndexId);
            checkAndAddColumn(modIndexSchema, newColumn, columnPos);
        }
    }

    private void checkAndAddColumn(List<Column> modIndexSchema, Column newColumn, ColumnPosition columnPos)
            throws DdlException {
        int posIndex = -1;
        String newColName = newColumn.getName();
        boolean hasPos = (columnPos != null && !columnPos.isFirst());
        for (int i = 0; i < modIndexSchema.size(); i++) {
            Column col = modIndexSchema.get(i);
            if (col.getName().equalsIgnoreCase(newColName)) {
                // already add
                throw new DdlException("Duplicately add column[" + newColName + "]");
            }
            if (hasPos) {
                // after the field
                if (col.getName().equalsIgnoreCase(columnPos.getLastCol())) {
                    posIndex = i;
                }
            } else {
                // save the last Key position
                if (col.isKey()) {
                    posIndex = i;
                }
            }
        }

        // check if lastCol was found
        if (hasPos && posIndex == -1) {
            throw new DdlException("Column[" + columnPos.getLastCol() + "] does not found");
        }

        // check if add to first
        if (columnPos != null && columnPos.isFirst()) {
            posIndex = -1;
            hasPos = true;
        }

        if (hasPos) {
            modIndexSchema.add(posIndex + 1, newColumn);
        } else {
            if (newColumn.isKey()) {
                // key
                modIndexSchema.add(posIndex + 1, newColumn);
            } else {
                // value
                modIndexSchema.add(newColumn);
            }
        }

        checkRowLength(modIndexSchema);
    }

    private void checkKeyModificationIfInRandomDistributedTable(OlapTable olapTable) throws DdlException {
        for (Partition partition : olapTable.getPartitions()) {
            DistributionInfo distributionInfo = partition.getDistributionInfo();
            if (distributionInfo.getType() == DistributionInfoType.RANDOM) {
                throw new DdlException("Cannot add/del/reorder/modify key column "
                        + "in table which is distributed by random");
            }
        }
    }

    private void checkRowLength(List<Column> modIndexSchema) throws DdlException {
        int rowLengthBytes = 0;
        for (Column column : modIndexSchema) {
            rowLengthBytes += column.getColumnType().getMemlayoutBytes();
        }

        if (rowLengthBytes > Config.max_layout_length_per_row) {
            throw new DdlException("The size of a row (" + rowLengthBytes + ") exceed the maximal row size: "
                    + Config.max_layout_length_per_row);
        }
    }

    private void createJob(long dbId, OlapTable olapTable, Map<Long, LinkedList<Column>> indexSchemaMap,
                           Map<String, String> propertyMap) throws DdlException {
        if (olapTable.getState() == OlapTableState.ROLLUP) {
            throw new DdlException("Table[" + olapTable.getName() + "]'s is doing ROLLUP job");
        }

        // check delay deleting old schema
        this.jobsLock.readLock().lock();
        try {
            for (SchemaChangeJob schemaChangeJob : delayDeleteSchemaChangeJobs) {
                if (schemaChangeJob.getTableId() == olapTable.getId()) {
                    long delayTime = System.currentTimeMillis() - schemaChangeJob.getFinishedTime();
                    // add ' + this.getInterval() ' because there will be a delay causing by thread running interval
                    long leftTime = Config.alter_delete_base_delay_second * 1000 + this.getInterval() - delayTime;
                    throw new DdlException("Old schema is not deleted. wait " + (leftTime / 1000)
                            + " second(s) and try again");
                }
            }
        } finally {
            this.jobsLock.readLock().unlock();
        }

        // for now table's state can only be NORMAL
        Preconditions.checkState(olapTable.getState() == OlapTableState.NORMAL, olapTable.getState().name());

        // process properties first
        // for now. properties has 2 option
        // property 1. to specify short key column count.
        // eg.
        //     "indexname1#short_key" = "3"
        //     "indexname2#short_key" = "4"
        Map<Long, Map<String, String>> indexIdToProperties = new HashMap<Long, Map<String, String>>();
        if (propertyMap.size() > 0) {
            for (String key : propertyMap.keySet()) {
                if (key.endsWith(PropertyAnalyzer.PROPERTIES_SHORT_KEY)) {
                    // short key
                    String[] keyArray = key.split("#");
                    if (keyArray.length != 2 || keyArray[0].isEmpty()
                            || !keyArray[1].equals(PropertyAnalyzer.PROPERTIES_SHORT_KEY)) {
                        throw new DdlException("Invalid alter table property: " + key);
                    }

                    HashMap<String, String> prop = new HashMap<String, String>();

                    if (!olapTable.hasMaterializedIndex(keyArray[0])) {
                        throw new DdlException("Index[" + keyArray[0] + "] does not exist");
                    }

                    prop.put(PropertyAnalyzer.PROPERTIES_SHORT_KEY, propertyMap.get(key));
                    indexIdToProperties.put(olapTable.getIndexIdByName(keyArray[0]), prop);
                }
            } // end for property keys
        }

        // property 2. bloom filter
        // eg. "bloom_filter_columns" = "k1,k2", "bloom_filter_fpp" = "0.05"
        Set<String> bfColumns = null;
        double bfFpp = 0;
        try {
            bfColumns = PropertyAnalyzer.analyzeBloomFilterColumns(propertyMap, indexSchemaMap.get(olapTable.getId()));
            bfFpp = PropertyAnalyzer.analyzeBloomFilterFpp(propertyMap);
        } catch (AnalysisException e) {
            throw new DdlException(e.getMessage());
        }

        // check bloom filter has change
        boolean hasBfChange = false;
        Set<String> oriBfColumns = olapTable.getCopiedBfColumns();
        double oriBfFpp = olapTable.getBfFpp();
        if (bfColumns != null) {
            if (bfFpp == 0) {
                // columns: yes, fpp: no
                if (bfColumns.equals(oriBfColumns)) {
                    throw new DdlException("Bloom filter index has no change");
                }

                if (oriBfColumns == null) {
                    bfFpp = FeConstants.default_bloom_filter_fpp;
                } else {
                    bfFpp = oriBfFpp;
                }
            } else {
                // columns: yes, fpp: yes
                if (bfColumns.equals(oriBfColumns) && bfFpp == oriBfFpp) {
                    throw new DdlException("Bloom filter index has no change");
                }
            }

            hasBfChange = true;
        } else {
            if (bfFpp == 0) {
                // columns: no, fpp: no
                bfFpp = oriBfFpp;
            } else {
                // columns: no, fpp: yes
                if (bfFpp == oriBfFpp) {
                    throw new DdlException("Bloom filter index has no change");
                }
                if (oriBfColumns == null) {
                    throw new DdlException("Bloom filter index has no change");
                }

                hasBfChange = true;
            }

            bfColumns = oriBfColumns;
        }

        if (bfColumns != null && bfColumns.isEmpty()) {
            bfColumns = null;
        }
        if (bfColumns == null) {
            bfFpp = 0;
        }

        // property 3 storage type
        // from now on, we only support COLUMN storage type
        TStorageType newStorageType = TStorageType.COLUMN;

        // resource info
        TResourceInfo resourceInfo = null;
        if (ConnectContext.get() != null) {
            resourceInfo = ConnectContext.get().toResourceCtx();
        }

        // create job
        SchemaChangeJob schemaChangeJob = new SchemaChangeJob(dbId, olapTable.getId(), resourceInfo,
                                                              olapTable.getName());
        schemaChangeJob.setTableBloomFilterInfo(hasBfChange, bfColumns, bfFpp);
        // begin checking each table
        // ATTN: DO NOT change any meta in this loop 
        long tableId = olapTable.getId();
        Map<Long, Short> indexIdToShortKeyColumnCount = new HashMap<Long, Short>();
        for (Long alterIndexId : indexSchemaMap.keySet()) {
            List<Column> originSchema = olapTable.getSchemaByIndexId(alterIndexId);
            List<Column> alterSchema = indexSchemaMap.get(alterIndexId);

            // 0. check if unchanged
            boolean hasColumnChange = false;
            if (alterSchema.size() != originSchema.size()) {
                hasColumnChange = true;
            } else {
                for (int i = 0; i < alterSchema.size(); i++) {
                    Column alterColumn = alterSchema.get(i);
                    if (!alterColumn.equals(originSchema.get(i))) {
                        hasColumnChange = true;
                        break;
                    }
                }
            }

            // if has column change, alter
            // else:
            //     if no bf change, no alter
            //     if has bf change, should check
            boolean needAlter = false;
            if (hasColumnChange) {
                needAlter = true;
            } else if (hasBfChange) {
                for (Column alterColumn : alterSchema) {
                    String columnName = alterColumn.getName();

                    boolean isOldBfColumn = false;
                    if (oriBfColumns != null && oriBfColumns.contains(columnName)) {
                        isOldBfColumn = true;
                    }

                    boolean isNewBfColumn = false;
                    if (bfColumns != null && bfColumns.contains(columnName)) {
                        isNewBfColumn = true;
                    }

                    if (isOldBfColumn != isNewBfColumn) {
                        // bf column change
                        needAlter = true;
                    } else if (isOldBfColumn && isNewBfColumn && oriBfFpp != bfFpp) {
                        // bf fpp change
                        needAlter = true;
                    }

                    if (needAlter) {
                        break;
                    }
                }
            }

            if (!needAlter) {
                // check if storage type changed
                TStorageType currentStorageType = olapTable.getStorageTypeByIndexId(alterIndexId);
                if (currentStorageType != newStorageType) {
                    needAlter = true;
                }
            }

            if (!needAlter) {
                LOG.debug("index[{}] is not changed. ignore", alterIndexId);
                continue;
            }

            LOG.debug("index[{}] is changed. start checking...", alterIndexId);
            // 1. check order: a) has key; b) value after key
            boolean meetValue = false;
            boolean hasKey = false;
            for (Column column : alterSchema) {
                if (column.isKey() && meetValue) {
                    throw new DdlException("Invalid column order. value should be after key. index["
                            + olapTable.getIndexNameById(alterIndexId) + "]");
                }
                if (!column.isKey()) {
                    meetValue = true;
                } else {
                    hasKey = true;
                }
            }
            if (!hasKey) {
                throw new DdlException("No key column left. index[" + olapTable.getIndexNameById(alterIndexId) + "]");
            }

            if (KeysType.AGG_KEYS == olapTable.getKeysType()
                    || KeysType.UNIQUE_KEYS == olapTable.getKeysType()) {
                // 2. check compatible
                for (Column alterColumn : alterSchema) {
                    for (Column oriColumn : originSchema) {
                        if (alterColumn.getName().equals(oriColumn.getName())) {
                            if (!alterColumn.equals(oriColumn)) {
                                // 3.1 check type
                                oriColumn.checkSchemaChangeAllowed(alterColumn);
                                // default value's compatibility is checked in Analyze phase
                            }
                        }
                    } // end for ori
                } // end for alter
            }

            // 3. check partition key
            PartitionInfo partitionInfo = olapTable.getPartitionInfo();
            if (partitionInfo.getType() == PartitionType.RANGE) {
                RangePartitionInfo rangePartitionInfo = (RangePartitionInfo) partitionInfo;
                List<Column> partitionColumns = rangePartitionInfo.getPartitionColumns();
                for (Column partitionCol : partitionColumns) {
                    boolean found = false;
                    for (Column alterColumn : alterSchema) {
                        if (alterColumn.getName().equals(partitionCol.getName())) {
                            // 2.1 partition column cannot be modified
                            if (!alterColumn.equals(partitionCol)) {
                                throw new DdlException("Can not modify partition column["
                                        + partitionCol.getName() + "]. index["
                                        + olapTable.getIndexNameById(alterIndexId) + "]");
                            }
                            found = true;
                            break;
                        }
                    } // end for alterColumns
                    if (!found && alterIndexId == tableId) {
                        // 2.1 partition column cannot be deleted.
                        throw new DdlException("Partition column[" + partitionCol.getName()
                                + "] cannot be dropped. index[" + olapTable.getIndexNameById(alterIndexId) + "]");
                        // ATTN. partition columns' order also need remaining unchanged.
                        // for now, we only allow one partition column, so no need to check order.
                    }
                } // end for partitionColumns
            }

            // 4. check distribution key:
            DistributionInfo distributionInfo = olapTable.getDefaultDistributionInfo();
            if (distributionInfo.getType() == DistributionInfoType.HASH) {
                List<Column> distributionColumns = ((HashDistributionInfo) distributionInfo).getDistributionColumns();
                for (Column distributionCol : distributionColumns) {
                    boolean found = false;
                    for (Column alterColumn : alterSchema) {
                        if (alterColumn.getName().equals(distributionCol.getName())) {
                            // 3.1 distribution column cannot be modified
                            if (!alterColumn.equals(distributionCol)) {
                                throw new DdlException("Cannot modify distribution column["
                                        + distributionCol.getName() + "]. index["
                                        + olapTable.getIndexNameById(alterIndexId) + "]");
                            }
                            found = true;
                            break;
                        }
                    } // end for alterColumns
                    if (!found && alterIndexId == tableId) {
                        // 2.2 distribution column cannot be deleted.
                        throw new DdlException("Distribution column[" + distributionCol.getName()
                                + "] cannot be dropped. index[" + olapTable.getIndexNameById(alterIndexId) + "]");
                    }
                } // end for distributionCols
            }

            // 5. check if has replicas
            for (Partition partition : olapTable.getPartitions()) {
                MaterializedIndex alterIndex = partition.getIndex(alterIndexId);
                Preconditions.checkState(alterIndex.getState() == IndexState.NORMAL, alterIndex.getState().name());

                short replicationNum = olapTable.getPartitionInfo().getReplicationNum(partition.getId());
                for (Tablet tablet : alterIndex.getTablets()) {
                    int replicaNum = 0;
                    for (Replica replica : tablet.getReplicas()) {
                        if (replica.getState() == ReplicaState.CLONE) {
                            // just skip it (replica cloned from old schema will be deleted)
                            continue;
                        }
                        ++replicaNum;
                    } // end for replicas

                    if (replicaNum < replicationNum / 2 + 1) {
                        String errMsg = "Tablet[" + tablet.getId() + "] does not have enough replicas. ["
                                + replicaNum + "/" + replicationNum + "]";
                        LOG.warn(errMsg);
                        throw new DdlException(errMsg);
                    }
                } // end for tablets
            } // end for partitions

            // 6. calc short key
            short newShortKeyColumnCount = Catalog.calcShortKeyColumnCount(alterSchema,
                                                                           indexIdToProperties.get(alterIndexId));
            LOG.debug("alter index[{}] short key column count: {}", alterIndexId, newShortKeyColumnCount);
            indexIdToShortKeyColumnCount.put(alterIndexId, newShortKeyColumnCount);

            // 7. check storage type if has null column
            TStorageType storageType = olapTable.getStorageTypeByIndexId(alterIndexId);
            boolean hasNullColumn = false;
            for (Column column : alterSchema) {
                if (column.isAllowNull()) {
                    hasNullColumn = true;
                    break;
                }
            }
            if (hasNullColumn && storageType != TStorageType.COLUMN) {
                throw new DdlException("Only column rollup support null columns");
            }

            // 8. store the changed columns for edit log
            schemaChangeJob.putToChangedIndexSchemaMap(alterIndexId, alterSchema);

            LOG.debug("schema change[{}-{}-{}] check pass.", dbId, tableId, alterIndexId);
        } // end for indices

        if (schemaChangeJob.getChangedIndexToSchema().isEmpty()) {
            throw new DdlException("Nothing is changed. please check your alter stmt.");
        }

        // from now on, storage type can only be column
        schemaChangeJob.setNewStorageType(TStorageType.COLUMN);

        // the following operations are done outside the 'for indices' loop
        // to avoid partial check success

        // 1. create schema change job
        for (Partition onePartition : olapTable.getPartitions()) {
            for (Map.Entry<Long, List<Column>> entry : schemaChangeJob.getChangedIndexToSchema().entrySet()) {
                long indexId = entry.getKey();
                MaterializedIndex alterIndex = onePartition.getIndex(indexId);
                Preconditions.checkState(alterIndex.getState() == IndexState.NORMAL, alterIndex.getState());

                // set new schema
                int currentSchemaVersion = olapTable.getSchemaVersionByIndexId(indexId);
                int newSchemaVersion = currentSchemaVersion + 1;
                List<Column> alterColumns = entry.getValue();
                int newSchemaHash = Util.schemaHash(newSchemaVersion, alterColumns, bfColumns, bfFpp);
                short newShortKeyColumnCount = indexIdToShortKeyColumnCount.get(indexId);
                schemaChangeJob.setNewSchemaInfo(indexId, newSchemaVersion, newSchemaHash, newShortKeyColumnCount);

                // set replica state
                for (Tablet tablet : alterIndex.getTablets()) {
                    for (Replica replica : tablet.getReplicas()) {
                        if (replica.getState() == ReplicaState.CLONE) {
                            // just skip it (replica cloned from old schema will be deleted)
                            continue;
                        }
                        Preconditions.checkState(replica.getState() == ReplicaState.NORMAL);
                        replica.setState(ReplicaState.SCHEMA_CHANGE);
                    } // end for replicas
                } // end for tablets

                Catalog.getCurrentInvertedIndex().setNewSchemaHash(onePartition.getId(), indexId, newSchemaHash);

                alterIndex.setState(IndexState.SCHEMA_CHANGE);
            } // end for indices

            onePartition.setState(PartitionState.SCHEMA_CHANGE);
        } // end for partitions

        olapTable.setState(OlapTableState.SCHEMA_CHANGE);

        // 2. add schemaChangeJob
        addAlterJob(schemaChangeJob);

        // 3. log schema change start operation
        Catalog.getInstance().getEditLog().logStartSchemaChange(schemaChangeJob);
        LOG.info("schema change job created. table[{}]", olapTable.getName());
    }

    private void checkIndexExists(OlapTable olapTable, String targetIndexName) throws DdlException {
        if (targetIndexName != null && !olapTable.hasMaterializedIndex(targetIndexName)) {
            throw new DdlException("Index[" + targetIndexName + "] does not exist in table[" + olapTable.getName()
                    + "]");
        }
    }

    private void checkAssignedTargetIndexName(String baseIndexName, String targetIndexName) throws DdlException {
        // user cannot assign base index to do schema change
        if (targetIndexName != null) {
            if (targetIndexName.equals(baseIndexName)) {
                throw new DdlException("Do not need to assign base index[" + baseIndexName + "] to do schema change");
            }
        }
    }

    public void removeReplicaRelatedTask(long tableId, long tabletId, long replicaId, long backendId) {
        AlterJob job = getAlterJob(tableId);
        if (job != null) {
            job.removeReplicaRelatedTask(-1L, tabletId, replicaId, backendId);
        }
    }

    public int getDelayDeletingJobNum(long dbId) {
        int jobNum = 0;
        this.jobsLock.readLock().lock();
        try {
            for (AlterJob alterJob : delayDeleteSchemaChangeJobs) {
                if (alterJob.getDbId() == dbId) {
                    ++jobNum;
                }
            }
            return jobNum;
        } finally {
            this.jobsLock.readLock().unlock();
        }
    }

    @Override
    protected void runOneCycle() {
        super.runOneCycle();

        List<AlterJob> cancelledJobs = Lists.newArrayList();
        // copied all jobs out of alterJobs to avoid lock problems
        List<AlterJob> copiedAlterJobs = Lists.newArrayList();
        Set<Long> removedIds = Sets.newHashSet();

        this.jobsLock.readLock().lock();
        try {
            copiedAlterJobs.addAll(alterJobs.values());
        } finally {
            this.jobsLock.readLock().unlock();
        }

        // handle all alter jobs
        for (AlterJob alterJob : copiedAlterJobs) {
            JobState state = alterJob.getState();
            switch (state) {
                case PENDING: {
                    if (!alterJob.sendTasks()) {
                        cancelledJobs.add(alterJob);
                        LOG.warn("sending schema change job[" + alterJob.getTableId()
                                + "] tasks failed. cancel it.");
                    }
                    break;
                }
                case RUNNING: {
                    if (alterJob.isTimeout()) {
                        cancelledJobs.add(alterJob);
                    } else {
                        int res = alterJob.tryFinishJob();
                        if (res == -1) {
                            cancelledJobs.add(alterJob);
                            LOG.warn("cancel bad schema change job[{}]", alterJob.getTableId());
                        } else if (res == 1) {
                            // finished
                            removedIds.add(alterJob.getTableId());
                        }
                    }
                    break;
                }
                case FINISHED: {
                    // FINISHED state should be handled in RUNNING case
                    Preconditions.checkState(false);
                    break;
                }
                case CANCELLED: {
                    // all CANCELLED state should be handled immediately
                    Preconditions.checkState(false);
                    break;
                }
                default:
                    Preconditions.checkState(false);
                    break;
            }
        } // end for jobs

        // remove job from alterJobs and add to delayDeleteSchemaChangeJobs
        copiedAlterJobs.clear();
        this.jobsLock.writeLock().lock();
        try {
            for (Long tblId : removedIds) {
                AlterJob job = alterJobs.remove(tblId);
                if (job != null) {
                    delayDeleteSchemaChangeJobs.add((SchemaChangeJob) job);
                }
            }
            copiedAlterJobs.addAll(delayDeleteSchemaChangeJobs);
        } finally {
            this.jobsLock.writeLock().unlock();
        }

        // handle delay delete jobs
        removedIds.clear();
        for (AlterJob alterJob : copiedAlterJobs) {
            SchemaChangeJob job = (SchemaChangeJob) alterJob;
            Preconditions.checkState(job.getFinishedTime() > 0L);
            if (job.tryDeleteAllTableHistorySchema()) {
                addFinishedOrCancelledAlterJob(job);
                removedIds.add(alterJob.getTableId());
            }
        }

        this.jobsLock.writeLock().lock();
        try {
            for (Long tblId : removedIds) {
                Iterator<SchemaChangeJob> iter = delayDeleteSchemaChangeJobs.iterator();
                while (iter.hasNext()) {
                    SchemaChangeJob job = iter.next();
                    if (job.getTableId() == tblId) {
                        iter.remove();
                    }
                }
            }
        } finally {
            this.jobsLock.writeLock().unlock();
        }

        // handle cancelled rollup jobs
        for (AlterJob alterJob : cancelledJobs) {
            Database db = Catalog.getInstance().getDb(alterJob.getDbId());
            if (db == null) {
                cancelInternal(alterJob, null, null);
            }
            db.writeLock();
            try {
                OlapTable olapTable = (OlapTable) db.getTable(alterJob.getTableId());
                if (olapTable == null) {
                    cancelInternal(alterJob, null, null);
                }
                cancelInternal(alterJob, olapTable, null);
            } finally {
                db.writeUnlock();
            }
        }
    }

    @Override
    public List<List<Comparable>> getAlterJobInfosByDb(Database db) {
        List<List<Comparable>> schemaChangeJobInfos = new LinkedList<List<Comparable>>();
        db.readLock();
        try {
            this.jobsLock.readLock().lock();
            try {
                // init or running
                for (AlterJob alterJob : this.alterJobs.values()) {
                    getJobInfo(schemaChangeJobInfos, (SchemaChangeJob) alterJob, db, false);
                }

                // delay deleting
                for (AlterJob alterJob : this.delayDeleteSchemaChangeJobs) {
                    getJobInfo(schemaChangeJobInfos, (SchemaChangeJob) alterJob, db, true);
                }

                // finished or cancelled
                for (AlterJob alterJob : this.finishedOrCancelledAlterJobs) {
                    getJobInfo(schemaChangeJobInfos, (SchemaChangeJob) alterJob, db, true);
                }

                // sort by "JobId", "PartitionName", "CreateTime", "FinishTime", "IndexName", "IndexState"
                ListComparator<List<Comparable>> comparator = new ListComparator<List<Comparable>>(0, 1, 2, 3, 4, 5);
                Collections.sort(schemaChangeJobInfos, comparator);
            } catch (Exception e) {
                LOG.warn("failed to get schema change job info", e);
            } finally {
                this.jobsLock.readLock().unlock();
            }
        } finally {
            db.readUnlock();
        }
        return schemaChangeJobInfos;
    }

    @Override
    public void process(List<AlterClause> alterClauses, String clusterName, Database db, OlapTable olapTable) throws DdlException {
        // index id -> index schema
        Map<Long, LinkedList<Column>> indexSchemaMap = new HashMap<Long, LinkedList<Column>>();
        for (Map.Entry<Long, List<Column>> entry : olapTable.getIndexIdToSchema().entrySet()) {
            indexSchemaMap.put(entry.getKey(), new LinkedList<Column>(entry.getValue()));
        }
        // index name -> properties
        Map<String, String> propertyMap = new HashMap<String, String>();
        for (AlterClause alterClause : alterClauses) {
            // get properties
            Map<String, String> properties = alterClause.getProperties();
            if (properties != null) {
                if (propertyMap.isEmpty()) {
                    propertyMap.putAll(properties);
                } else {
                    throw new DdlException("reduplicated PROPERTIES");
                }
            }

            if (alterClause instanceof AddColumnClause) {
                // add column
                processAddColumn((AddColumnClause) alterClause, olapTable, indexSchemaMap);
            } else if (alterClause instanceof AddColumnsClause) {
                // add columns
                processAddColumns((AddColumnsClause) alterClause, olapTable, indexSchemaMap);
            } else if (alterClause instanceof DropColumnClause) {
                // drop column
                processDropColumn((DropColumnClause) alterClause, olapTable, indexSchemaMap);
            } else if (alterClause instanceof ModifyColumnClause) {
                // modify column
                processModifyColumn((ModifyColumnClause) alterClause, olapTable, indexSchemaMap);
            } else if (alterClause instanceof ReorderColumnsClause) {
                // reorder column
                processReorderColumn((ReorderColumnsClause) alterClause, olapTable, indexSchemaMap);
            } else if (alterClause instanceof ModifyTablePropertiesClause) {
                // modify table properties
                // do nothing, properties are already in propertyMap
            } else {
                Preconditions.checkState(false);
            }
        } // end for alter clauses

        createJob(db.getId(), olapTable, indexSchemaMap, propertyMap);
    }

    @Override
    public void cancel(CancelStmt stmt) throws DdlException {
        CancelAlterTableStmt cancelAlterTableStmt = (CancelAlterTableStmt) stmt;

        String dbName = cancelAlterTableStmt.getDbName();
        String tableName = cancelAlterTableStmt.getTableName();
        final String clusterName = cancelAlterTableStmt.getClusterName();
        Preconditions.checkState(!Strings.isNullOrEmpty(dbName));
        Preconditions.checkState(!Strings.isNullOrEmpty(tableName));

        Database db = Catalog.getInstance().getDb(dbName);
        if (db == null) {
            throw new DdlException("Database[" + dbName + "] does not exist");
        }

        db.writeLock();
        try {
            this.jobsLock.writeLock().lock();
            try {
                // 1. get table
                OlapTable olapTable = (OlapTable) db.getTable(tableName);
                if (olapTable == null) {
                    throw new DdlException("Table[" + tableName + "] does not exist");
                }

                // 2. find schema change job
                AlterJob alterJob = null;
                Iterator<Map.Entry<Long, AlterJob>> iterator = this.alterJobs.entrySet().iterator();
                while (iterator.hasNext()) {
                    alterJob = iterator.next().getValue();
                    if (alterJob.getTableId() == olapTable.getId()) {
                        break;
                    }
                }
                if (alterJob == null) {
                    throw new DdlException("Table[" + tableName + "] is not under SCHEMA CHANGE");
                }

                // 3. cancel schema change job
                cancelInternal(alterJob, olapTable, "user cancelled");

                // 4. remove from job list
                this.alterJobs.remove(alterJob.getTableId());
            } finally {
                this.jobsLock.writeLock().unlock();
            }
        } finally {
            db.writeUnlock();
        }
    }

    private void getJobInfo(List<List<Comparable>> schemaChangeJobInfos,
                            SchemaChangeJob schemaChangeJob, Database db, boolean isFinished) {
        if (schemaChangeJob.getDbId() != db.getId()) {
            return;
        }

        long tableId = schemaChangeJob.getTableId();
        OlapTable olapTable = (OlapTable) db.getTable(tableId);
        if (olapTable == null) {
            return;
        }
        
        // check auth
        if (!Catalog.getCurrentCatalog().getAuth().checkTblPriv(ConnectContext.get(), db.getFullName(),
                                                                olapTable.getName(),
                                                                PrivPredicate.ALTER)) {
            // no priv, return
            LOG.debug("No priv for user {} to table {}.{}", ConnectContext.get().getQualifiedUser(),
                      ConnectContext.get().getRemoteIP(), db.getFullName(), olapTable.getName());
            return;
        }

        // create time
        long createTime = schemaChangeJob.getCreateTimeMs();
        String createTimeStr = TimeUtils.longToTimeString(createTime);

        // finish time
        long finishTime = schemaChangeJob.getFinishedTime();
        String finishTimeStr = TimeUtils.longToTimeString(finishTime);

        if (isFinished) {
            List<Comparable> jobInfo = new ArrayList<Comparable>();
            jobInfo.add(tableId);
            jobInfo.add(olapTable.getName());
            jobInfo.add(createTimeStr);
            jobInfo.add(finishTimeStr);
            jobInfo.add("N/A");
            jobInfo.add("N/A");
            jobInfo.add(schemaChangeJob.getState().name());
            jobInfo.add(schemaChangeJob.getMsg());
            jobInfo.add("N/A");

            schemaChangeJobInfos.add(jobInfo);
            return;
        }

        // calc progress and state for each table
        Map<Long, String> indexProgress = new HashMap<Long, String>();
        Map<Long, String> indexState = new HashMap<Long, String>();
        for (Long indexId : schemaChangeJob.getChangedIndexToSchema().keySet()) {
            int totalReplicaNum = 0;
            int finishedReplicaNum = 0;
            String state = IndexState.NORMAL.name();
            for (Partition partition : olapTable.getPartitions()) {
                MaterializedIndex index = partition.getIndex(indexId);
                int tableReplicaNum = schemaChangeJob.getTotalReplicaNumByIndexId(indexId);
                int tableFinishedReplicaNum = schemaChangeJob.getFinishedReplicaNumByIndexId(indexId);
                Preconditions.checkState(!(tableReplicaNum == 0 && tableFinishedReplicaNum == -1));
                Preconditions.checkState(tableFinishedReplicaNum <= tableReplicaNum,
                                         tableFinishedReplicaNum + "/" + tableReplicaNum);
                totalReplicaNum += tableReplicaNum;
                finishedReplicaNum += tableFinishedReplicaNum;

                if (index.getState() != IndexState.NORMAL) {
                    state = index.getState().name();
                }
            }
            if (Catalog.getInstance().isMaster()
                    && (schemaChangeJob.getState() == JobState.RUNNING
                    || schemaChangeJob.getState() == JobState.FINISHED)) {
                indexProgress.put(indexId, (finishedReplicaNum * 100 / totalReplicaNum) + "%");
                indexState.put(indexId, state);
            } else {
                indexProgress.put(indexId, "0%");
                indexState.put(indexId, state);
            }
        }

        for (Long indexId : schemaChangeJob.getChangedIndexToSchema().keySet()) {
            List<Comparable> jobInfo = new ArrayList<Comparable>();

            jobInfo.add(tableId);
            jobInfo.add(olapTable.getName());
            jobInfo.add(createTimeStr);
            jobInfo.add(finishTimeStr);
            jobInfo.add(olapTable.getIndexNameById(indexId));
            jobInfo.add(indexState.get(indexId));
            jobInfo.add(schemaChangeJob.getState().name());
            jobInfo.add(schemaChangeJob.getMsg());
            jobInfo.add(indexProgress.get(indexId));

            schemaChangeJobInfos.add(jobInfo);
        } // end for indexIds
    }
}
