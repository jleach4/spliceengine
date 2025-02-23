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

import com.splicemachine.derby.impl.sql.execute.operations.JoinOperation;
import com.splicemachine.derby.impl.sql.execute.operations.LocatedRow;
import com.splicemachine.derby.stream.iapi.OperationContext;

import javax.annotation.Nullable;

/**
 * Created by jleach on 4/22/15.
 */
public class JoinRestrictionPredicateFunction extends SplicePredicateFunction<JoinOperation,LocatedRow> {
    public JoinRestrictionPredicateFunction() {
        super();
    }

    public JoinRestrictionPredicateFunction(OperationContext<JoinOperation> operationContext) {
        super(operationContext);
    }

    @Override
    public boolean apply(@Nullable LocatedRow locatedRow) {
        JoinOperation joinOp = operationContext.getOperation();
        try {
            if (!joinOp.getRestriction().apply(locatedRow.getRow())) {
                operationContext.recordFilter();
                return false;
            }
            joinOp.setCurrentLocatedRow(locatedRow);
            return true;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
