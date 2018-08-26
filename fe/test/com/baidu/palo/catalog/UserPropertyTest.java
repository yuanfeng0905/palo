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

package com.baidu.palo.catalog;

import com.baidu.palo.analysis.SetUserPropertyVar;
import com.baidu.palo.analysis.SetVar;
import com.baidu.palo.common.DdlException;
import com.baidu.palo.common.FeConstants;
import com.baidu.palo.load.DppConfig;
import com.baidu.palo.mysql.privilege.UserProperty;

import com.google.common.collect.Lists;

import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.easymock.PowerMock;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

@RunWith(PowerMockRunner.class)
@PrepareForTest({ Catalog.class })
public class UserPropertyTest {
    @Test
    public void testNormal() throws IOException, DdlException {
        // mock catalog
        PowerMock.mockStatic(Catalog.class);
        EasyMock.expect(Catalog.getCurrentCatalogJournalVersion()).andReturn(FeConstants.meta_version).anyTimes();
        PowerMock.replay(Catalog.class);

        UserProperty property = new UserProperty("root");
        property.getResource().updateGroupShare("low", 991);
        // To image
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        DataOutputStream outputStream = new DataOutputStream(byteStream);
        property.write(outputStream);
        outputStream.flush();
        DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(byteStream.toByteArray()));
        UserProperty newProperty = UserProperty.read(inputStream);

        Assert.assertEquals(991, newProperty.getResource().getShareByGroup().get("low").intValue());
    }

    @Test
    public void testUpdate() throws DdlException {
        List<SetVar> propertyList = Lists.newArrayList();
        propertyList.add(new SetUserPropertyVar("MAX_USER_CONNECTIONS", "100"));
        propertyList.add(new SetUserPropertyVar("resource.cpu_share", "101"));
        propertyList.add(new SetUserPropertyVar("quota.normal", "102"));
        propertyList.add(new SetUserPropertyVar("load_cluster.dpp-cluster.hadoop_palo_path", "/user/palo2"));
        propertyList.add(new SetUserPropertyVar("default_load_cluster", "dpp-cluster"));

        UserProperty userProperty = new UserProperty();
        userProperty.update(propertyList);
        Assert.assertEquals(100, userProperty.getMaxConn());
        Assert.assertEquals(101, userProperty.getResource().getResource().getByDesc("cpu_share"));
        Assert.assertEquals(102, userProperty.getResource().getShareByGroup().get("normal").intValue());
        Assert.assertEquals("/user/palo2", userProperty.getLoadClusterInfo("dpp-cluster").second.getPaloPath());
        Assert.assertEquals("dpp-cluster", userProperty.getDefaultLoadCluster());

        // fetch property
        List<List<String>> rows = userProperty.fetchProperty();
        for (List<String> row : rows) {
            String key = row.get(0);
            String value = row.get(1);

            if (key.equalsIgnoreCase("max_user_connections")) {
                Assert.assertEquals("100", value);
            } else if (key.equalsIgnoreCase("resource.cpu_share")) {
                Assert.assertEquals("101", value);
            } else if (key.equalsIgnoreCase("quota.normal")) {
                Assert.assertEquals("102", value);
            } else if (key.equalsIgnoreCase("load_cluster.dpp-cluster.hadoop_palo_path")) {
                Assert.assertEquals("/user/palo2", value);
            } else if (key.equalsIgnoreCase("default_load_cluster")) {
                Assert.assertEquals("dpp-cluster", value);
            }
        }

        // get cluster info
        DppConfig dppConfig = userProperty.getLoadClusterInfo("dpp-cluster").second;
        Assert.assertEquals(8070, dppConfig.getHttpPort());

        // set palo path null
        propertyList = Lists.newArrayList();
        propertyList.add(new SetUserPropertyVar("load_cluster.dpp-cluster.hadoop_palo_path", null));
        userProperty.update(propertyList);
        Assert.assertEquals(null, userProperty.getLoadClusterInfo("dpp-cluster").second.getPaloPath());

        // remove dpp-cluster
        propertyList = Lists.newArrayList();
        propertyList.add(new SetUserPropertyVar("load_cluster.dpp-cluster", null));
        Assert.assertEquals("dpp-cluster", userProperty.getDefaultLoadCluster());
        userProperty.update(propertyList);
        Assert.assertEquals(null, userProperty.getLoadClusterInfo("dpp-cluster").second);
        Assert.assertEquals(null, userProperty.getDefaultLoadCluster());
    }
}
