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

package com.baidu.palo.http.rest;

import com.baidu.palo.common.DdlException;
import com.baidu.palo.http.ActionController;
import com.baidu.palo.http.BaseRequest;
import com.baidu.palo.http.BaseResponse;
import com.baidu.palo.http.IllegalArgException;
import com.baidu.palo.load.Load;
import com.baidu.palo.mysql.privilege.PrivPredicate;

import com.google.common.base.Strings;

import io.netty.handler.codec.http.HttpMethod;

// Get load information of one load job
public class GetLoadInfoAction extends RestBaseAction {
    private static final String DB_KEY = "db";
    private static final String LABEL_KEY = "label";

    public GetLoadInfoAction(ActionController controller) {
        super(controller);
    }

    public static void registerAction(ActionController controller)
            throws IllegalArgException {
        GetLoadInfoAction action = new GetLoadInfoAction(controller);
        controller.registerHandler(HttpMethod.GET, "/api/{db}/_load_info", action);
    }

    @Override
    public void executeWithoutPassword(AuthorizationInfo authInfo, BaseRequest request, BaseResponse response)
            throws DdlException {

        Load.JobInfo info = new Load.JobInfo(request.getSingleParameter(DB_KEY),
                                             request.getSingleParameter(LABEL_KEY),
                                             authInfo.cluster);
        if (Strings.isNullOrEmpty(info.dbName)) {
            throw new DdlException("No database selected");
        }
        if (Strings.isNullOrEmpty(info.label)) {
            throw new DdlException("No label selected");
        }
        if (Strings.isNullOrEmpty(info.clusterName)) {
            throw new DdlException("No cluster name selected");
        }

        if (redirectToMaster(request, response)) {
            return;
        }
        catalog.getLoadInstance().getJobInfo(info);

        if (info.tblNames.isEmpty()) {
            checkDbAuth(authInfo, info.dbName, PrivPredicate.LOAD);
        } else {
            for (String tblName : info.tblNames) {
                checkTblAuth(authInfo, info.dbName, tblName, PrivPredicate.LOAD);
            }
        }

        sendResult(request, response, new Result(info));
    }

    private static class Result extends RestBaseResult {
        private Load.JobInfo jobInfo;

        public Result(Load.JobInfo info) {
            jobInfo = info;
        }
    }
}
