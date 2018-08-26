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

import com.baidu.palo.common.AnalysisException;
import com.baidu.palo.common.proc.ProcNodeInterface;
import com.baidu.palo.common.proc.ProcResult;
import com.baidu.palo.common.proc.ProcService;
import com.baidu.palo.http.ActionController;
import com.baidu.palo.http.BaseRequest;
import com.baidu.palo.http.BaseResponse;
import com.baidu.palo.http.IllegalArgException;
import com.baidu.palo.http.UnauthorizedException;
import com.baidu.palo.mysql.privilege.PrivPredicate;

import com.google.common.base.Strings;
import com.google.gson.Gson;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

import io.netty.handler.codec.http.HttpMethod;

// Format:
//   http://username:password@10.73.150.30:8138/api/show_proc?path=/
public class ShowProcAction extends RestBaseAction {
    private static final Logger LOG = LogManager.getLogger(ShowProcAction.class);

    public ShowProcAction(ActionController controller) {
        super(controller);
    }

    public static void registerAction(ActionController controller) throws IllegalArgException {
        controller.registerHandler(HttpMethod.GET, "/api/show_proc", new ShowProcAction(controller));
    }

    @Override
    public void execute(BaseRequest request, BaseResponse response) {
        // check authority
        try {
            AuthorizationInfo authInfo = getAuthorizationInfo(request);
            checkGlobalAuth(authInfo, PrivPredicate.ADMIN);
        } catch (UnauthorizedException e) {
            response.appendContent("Authentication Failed. " + e.getMessage());
            sendResult(request, response);
            return;
        }

        String path = request.getSingleParameter("path");
        ProcNodeInterface procNode = null;
        ProcService instance = ProcService.getInstance();
        try {
            if (Strings.isNullOrEmpty(path)) {
                procNode = instance.open("/");
            } else {
                procNode = instance.open(path);
            }
        } catch (AnalysisException e) {
            LOG.warn(e.getMessage());
            response.getContent().append("[]");
        }

        if (procNode != null) {
            ProcResult result;
            try {
                result = procNode.fetchResult();
                List<List<String>> rows = result.getRows();

                Gson gson = new Gson();
                response.setContentType("application/json");
                response.getContent().append(gson.toJson(rows));
            } catch (AnalysisException e) {
                LOG.warn(e.getMessage());
                response.getContent().append("[]");
            }
        }
        sendResult(request, response);
    }
}
