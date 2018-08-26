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
import com.baidu.palo.common.AnalysisException;
import com.baidu.palo.common.ErrorCode;
import com.baidu.palo.common.ErrorReport;
import com.baidu.palo.common.InternalException;
import com.baidu.palo.mysql.privilege.PrivPredicate;
import com.baidu.palo.qe.ConnectContext;
import com.baidu.palo.system.SystemInfoService;

import com.google.common.base.Strings;

public class DropClusterStmt extends DdlStmt {
    private boolean ifExists;
    private String name;

    public DropClusterStmt(boolean ifExists, String name) {
        this.ifExists = ifExists;
        this.name = name;
    }

    @Override
    public void analyze(Analyzer analyzer) throws AnalysisException, InternalException {
        if (Strings.isNullOrEmpty(name)) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_CLUSTER_NAME_NULL);
        }

        if (name.equalsIgnoreCase(SystemInfoService.DEFAULT_CLUSTER)) {
            throw new AnalysisException("Can not drop " + SystemInfoService.DEFAULT_CLUSTER);
        }

        if (!Catalog.getCurrentCatalog().getAuth().checkGlobalPriv(ConnectContext.get(), PrivPredicate.OPERATOR)) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_CLUSTER_NO_PERMISSIONS);
        }
    }

    @Override
    public String toSql() {
        return "DROP CLUSTER " + name;
    }

    public String getClusterName() {
        return name;
    }

    public void setClusterName(String clusterName) {
        this.name = clusterName;
    }

    public boolean isIfExists() {
        return ifExists;
    }

    public void setIfExists(boolean ifExists) {
        this.ifExists = ifExists;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

}
