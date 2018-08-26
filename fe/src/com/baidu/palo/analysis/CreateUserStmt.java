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
import com.baidu.palo.mysql.MysqlPassword;
import com.baidu.palo.mysql.privilege.PaloRole;
import com.baidu.palo.mysql.privilege.PrivPredicate;
import com.baidu.palo.qe.ConnectContext;

import com.google.common.base.Strings;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/*
 * We support the following create user stmt
 * 1. create user user@ip [identified by 'password']
 *      specify the user name at a certain ip(wildcard is accepted), with optional password.
 *      the user@ip must not exist in system
 *      
 * 2. create user user@[domain] [identified by 'password']
 *      specify the user name at a certain domain, with optional password.
 *      the user@[domain] must not exist in system
 *      the daemon thread will resolve this domain to user@ip format
 *      
 * 3. create user user@xx [identified by 'password'] role role_name
 *      not only create the specified user, but also grant all privs of the specified role to the user.
 */
public class CreateUserStmt extends DdlStmt {
    private static final Logger LOG = LogManager.getLogger(CreateUserStmt.class);

    private boolean ifNotExist;
    private UserIdentity userIdent;
    private String password;
    private byte[] scramblePassword;
    private boolean isPlain;
    private String role;

    public CreateUserStmt() {
    }

    public CreateUserStmt(UserDesc userDesc) {
        userIdent = userDesc.getUserIdent();
        password = userDesc.getPassword();
        isPlain = userDesc.isPlain();
    }

    public CreateUserStmt(boolean ifNotExist, UserDesc userDesc, String role) {
        this.ifNotExist = ifNotExist;
        userIdent = userDesc.getUserIdent();
        password = userDesc.getPassword();
        isPlain = userDesc.isPlain();
        this.role = role;
    }

    public boolean isIfNotExist() {
        return ifNotExist;
    }

    public boolean isSuperuser() {
        return role.equalsIgnoreCase(PaloRole.ADMIN_ROLE);
    }

    public boolean hasRole() {
        return role != null;
    }

    public String getQualifiedRole() {
        return role;
    }

    public byte[] getPassword() {
        return scramblePassword;
    }

    public UserIdentity getUserIdent() {
        return userIdent;
    }

    @Override
    public void analyze(Analyzer analyzer) throws AnalysisException, InternalException {
        super.analyze(analyzer);
        userIdent.analyze(analyzer.getClusterName());
        // convert plain password to hashed password
        if (!Strings.isNullOrEmpty(password)) {
            if (isPlain) {
                // convert plain password to scramble
                scramblePassword = MysqlPassword.makeScrambledPassword(password);
            } else {
                scramblePassword = MysqlPassword.checkPassword(password);
            }
        } else {
            scramblePassword = new byte[0];
        }

        if (role != null) {
            if (role.equalsIgnoreCase("SUPERUSER")) {
                // for forward compatibility
                role = PaloRole.ADMIN_ROLE;
            }
            FeNameFormat.checkRoleName(role, true /* can be admin */);
            role = ClusterNamespace.getFullName(analyzer.getClusterName(), role);
        }

        if (!Catalog.getCurrentCatalog().getAuth().checkGlobalPriv(ConnectContext.get(), PrivPredicate.GRANT)) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_SPECIFIC_ACCESS_DENIED_ERROR, "CREATE USER");
        }
    }

    @Override
    public String toSql() {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE USER ").append(userIdent);
        if (!Strings.isNullOrEmpty(password)) {
            if (isPlain) {
                sb.append(" IDENTIFIED BY '").append(password).append("'");
            } else {
                sb.append(" IDENTIFIED BY PASSWORD '").append(password).append("'");
            }
        }

        if (!Strings.isNullOrEmpty(role)) {
            sb.append(" DEFAULT ROLE '").append(role).append("'");

        }

        return sb.toString();
    }

    @Override
    public String toString() {
        return toSql();
    }
}
