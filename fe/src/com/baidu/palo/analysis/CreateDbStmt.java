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

import com.baidu.palo.catalog.Catalog;
import com.baidu.palo.cluster.ClusterNamespace;
import com.baidu.palo.common.AnalysisException;
import com.baidu.palo.common.ErrorCode;
import com.baidu.palo.common.ErrorReport;
import com.baidu.palo.common.FeNameFormat;
import com.baidu.palo.common.InternalException;
import com.baidu.palo.mysql.privilege.PrivPredicate;
import com.baidu.palo.qe.ConnectContext;

import com.google.common.base.Strings;

// 用于描述CREATE DATABASE的内部结构
public class CreateDbStmt extends DdlStmt {
    private boolean ifNotExists;
    private String dbName;

    public CreateDbStmt(boolean ifNotExists, String dbName) {
        this.ifNotExists = ifNotExists;
        this.dbName = dbName;
    }

    public String getFullDbName() {
        return dbName;
    }

    public boolean isSetIfNotExists() {
        return ifNotExists;
    }

    @Override
    public void analyze(Analyzer analyzer) throws AnalysisException, InternalException {
        super.analyze(analyzer);
        if (Strings.isNullOrEmpty(analyzer.getClusterName())) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_CLUSTER_NO_SELECT_CLUSTER);
        }
        FeNameFormat.checkDbName(dbName);
        dbName = ClusterNamespace.getFullName(getClusterName(), dbName);

        if (!Catalog.getCurrentCatalog().getAuth().checkDbPriv(ConnectContext.get(), dbName, PrivPredicate.CREATE)) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_DB_ACCESS_DENIED, analyzer.getQualifiedUser(), dbName);
        }
    }

    @Override
    public String toString() {
        return toSql();
    }

    @Override
    public String toSql() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("CREATE DATABASE ").append("`").append(dbName).append("`");
        return stringBuilder.toString();
    }
}
