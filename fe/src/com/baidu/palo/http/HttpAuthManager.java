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

package com.baidu.palo.http;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.util.concurrent.TimeUnit;

// We simulate a simplified session here: only store user-name of clients who already logged in,
// and we only have a default admin user for now.
public final class HttpAuthManager {
    private static long SESSION_EXPIRE_TIME = 2; // hour
    private static long SESSION_MAX_SIZE = 100; // avoid to store too many

    private static HttpAuthManager instance = new HttpAuthManager();

    // session_id => username
    private Cache<String, String> authSessions =  CacheBuilder.newBuilder()
            .maximumSize(SESSION_MAX_SIZE)
            .expireAfterAccess(SESSION_EXPIRE_TIME, TimeUnit.HOURS)
            .build();

    private HttpAuthManager() {
        // do nothing
    }

    public static HttpAuthManager getInstance() {
        return instance;
    }

    public String getUsername(String sessionId) {
        return authSessions.getIfPresent(sessionId);
    }

    public void addClient(String key, String value) {
        authSessions.put(key, value);
    }

    public Cache<String, String> getAuthSessions() {
        return authSessions;
    }
}
