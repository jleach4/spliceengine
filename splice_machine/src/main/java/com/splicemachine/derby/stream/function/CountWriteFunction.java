/*
 * Copyright 2012 - 2016 Splice Machine, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.splicemachine.derby.stream.function;

import com.splicemachine.derby.stream.iapi.OperationContext;

/**
 *
 * Class counts the writes that occur during
 * a write to an external table.
 *
 *
 */
public class CountWriteFunction extends SpliceFunction {
    public CountWriteFunction() {
    }

    public CountWriteFunction(OperationContext<?> operationContext) {
        super(operationContext);
    }

    @Override
    public Object call(Object o) throws Exception {
        operationContext.recordWrite();
        return o;
    }
}