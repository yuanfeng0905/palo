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

import com.baidu.palo.catalog.Catalog;
import com.baidu.palo.catalog.Column;
import com.baidu.palo.catalog.ColumnType;
import com.baidu.palo.common.AnalysisException;
import com.baidu.palo.common.ErrorCode;
import com.baidu.palo.common.ErrorReport;
import com.baidu.palo.mysql.privilege.PrivPredicate;
import com.baidu.palo.qe.ConnectContext;
import com.baidu.palo.qe.ShowResultSetMetaData;

// SHOW CREATE TABLE statement.
public class ShowCreateTableStmt extends ShowStmt {
    private static final ShowResultSetMetaData META_DATA =
            ShowResultSetMetaData.builder()
                    .addColumn(new Column("Table", ColumnType.createVarchar(20)))
                    .addColumn(new Column("Create Table", ColumnType.createVarchar(30)))
                    .build();

    private static final ShowResultSetMetaData VIEW_META_DATA =
            ShowResultSetMetaData.builder()
                    .addColumn(new Column("View", ColumnType.createVarchar(20)))
                    .addColumn(new Column("Create View", ColumnType.createVarchar(30)))
                    .addColumn(new Column("character_set_client", ColumnType.createVarchar(30)))
                    .addColumn(new Column("collation_connection", ColumnType.createVarchar(30)))
                    .build();

    private TableName tbl;
    private boolean isView;

    public ShowCreateTableStmt(TableName tbl) {
        this(tbl, false);
    }

    public ShowCreateTableStmt(TableName tbl, boolean isView) {
        this.tbl = tbl;
        this.isView = isView;
    }

    public String getDb() {
        return tbl.getDb();
    }

    public String getTable() {
        return tbl.getTbl();
    }

    public boolean isView() {
        return isView;
    }

    public static ShowResultSetMetaData getViewMetaData() {
        return VIEW_META_DATA;
    }

    @Override
    public void analyze(Analyzer analyzer) throws AnalysisException {
        if (tbl == null) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_NO_TABLES_USED);
        }
        tbl.analyze(analyzer);

        if (!Catalog.getCurrentCatalog().getAuth().checkTblPriv(ConnectContext.get(), tbl.getDb(), tbl.getTbl(),
                                                                PrivPredicate.SHOW)) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_TABLEACCESS_DENIED_ERROR, "SHOW CREATE TABLE",
                                                ConnectContext.get().getQualifiedUser(),
                                                ConnectContext.get().getRemoteIP(),
                                                tbl.getTbl());
        }
    }

    @Override
    public String toSql() {
        return "SHOW CREATE TABLE " + tbl;
    }

    @Override
    public String toString() {
        return toSql();
    }

    @Override
    public ShowResultSetMetaData getMetaData() {
        return META_DATA;
    }
}
