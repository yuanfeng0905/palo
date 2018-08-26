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

import com.baidu.palo.cluster.ClusterNamespace;
import com.baidu.palo.common.AnalysisException;
import com.baidu.palo.common.FeNameFormat;
import com.baidu.palo.common.InternalException;

public class DropRoleStmt extends DdlStmt {

    private String role;

    public DropRoleStmt(String role) {
        this.role = role;
    }

    public String getQualifiedRole() {
        return role;
    }

    @Override
    public void analyze(Analyzer analyzer) throws AnalysisException, InternalException {
        super.analyze(analyzer);
        FeNameFormat.checkRoleName(role, false /* can not be superuser */);
        role = ClusterNamespace.getFullName(analyzer.getClusterName(), role);
    }

    @Override
    public String toSql() {
        return "DROP ROLE " + role;
    }
}
