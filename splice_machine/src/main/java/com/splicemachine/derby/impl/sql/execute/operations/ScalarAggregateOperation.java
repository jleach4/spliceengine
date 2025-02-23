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

package com.splicemachine.derby.impl.sql.execute.operations;

import com.splicemachine.derby.iapi.sql.execute.SpliceOperation;
import com.splicemachine.derby.iapi.sql.execute.SpliceOperationContext;
import com.splicemachine.derby.stream.function.ScalarAggregateFlatMapFunction;
import com.splicemachine.derby.stream.iapi.DataSet;
import com.splicemachine.derby.stream.iapi.DataSetProcessor;
import com.splicemachine.derby.stream.iapi.OperationContext;
import com.splicemachine.derby.utils.EngineUtils;
import com.splicemachine.utils.SpliceLogUtils;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.services.loader.GeneratedMethod;
import com.splicemachine.db.iapi.sql.Activation;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import org.apache.log4j.Logger;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

/**
 * Operation for performing Scalar Aggregations (sum, avg, max/min, etc.). 
 *
 * @author Scott Fines
 */
public class ScalarAggregateOperation extends GenericAggregateOperation {

    public static final long serialVersionUID = 1l;
    private static Logger LOG = Logger.getLogger(ScalarAggregateOperation.class);
    protected boolean isInSortedOrder;
    protected boolean singleInputRow;
    protected static final String NAME = ScalarAggregateOperation.class.getSimpleName().replaceAll("Operation","");
    
    @Override
    public String getName() {
		return NAME;
    }
    
    
    public ScalarAggregateOperation () {
		super();
    }
    
    public ScalarAggregateOperation(SpliceOperation s,
                                    boolean isInSortedOrder,
                                    int	aggregateItem,
                                    Activation a,
                                    GeneratedMethod ra,
                                    int resultSetNumber,
                                    boolean singleInputRow,
                                    double optimizerEstimatedRowCount,
                                    double optimizerEstimatedCost) throws StandardException  {
		super(s, aggregateItem, a, ra, resultSetNumber, optimizerEstimatedRowCount, optimizerEstimatedCost);
		this.isInSortedOrder = isInSortedOrder;
		this.singleInputRow = singleInputRow;
    }
    
    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
		super.readExternal(in);
		isInSortedOrder = in.readBoolean();
		singleInputRow = in.readBoolean();
    }
    
    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
		super.writeExternal(out);
		out.writeBoolean(isInSortedOrder);
		out.writeBoolean(singleInputRow);
    }
    
    @Override
    public void init(SpliceOperationContext context) throws StandardException, IOException {
		super.init(context);
		source.init(context);
		try {
			sortTemplateRow = this.aggregateContext.getSortTemplateRow();
			sourceExecIndexRow = this.aggregateContext.getSourceIndexRow();
		} catch (StandardException e) {
			SpliceLogUtils.logAndThrowRuntime(LOG,e);
		}
    }
    
    @Override
    public ExecRow getExecRowDefinition() throws StandardException {
		if (LOG.isTraceEnabled())
			SpliceLogUtils.trace(LOG,"getExecRowDefinition");
		ExecRow row = sourceExecIndexRow.getClone();
		// Set the default values to 0 in case a ProjectRestrictOperation has set the default values to 1.
		// That is done to avoid division by zero exceptions when executing a projection for defining the rows
		// before execution.
		EngineUtils.populateDefaultValues(row.getRowArray(),0);
		return row;
    }

    @Override
    public String toString() {
        return String.format("ScalarAggregateOperation {resultSetNumber=%d, source=%s}", resultSetNumber, source);
    }

	@Override
	public String prettyPrint(int indentLevel) {
		return "Scalar"+super.prettyPrint(indentLevel);
	}

    @Override
    public DataSet<LocatedRow> getDataSet(DataSetProcessor dsp) throws StandardException {
        OperationContext<ScalarAggregateOperation> operationContext = dsp.createOperationContext(this);
        DataSet<LocatedRow> dsSource = source.getDataSet(dsp);
        DataSet<LocatedRow> ds = dsSource.mapPartitions(new ScalarAggregateFlatMapFunction(operationContext, false), false, /*pushScope=*/true, "First Aggregation");
        DataSet<LocatedRow> ds2 = ds.coalesce(1, /*shuffle=*/true, /*isLast=*/false, operationContext, /*pushScope=*/true, "Coalesce");
        return ds2.mapPartitions(new ScalarAggregateFlatMapFunction(operationContext, true), /*isLast=*/true, /*pushScope=*/true, "Final Aggregation");
    }
}
