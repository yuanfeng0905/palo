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

package com.baidu.palo.http.action;

import com.baidu.palo.catalog.Catalog;
import com.baidu.palo.common.Config;
import com.baidu.palo.ha.HAProtocol;
import com.baidu.palo.http.ActionController;
import com.baidu.palo.http.BaseRequest;
import com.baidu.palo.http.BaseResponse;
import com.baidu.palo.http.IllegalArgException;
import com.baidu.palo.persist.Storage;
import com.baidu.palo.system.Frontend;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Date;
import java.util.List;

import io.netty.handler.codec.http.HttpMethod;

public class HaAction extends WebBaseAction {
    
    public HaAction(ActionController controller) {
        super(controller);
    }
    
    public static void registerAction(ActionController controller) throws IllegalArgException {
        controller.registerHandler(HttpMethod.GET, "/ha", new HaAction(controller));
    }
    
    @Override
    public void executeGet(BaseRequest request, BaseResponse response) {
        getPageHeader(request, response.getContent());
        
        appendRoleInfo(response.getContent());
        appendJournalInfo(response.getContent());
        appendCanReadInfo(response.getContent());
        appendNodesInfo(response.getContent());
        appendImageInfo(response.getContent());
        appendDbNames(response.getContent());
        appendFe(response.getContent());
        appendRemovedFe(response.getContent());
        
        getPageFooter(response.getContent());
        writeResponse(request, response);
    }
    
    private void appendRoleInfo(StringBuilder buffer) {
        buffer.append("<h2>Frontend Role</h2>");
        buffer.append("<pre>");
        buffer.append("<p>" + Catalog.getInstance().getFeType() + "</p>");
        buffer.append("</pre>");
    }
    
    private void appendJournalInfo(StringBuilder buffer) {
        buffer.append("<h2>Current Journal Id</h2>");
        buffer.append("<pre>");
        if (Catalog.getInstance().isMaster()) {
            buffer.append("<p>" + Catalog.getInstance().getEditLog().getMaxJournalId() + "</p>");
        } else {
            buffer.append("<p>" + Catalog.getInstance().getReplayedJournalId() + "</p>");
        }
        buffer.append("</pre>");
    }
    
    private void appendNodesInfo(StringBuilder buffer) {
        HAProtocol haProtocol = Catalog.getInstance().getHaProtocol();
        if (haProtocol == null) {
            return;
        }
        List<InetSocketAddress> electableNodes = haProtocol.getElectableNodes(true);
        if (electableNodes == null) {
            return;
        }
        buffer.append("<h2>Electable nodes</h2>");
        buffer.append("<pre>");
        for (InetSocketAddress node : electableNodes) {
            buffer.append("<p>" + node.getAddress() + "</p>");
            
        }
        buffer.append("</pre>");
        
        List<InetSocketAddress> observerNodes = haProtocol.getObserverNodes();
        if (observerNodes == null) {
            return;
        }
        buffer.append("<h2>Observer nodes</h2>");
        buffer.append("<pre>");
        for (InetSocketAddress node : observerNodes) {
            buffer.append("<p>" + node.getHostString() + "</p>");
        }
        buffer.append("</pre>");
    }
    
    private void appendCanReadInfo(StringBuilder buffer) {
        buffer.append("<h2>Can Read</h2>");
        buffer.append("<pre>");
        buffer.append("<p>" + Catalog.getInstance().canRead() + "</p>");
        
        buffer.append("</pre>");
    }
    
    private void appendImageInfo(StringBuilder buffer) {
        try {
            Storage storage = new Storage(Config.meta_dir + "/image");
            buffer.append("<h2>Checkpoint Info</h2>");
            buffer.append("<pre>");
            buffer.append("<p>last checkpoint version:" + storage.getImageSeq() + "</p>");
            long lastCheckpointTime = storage.getCurrentImageFile().lastModified();
            Date date = new Date(lastCheckpointTime);
            buffer.append("<p>last checkpoint time: " + date + "</p>");
            buffer.append("</pre>");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private void appendDbNames(StringBuilder buffer) {
        List<Long> names = Catalog.getInstance().getEditLog().getDatabaseNames();
        if (names == null) {
            return;
        }
        
        String msg = "";
        for (long name : names) {
            msg += name + " ";
        }
        buffer.append("<h2>Database names</h2>");
        buffer.append("<pre>");
        buffer.append("<p>" + msg + "</p>");
        buffer.append("</pre>");
    }
    
    private void appendFe(StringBuilder buffer) {
        List<Frontend> fes = Catalog.getInstance().getFrontends(null /* all */);
        if (fes == null) {
            return;
        }
        
        buffer.append("<h2>Allowed Frontends</h2>");
        buffer.append("<pre>");
        for (Frontend fe : fes) {
            buffer.append("<p>" + fe.toString() + "</p>");
        }
        buffer.append("</pre>");
    }
    
    private void appendRemovedFe(StringBuilder buffer) {
        List<String> feNames = Catalog.getInstance().getRemovedFrontendNames();
        buffer.append("<h2>Removed Frontends</h2>");
        buffer.append("<pre>");
        for (String feName : feNames) {
            buffer.append("<p>" + feName + "</p>");
        }
        buffer.append("</pre>");
    }
    
}