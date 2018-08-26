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

package com.baidu.palo.analysis;

import com.baidu.palo.catalog.Column;
import com.baidu.palo.catalog.ColumnType;
import com.baidu.palo.catalog.InfoSchemaDb;
import com.baidu.palo.cluster.ClusterNamespace;
import com.baidu.palo.common.AnalysisException;
import com.baidu.palo.common.ErrorCode;
import com.baidu.palo.common.ErrorReport;
import com.baidu.palo.qe.ShowResultSetMetaData;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

// SHOW TABLES
public class ShowTableStmt extends ShowStmt {
    private static final Logger LOG = LogManager.getLogger(ShowTableStmt.class);
    private static final String NAME_COL_PREFIX = "Tables_in_";
    private static final String TYPE_COL = "Table_type";
    private static final TableName TABLE_NAME = new TableName(InfoSchemaDb.DATABASE_NAME, "tables");
    private String db;
    private boolean isVerbose;
    private String pattern;
    private Expr where;
    private SelectStmt selectStmt;

    public ShowTableStmt(String db, boolean isVerbose, String pattern) {
        this.db = db;
        this.isVerbose = isVerbose;
        this.pattern = pattern;
        this.where = null;
    }

    public ShowTableStmt(String db, boolean isVerbose, String pattern, Expr where) {
        this.db = db;
        this.isVerbose = isVerbose;
        this.pattern = pattern;
        this.where = where;
    }

    public String getDb() {
        return db;
    }

    public boolean isVerbose() {
        return isVerbose;
    }

    public String getPattern() {
        return pattern;
    }

    @Override
    public void analyze(Analyzer analyzer) throws AnalysisException {
        if (Strings.isNullOrEmpty(db)) {
            db = analyzer.getDefaultDb();
            if (Strings.isNullOrEmpty(db)) {
                ErrorReport.reportAnalysisException(ErrorCode.ERR_NO_DB_ERROR);
            }
        } else {
            db = ClusterNamespace.getFullName(analyzer.getClusterName(), db);
        }

        // we do not check db privs here. because user may not have any db privs,
        // but if it has privs of tbls inside this db,it should be allowed to see this db.
    }

    @Override
    public SelectStmt toSelectStmt(Analyzer analyzer) throws AnalysisException {
        if (where == null) {
            return null;
        }
        if (selectStmt != null) {
            return selectStmt;
        }
        analyze(analyzer);
        // Columns
        SelectList selectList = new SelectList();
        ExprSubstitutionMap aliasMap = new ExprSubstitutionMap(false);
        SelectListItem item = new SelectListItem(new SlotRef(TABLE_NAME, "TABLE_NAME"),
                NAME_COL_PREFIX + ClusterNamespace.getNameFromFullName(db));
        selectList.addItem(item);
        aliasMap.put(new SlotRef(null, NAME_COL_PREFIX + ClusterNamespace.getNameFromFullName(db)),
                item.getExpr().clone(null));
        if (isVerbose) {
            item = new SelectListItem(new SlotRef(TABLE_NAME, "TABLE_TYPE"), TYPE_COL);
            selectList.addItem(item);
            aliasMap.put(new SlotRef(null, TYPE_COL), item.getExpr().clone(null));
        }
        where = where.substitute(aliasMap);
        selectStmt = new SelectStmt(selectList,
                new FromClause(Lists.newArrayList(new TableRef(TABLE_NAME, null))),
                where, null, null, null, LimitElement.NO_LIMIT);

        analyzer.setSchemaInfo(ClusterNamespace.getNameFromFullName(db), null, null);

        return selectStmt;
    }

    @Override
    public String toSql() {
        StringBuilder sb = new StringBuilder();
        sb.append("SHOW");
        if (isVerbose) {
            sb.append(" FULL");
        }
        sb.append(" TABLES");
        if (!Strings.isNullOrEmpty(db)) {
            sb.append(" FROM ").append(db);
        }
        if (pattern != null) {
            sb.append(" LIKE '").append(pattern).append("'");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return toSql();
    }

    @Override
    public ShowResultSetMetaData getMetaData() {
        ShowResultSetMetaData.Builder builder = ShowResultSetMetaData.builder();
        builder.addColumn(
                new Column(NAME_COL_PREFIX + ClusterNamespace.getNameFromFullName(db), ColumnType.createVarchar(20)));
        if (isVerbose) {
            builder.addColumn(new Column(TYPE_COL, ColumnType.createVarchar(20)));
        }
        return builder.build();
    }
}
