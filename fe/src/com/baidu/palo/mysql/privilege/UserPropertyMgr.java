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

package com.baidu.palo.mysql.privilege;

import com.baidu.palo.analysis.SetUserPropertyStmt;
import com.baidu.palo.analysis.TablePattern;
import com.baidu.palo.analysis.UserIdentity;
import com.baidu.palo.catalog.Catalog;
import com.baidu.palo.common.AnalysisException;
import com.baidu.palo.common.Config;
import com.baidu.palo.common.DdlException;
import com.baidu.palo.common.FeMetaVersion;
import com.baidu.palo.common.Pair;
import com.baidu.palo.common.io.Writable;
import com.baidu.palo.common.publish.FixedTimePublisher;
import com.baidu.palo.common.publish.Listener;
import com.baidu.palo.common.publish.TopicUpdate;
import com.baidu.palo.load.DppConfig;
import com.baidu.palo.thrift.TAgentServiceVersion;
import com.baidu.palo.thrift.TFetchResourceResult;
import com.baidu.palo.thrift.TTopicItem;
import com.baidu.palo.thrift.TTopicType;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public class UserPropertyMgr implements Writable {
    private static final Logger LOG = LogManager.getLogger(UserPropertyMgr.class);

    protected Map<String, UserProperty> propertyMap = Maps.newHashMap();
    public static final String ROOT_USER = "root";
    public static final String SYSTEM_RESOURCE_USER = "system";
    private AtomicLong resourceVersion = new AtomicLong(0);

    public UserPropertyMgr() {
    }

    // Register callback to FixedTimePublisher
    public void setUp() {
        FixedTimePublisher.getInstance().register(new FixedTimePublisher.Callback() {
            @Override
            public TopicUpdate getTopicUpdate() {
                TopicUpdate update = new TopicUpdate(TTopicType.RESOURCE);
                TTopicItem tTopicItem = new TTopicItem("version");
                tTopicItem.setInt_value(resourceVersion.get());
                update.addUpdates(tTopicItem);
                return update;
            }

            @Override
            public Listener getListener() {
                return null;
            }
        }, Config.meta_resource_publish_interval_ms);
    }

    public void addUserResource(String qualifiedUser, boolean isSystemUser) {
        UserProperty property = propertyMap.get(qualifiedUser);
        if (property != null) {
            return;
        }

        property = new UserProperty(qualifiedUser);

        // set user properties
        try {
            if (isSystemUser) {
                setSystemUserDefaultResource(property);
            } else {
                setNormalUserDefaultResource(property);
            }
        } catch (DdlException e) {
            // this should not happen, because the value is set by us!!
        }

        propertyMap.put(qualifiedUser, property);
        resourceVersion.incrementAndGet();
    }

    /*
     * Try to grant privs to whitelist of the user. 
     */
    public void addOrGrantWhiteList(UserIdentity userIdentity, Map<TablePattern, PrivBitSet> privsMap,
            byte[] password, boolean errOnExist, boolean errOnNonExist) throws DdlException {
        Preconditions.checkArgument(userIdentity.isDomain());
        UserProperty property = propertyMap.get(userIdentity.getQualifiedUser());
        if (property == null) {
            if (errOnNonExist) {
                throw new DdlException("user " + userIdentity + " does not exist");
            }
            property = new UserProperty(userIdentity.getQualifiedUser());
        }

        property.addOrGrantWhiteList(userIdentity.getHost(), privsMap, password, errOnExist);
        // update propertyMap after addOrGrantWhiteList, cause addOrGrantWhiteList may throw exception
        propertyMap.put(userIdentity.getQualifiedUser(), property);
    }

    public void revokePrivsFromWhiteList(UserIdentity userIdentity, Map<TablePattern, PrivBitSet> privsMap,
            boolean errOnNonExist) throws DdlException {
        Preconditions.checkArgument(userIdentity.isDomain());
        UserProperty property = propertyMap.get(userIdentity.getQualifiedUser());
        if (property == null && errOnNonExist) {
            throw new DdlException("User " + userIdentity.getQualifiedUser() + " does not exist");
        }

        property.revokePrivsFromWhiteList(userIdentity.getHost(), privsMap, errOnNonExist);
    }

    public void dropUser(String qualifiedUser) {
        propertyMap.remove(qualifiedUser);
        resourceVersion.incrementAndGet();
    }

    public void setPassword(UserIdentity userIdent, byte[] password) throws DdlException {
        Preconditions.checkArgument(userIdent.isDomain());
        UserProperty property = propertyMap.get(userIdent.getQualifiedUser());
        if (property == null) {
            throw new DdlException("user " + userIdent.getQualifiedUser() + " does not exist");
        }

        if (property.getWhiteList().containsDomain(userIdent.getHost())) {
            throw new DdlException("user " + userIdent + " does not exist");
        }

        property.getWhiteList().setPassword(password);
    }

    public void updateUserProperty(SetUserPropertyStmt stmt) throws DdlException {
        UserProperty property = propertyMap.get(stmt.getUser());
        if (property == null) {
            throw new DdlException("Unknown user(" + stmt.getUser() + ")");
        }

        property.update(stmt.getPropertyList());
    }

    public long getMaxConn(String qualifiedUser) {
        UserProperty existProperty = propertyMap.get(qualifiedUser);
        if (existProperty == null) {
            return 0;
        }
        return existProperty.getMaxConn();
    }

    public int getPropertyMapSize() {
        return propertyMap.size();
    }

    private void setSystemUserDefaultResource(UserProperty user) throws DdlException {
        UserResource userResource = user.getResource();
        userResource.updateResource("CPU_SHARE", 100);
        userResource.updateResource("IO_SHARE", 100);
        userResource.updateResource("SSD_READ_MBPS", 30);
        userResource.updateResource("SSD_WRITE_MBPS", 30);
        userResource.updateResource("HDD_READ_MBPS", 30);
        userResource.updateResource("HDD_WRITE_MBPS", 30);
    }

    private void setNormalUserDefaultResource(UserProperty user) throws DdlException {
        UserResource userResource = user.getResource();
        userResource.updateResource("CPU_SHARE", 1000);
        userResource.updateResource("IO_SHARE", 1000);
        userResource.updateResource("SSD_READ_IOPS", 1000);
        userResource.updateResource("HDD_READ_IOPS", 80);
        userResource.updateResource("SSD_READ_MBPS", 30);
        userResource.updateResource("HDD_READ_MBPS", 30);
    }

    public TFetchResourceResult toResourceThrift() {
        TFetchResourceResult tResult = new TFetchResourceResult();
        tResult.setProtocolVersion(TAgentServiceVersion.V1);
        tResult.setResourceVersion(resourceVersion.get());

        for (Map.Entry<String, UserProperty> entry : propertyMap.entrySet()) {
            tResult.putToResourceByUser(entry.getKey(), entry.getValue().getResource().toThrift());
        }

        return tResult;
    }

    public Pair<String, DppConfig> getLoadClusterInfo(String qualifiedUser, String cluster) throws DdlException {
        Pair<String, DppConfig> loadClusterInfo = null;

        if (!propertyMap.containsKey(qualifiedUser)) {
            throw new DdlException("User " + qualifiedUser + " does not exist");
        }

        UserProperty property = propertyMap.get(qualifiedUser);
        loadClusterInfo = property.getLoadClusterInfo(cluster);
        return loadClusterInfo;
    }

    public List<List<String>> fetchUserProperty(String qualifiedUser) throws AnalysisException {
        if (!propertyMap.containsKey(qualifiedUser)) {
            throw new AnalysisException("User " + qualifiedUser + " does not exist");
        }

        UserProperty property = propertyMap.get(qualifiedUser);
        return property.fetchProperty();
    }

    public void getCopiedWhiteList(Map<String, Set<String>> userMap) {
        LOG.debug("get property map: {}", propertyMap);
        for (Map.Entry<String, UserProperty> entry : propertyMap.entrySet()) {
            Set<String> domains = entry.getValue().getWhiteList().getAllDomains();
            if (domains.isEmpty()) {
                continue;
            }
            userMap.put(entry.getKey(), domains);
        }
    }

    public void updateResolovedIps(String qualifiedUser, String domain, Set<String> resolvedIPs) {
        if (!propertyMap.containsKey(qualifiedUser)) {
            return;
        }

        UserProperty property = propertyMap.get(qualifiedUser);
        property.getWhiteList().updateResolovedIps(qualifiedUser, domain, resolvedIPs);
    }

    public UserProperty getUserProperty(String qualifiedUserName) {
        return propertyMap.get(qualifiedUserName);
    }

    @Deprecated
    public void addUserPropertyUnchecked(UserProperty userProperty) {
        Preconditions.checkState(Catalog.getCurrentCatalogJournalVersion() < FeMetaVersion.VERSION_43);
        propertyMap.put(userProperty.getQualifiedUser(), userProperty);
    }
    
    public void transform(PaloAuth auth) {
        Preconditions.checkState(Catalog.getCurrentCatalogJournalVersion() < FeMetaVersion.VERSION_43);
        for (UserProperty userProperty : propertyMap.values()) {
            auth.transformAndAddOldUserProperty(userProperty);
        }
    }

    public void getUserAuthInfo(List<List<String>> userAuthInfos, UserIdentity specifiedUserIdent) {
        for (UserProperty property : propertyMap.values()) {
            if (specifiedUserIdent != null
                    && !property.getQualifiedUser().equals(specifiedUserIdent.getQualifiedUser())) {
                continue;
            }
            property.getAuthInfo(userAuthInfos);
        }
    }

    public static UserPropertyMgr read(DataInput in) throws IOException {
        UserPropertyMgr userPropertyMgr = new UserPropertyMgr();
        userPropertyMgr.readFields(in);
        return userPropertyMgr;
    }

    @Override
    public void write(DataOutput out) throws IOException {
        out.writeInt(propertyMap.size());
        for (Map.Entry<String, UserProperty> entry : propertyMap.entrySet()) {
            entry.getValue().write(out);
        }
        // Write resource version
        out.writeLong(resourceVersion.get());
    }

    @Override
    public void readFields(DataInput in) throws IOException {
        int size = in.readInt();
        for (int i = 0; i < size; ++i) {
            UserProperty userProperty = UserProperty.read(in);
            propertyMap.put(userProperty.getQualifiedUser(), userProperty);
            LOG.debug("read user property: {}: {}", userProperty.getQualifiedUser(), userProperty);
        }
        // Read resource
        resourceVersion = new AtomicLong(in.readLong());
    }
}

