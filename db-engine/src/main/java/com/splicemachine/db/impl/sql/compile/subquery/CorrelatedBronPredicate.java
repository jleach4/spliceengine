/*
 * Apache Derby is a subproject of the Apache DB project, and is licensed under
 * the Apache License, Version 2.0 (the "License"); you may not use these files
 * except in compliance with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * Splice Machine, Inc. has modified this file.
 *
 * All Splice Machine modifications are Copyright 2012 - 2016 Splice Machine, Inc.,
 * and are licensed to you under the License; you may not use this file except in
 * compliance with the License.
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 */

package com.splicemachine.db.impl.sql.compile.subquery;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.impl.ast.PredicateUtils;
import com.splicemachine.db.impl.ast.RSUtils;
import com.splicemachine.db.impl.sql.compile.BinaryRelationalOperatorNode;
import com.splicemachine.db.impl.sql.compile.ColumnReference;
import org.spark_project.guava.base.Predicate;

/**
 * A predicate that evaluates to true if a given BinaryRelationalOperatorNode has this shape:
 * <pre>
 * BRON(ANY-OP)
 *  /  \
 * CR  NoColumnReferenceSubTree
 * </pre>
 *
 * Where the CR can be on either side but must have nesting level equal to that specified in the constructor. We are
 * usually looking for a level different than where the CR is found, thus it is a correlated column reference. Since it
 * is being compared to a subtree with no column references this predicate evaluates to true for predicates that can be
 * moved to the query with the specified nesting level.
 *
 * EXAMPLE:
 *
 * select * from A where exists( select 1 from B where a1 = 10 );
 *
 * In this query 'a1=10' can be moved to the outer query (for EXISTS subqueries, but not for NOT-EXISTS subqueries).
 */
public class CorrelatedBronPredicate implements Predicate<BinaryRelationalOperatorNode> {

    private int sourceLevel;

    public CorrelatedBronPredicate(int sourceLevel) {
        this.sourceLevel = sourceLevel;
    }

    @Override
    public boolean apply(BinaryRelationalOperatorNode bron) {
        try {
            return test(bron);
        } catch (StandardException e) {
            /* not expected, programmer error */
            throw new IllegalStateException(e);
        }
    }

    private boolean test(BinaryRelationalOperatorNode bron) throws StandardException {
        return (
                PredicateUtils.isLeftColRef(bron, sourceLevel)
                        &&
                        RSUtils.collectNodes(bron.getRightOperand(), ColumnReference.class).isEmpty()
        )
                ||
                (
                        PredicateUtils.isRightColRef(bron, sourceLevel)
                                &&
                                RSUtils.collectNodes(bron.getLeftOperand(), ColumnReference.class).isEmpty()
                );
    }
}
