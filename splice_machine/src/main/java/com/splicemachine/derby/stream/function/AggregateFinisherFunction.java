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

import com.splicemachine.derby.impl.sql.execute.operations.GenericAggregateOperation;
import com.splicemachine.derby.impl.sql.execute.operations.GroupedAggregateOperation;
import com.splicemachine.derby.impl.sql.execute.operations.LocatedRow;
import com.splicemachine.derby.impl.sql.execute.operations.framework.SpliceGenericAggregator;
import com.splicemachine.derby.stream.iapi.OperationContext;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

/**
 * Created by jleach on 4/24/15.
 */
public class AggregateFinisherFunction extends SpliceFunction<GroupedAggregateOperation, LocatedRow, LocatedRow> {
        protected SpliceGenericAggregator[] aggregates;
        protected boolean initialized;
        protected GenericAggregateOperation op;
        public AggregateFinisherFunction() {
            super();
        }

        public AggregateFinisherFunction(OperationContext<GroupedAggregateOperation> operationContext) {
            super(operationContext);
        }
        @Override
        public void writeExternal(ObjectOutput out) throws IOException {
            super.writeExternal(out);
        }

        @Override
        public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
            super.readExternal(in);
        }
        @Override
        public LocatedRow call(LocatedRow locatedRow) throws Exception {
            if (!initialized) {
                op =getOperation();
                aggregates = op.aggregates;
                initialized = true;
            }
            for(SpliceGenericAggregator aggregator:aggregates){
                if (!aggregator.isInitialized(locatedRow.getRow())) {
                    aggregator.initializeAndAccumulateIfNeeded(locatedRow.getRow(), locatedRow.getRow());
                }
                aggregator.finish(locatedRow.getRow());
            }
            op.setCurrentLocatedRow(locatedRow);
            operationContext.recordProduced();
            return locatedRow;
        }
}
