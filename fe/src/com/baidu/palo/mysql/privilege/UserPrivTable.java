// Copyright (c) 2018, Baidu.com, Inc. All Rights Reserved

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

package com.baidu.palo.mysql.privilege;

import com.baidu.palo.analysis.UserIdentity;
import com.baidu.palo.common.DdlException;
import com.baidu.palo.common.io.Text;
import com.baidu.palo.mysql.MysqlPassword;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataOutput;
import java.io.IOException;

public class UserPrivTable extends PrivTable {
    private static final Logger LOG = LogManager.getLogger(UserPrivTable.class);

    public UserPrivTable() {
    }

    public void getPrivs(String host, String user, PrivBitSet savedPrivs) {
        GlobalPrivEntry matchedEntry = null;
        for (PrivEntry entry : entries) {
            GlobalPrivEntry globalPrivEntry = (GlobalPrivEntry) entry;

            // check host
            if (!globalPrivEntry.isAnyHost() && !globalPrivEntry.getHostPattern().match(host)) {
                continue;
            }

            // check user
            if (!globalPrivEntry.isAnyUser() && !globalPrivEntry.getUserPattern().match(user)) {
                continue;
            }

            matchedEntry = globalPrivEntry;
            break;
        }
        if (matchedEntry == null) {
            return;
        }

        savedPrivs.or(matchedEntry.getPrivSet());
    }

    // validate the connection by host, user and password.
    // return true if this connection is valid, and 'savedPrivs' save all global privs got from user table.
    public boolean checkPassword(String remoteUser, String remoteHost, byte[] remotePasswd, byte[] randomString) {
        LOG.debug("check password for user: {} from {}, password: {}, random string: {}",
                  remoteUser, remoteHost, remotePasswd, randomString);

        // TODO(cmy): for now, we check user table from first entry to last,
        // This may not efficient, but works.
        for (PrivEntry entry : entries) {
            GlobalPrivEntry globalPrivEntry = (GlobalPrivEntry) entry;

            // check host
            if (!globalPrivEntry.isAnyHost() && !globalPrivEntry.getHostPattern().match(remoteHost)) {
                continue;
            }

            // check user
            if (!globalPrivEntry.isAnyUser() && !globalPrivEntry.getUserPattern().match(remoteUser)) {
                continue;
            }

            // check password
            byte[] saltPassword = MysqlPassword.getSaltFromPassword(globalPrivEntry.getPassword());
            // when the length of password is zero, the user has no password
            if ((remotePasswd.length == saltPassword.length)
                    && (remotePasswd.length == 0
                            || MysqlPassword.checkScramble(remotePasswd, randomString, saltPassword))) {
                // found the matched entry
                return true;
            } else {
                continue;
            }
        }

        return false;
    }

    public boolean checkPlainPassword(String remoteUser, String remoteHost, String remotePasswd) {
        for (PrivEntry entry : entries) {
            GlobalPrivEntry globalPrivEntry = (GlobalPrivEntry) entry;

            // check host
            if (!globalPrivEntry.isAnyHost() && !globalPrivEntry.getHostPattern().match(remoteHost)) {
                continue;
            }

            // check user
            if (!globalPrivEntry.isAnyUser() && !globalPrivEntry.getUserPattern().match(remoteUser)) {
                continue;
            }

            if (MysqlPassword.checkPlainPass(globalPrivEntry.getPassword(), remotePasswd)) {
                return true;
            }
        }

        return false;
    }

    public void setPassword(GlobalPrivEntry passwdEntry, boolean addIfNotExist) throws DdlException {
        GlobalPrivEntry existingEntry = (GlobalPrivEntry) getExistingEntry(passwdEntry);
        if (existingEntry == null) {
            if (!addIfNotExist) {
                throw new DdlException("User " + passwdEntry.getUserIdent() + " does not exist");
            }
            existingEntry = passwdEntry;
            addEntry(existingEntry, false /* err on exist */, false /* err on non exist */);
        } else {
            if (existingEntry.isSetByDomainResolver() && !passwdEntry.isSetByDomainResolver()) {
                LOG.info("cannot set password, existing entry is set by resolver: {}", existingEntry);
                throw new DdlException("Cannot set password, existing entry is set by resolver");
            } else if (!existingEntry.isSetByDomainResolver() && passwdEntry.isSetByDomainResolver()) {
                LOG.info("Cannot set password, existing entry is not set by resolver: {}", existingEntry);
                throw new DdlException("Cannot set password, existing entry is not set by resolver");
            }
        }

        existingEntry.setPassword(passwdEntry.getPassword());
    }

    public boolean doesUserExist(UserIdentity userIdent, boolean exactMatch) {
        for (PrivEntry privEntry : entries) {
            if (privEntry.match(userIdent, exactMatch)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void write(DataOutput out) throws IOException {
        if (!isClassNameWrote) {
            String className = UserPrivTable.class.getCanonicalName();
            Text.writeString(out, className);
            isClassNameWrote = true;
        }

        super.write(out);
    }
}
