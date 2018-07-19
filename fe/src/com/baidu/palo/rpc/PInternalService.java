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

package com.baidu.palo.rpc;

import com.baidu.jprotobuf.pbrpc.ProtobufRPC;

import java.util.concurrent.Future;

public interface PInternalService {
    @ProtobufRPC(serviceName = "PInternalService", methodName = "exec_plan_fragment",
            attachmentHandler = ThriftClientAttachmentHandler.class, onceTalkTimeout = 2000)
    Future<PExecPlanFragmentResult> execPlanFragmentAsync(PExecPlanFragmentRequest request);

    @ProtobufRPC(serviceName = "PInternalService", methodName = "cancel_plan_fragment",
            onceTalkTimeout = 1000)
    Future<PCancelPlanFragmentResult> cancelPlanFragmentAsync(PCancelPlanFragmentRequest request);

    // we set timeout to 1 day, because now there is no way to give different timeout for each RPC call
    @ProtobufRPC(serviceName = "PInternalService", methodName = "fetch_data",
            attachmentHandler = ThriftClientAttachmentHandler.class, onceTalkTimeout = 86400000)
    Future<PFetchDataResult> fetchDataAsync(PFetchDataRequest request);
}
