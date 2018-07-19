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

#ifndef BDG_PALO_BE_SRC_COMMON_UTIL_HTTP_METHOD_H
#define BDG_PALO_BE_SRC_COMMON_UTIL_HTTP_METHOD_H

#include <string>

#include <event2/http.h>

namespace palo {

// Http method enumerate
enum HttpMethod {
    GET,
    PUT,
    POST,
    DELETE,
    HEAD,
    OPTIONS,
    UNKNOWN
};

// Convert string to HttpMethod
HttpMethod to_http_method(const char* method);

inline HttpMethod to_http_method(evhttp_cmd_type type) {
    switch (type) {
    case EVHTTP_REQ_GET:
        return HttpMethod::GET;
    case EVHTTP_REQ_POST:
        return HttpMethod::POST;
    case EVHTTP_REQ_HEAD:
        return HttpMethod::HEAD;
    case EVHTTP_REQ_PUT:
        return HttpMethod::PUT;
    case EVHTTP_REQ_DELETE:
        return HttpMethod::DELETE;
    case EVHTTP_REQ_OPTIONS:
        return HttpMethod::OPTIONS;
    default:
        return HttpMethod::UNKNOWN;
    }
    return HttpMethod::UNKNOWN;
}

std::string to_method_desc(const HttpMethod& method);

}
#endif
