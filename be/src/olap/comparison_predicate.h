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

#ifndef BDG_PALO_BE_SRC_OLAP_COMPARISON_PREDICATE_H
#define BDG_PALO_BE_SRC_OLAP_COMPARISON_PREDICATE_H

#include <stdint.h>
#include "olap/column_predicate.h"

namespace palo {

class VectorizedRowBatch;

#define COMPARISON_PRED_CLASS_DEFINE(CLASS) \
    template <class type> \
    class CLASS : public ColumnPredicate { \
    public: \
        CLASS(int column_id, const type& value); \
        virtual ~CLASS() { }  \
        virtual void evaluate(VectorizedRowBatch* batch) const override; \
    private: \
        int32_t _column_id; \
        type _value; \
    }; \

COMPARISON_PRED_CLASS_DEFINE(EqualPredicate)
COMPARISON_PRED_CLASS_DEFINE(NotEqualPredicate)
COMPARISON_PRED_CLASS_DEFINE(LessPredicate)
COMPARISON_PRED_CLASS_DEFINE(LessEqualPredicate)
COMPARISON_PRED_CLASS_DEFINE(GreaterPredicate)
COMPARISON_PRED_CLASS_DEFINE(GreaterEqualPredicate)

} //namespace palo

#endif //BDG_PALO_BE_SRC_OLAP_COMPARISON_PREDICATE_H
