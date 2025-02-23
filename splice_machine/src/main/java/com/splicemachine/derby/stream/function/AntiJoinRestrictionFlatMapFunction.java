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

import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.derby.iapi.sql.execute.SpliceOperation;
import com.splicemachine.derby.impl.sql.execute.operations.JoinUtils;
import com.splicemachine.derby.impl.sql.execute.operations.LocatedRow;
import com.splicemachine.derby.stream.iapi.OperationContext;
import org.apache.commons.collections.iterators.SingletonIterator;
import scala.Tuple2;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Collections;
import java.util.Iterator;

/**
 *
 */
@NotThreadSafe
public class AntiJoinRestrictionFlatMapFunction<Op extends SpliceOperation> extends SpliceJoinFlatMapFunction<Op,Tuple2<LocatedRow,Iterable<LocatedRow>>,LocatedRow> {
    protected LocatedRow leftRow;
    protected LocatedRow rightRow;
    protected ExecRow mergedRow;
    public AntiJoinRestrictionFlatMapFunction() {
        super();
    }

    public AntiJoinRestrictionFlatMapFunction(OperationContext<Op> operationContext) {
        super(operationContext);
    }

    @Override
    public Iterator<LocatedRow> call(Tuple2<LocatedRow, Iterable<LocatedRow>> tuple) throws Exception {
        checkInit();
        leftRow = tuple._1();
        Iterator<LocatedRow> it = tuple._2.iterator();
        while (it.hasNext()) {
            rightRow = it.next();
            mergedRow = JoinUtils.getMergedRow(leftRow.getRow(),
                    rightRow.getRow(), op.wasRightOuterJoin,
                    executionFactory.getValueRow(numberOfColumns));
            op.setCurrentRow(mergedRow);
            if (op.getRestriction().apply(mergedRow)) { // Has Row, abandon
                operationContext.recordFilter();
                return Collections.<LocatedRow>emptyList().iterator();
            }
        }
        // No Rows Matched...
        LocatedRow returnRow = new LocatedRow(leftRow.getRowLocation(),JoinUtils.getMergedRow(leftRow.getRow(),
                op.getEmptyRow(), op.wasRightOuterJoin,
                executionFactory.getValueRow(numberOfColumns)));
        op.setCurrentLocatedRow(returnRow);
        return new SingletonIterator(returnRow);
    }
}
