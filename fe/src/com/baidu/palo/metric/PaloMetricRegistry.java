// Modifications copyright (C) 2018, Baidu.com, Inc.
// Copyright 2018 The Apache Software Foundation

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

package com.baidu.palo.metric;

import com.google.common.collect.Lists;

import java.util.List;
import java.util.stream.Collectors;

public class PaloMetricRegistry {

    private List<PaloMetric> paloMetrics = Lists.newArrayList();

    public PaloMetricRegistry() {

    }

    public synchronized void addPaloMetrics(PaloMetric paloMetric) {
        paloMetrics.add(paloMetric);
    }

    public synchronized List<PaloMetric> getPaloMetrics() {
        return Lists.newArrayList(paloMetrics);
    }

    public synchronized void removeMetrics(String name) {
        paloMetrics = paloMetrics.stream().filter(m -> !(m.getName().equals(name))).collect(Collectors.toList());
    }
}
