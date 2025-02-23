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

import org.spark_project.guava.base.Strings;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.services.loader.GeneratedMethod;
import com.splicemachine.db.iapi.sql.Activation;
import com.splicemachine.db.iapi.sql.execute.CursorResultSet;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.db.iapi.types.RowLocation;
import com.splicemachine.db.iapi.types.SQLInteger;
import com.splicemachine.db.impl.sql.execute.ValueRow;
import com.splicemachine.derby.iapi.sql.execute.SpliceOperation;
import com.splicemachine.derby.iapi.sql.execute.SpliceOperationContext;
import com.splicemachine.derby.impl.SpliceMethod;
import com.splicemachine.derby.impl.store.access.hbase.HBaseRowLocation;
import com.splicemachine.derby.stream.function.RowOperationFunction;
import com.splicemachine.derby.stream.iapi.DataSet;
import com.splicemachine.derby.stream.iapi.DataSetProcessor;
import com.splicemachine.primitives.Bytes;
import com.splicemachine.utils.SpliceLogUtils;
import org.apache.log4j.Logger;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Collections;
import java.util.List;
    /**
    * Takes a constant row value and returns it as
    * a result set.
    * <p>
    * This class actually probably never underlies a select statement,
    * but in case it might and because it has the same behavior as the
    * ones that do, we have it implement CursorResultSet and give
    * reasonable answers.
    *
    */
public class RowOperation extends SpliceBaseOperation{
    private static final long serialVersionUID=2l;
    private static Logger LOG=Logger.getLogger(RowOperation.class);
    protected boolean canCacheRow;
    protected boolean next=false;
    protected SpliceMethod<ExecRow> rowMethod;
    protected ExecRow cachedRow;
    private ExecRow rowDefinition;
    private String rowMethodName; //name of the row method for
    protected static final String NAME=RowOperation.class.getSimpleName().replaceAll("Operation","");

    /**
     *
     * Retrieve the static name for the operation.
     *
     * @return
     */
    @Override
    public String getName(){
        return NAME;
    }


    /**
     * Required for serialization...
     */
    public RowOperation(){

    }

    /**
     *
     * Instance method for projected value.
     *
     * @param activation
     * @param row
     * @param canCacheRow
     * @param resultSetNumber
     * @param optimizerEstimatedRowCount
     * @param optimizerEstimatedCost
     * @throws StandardException
     */
    public RowOperation(
            Activation activation,
            GeneratedMethod row,
            boolean canCacheRow,
            int resultSetNumber,
            double optimizerEstimatedRowCount,
            double optimizerEstimatedCost) throws StandardException{
        super(activation,resultSetNumber,optimizerEstimatedRowCount,optimizerEstimatedCost);
        this.canCacheRow=canCacheRow;
        this.rowMethodName=row.getMethodName();
        init();
    }

    /**
     *
     * Instance method for straight forward non-projected value.
     *
     * @param activation
     * @param constantRow
     * @param canCacheRow
     * @param resultSetNumber
     * @param optimizerEstimatedRowCount
     * @param optimizerEstimatedCost
     * @throws StandardException
     */
    public RowOperation(
            Activation activation,
            ExecRow constantRow,
            boolean canCacheRow,
            int resultSetNumber,
            double optimizerEstimatedRowCount,
            double optimizerEstimatedCost) throws StandardException{
        super(activation,resultSetNumber,optimizerEstimatedRowCount,optimizerEstimatedCost);
        this.cachedRow=constantRow;
        this.canCacheRow=canCacheRow;
        init();
    }

    /**
     *
     * Called during creation and after serialization.
     *
     * @param context
     * @throws StandardException
     * @throws IOException
     */
    @Override
    public void init(SpliceOperationContext context) throws StandardException, IOException{
        super.init(context);
        if(rowMethod==null && rowMethodName!=null){
            this.rowMethod=new SpliceMethod<>(rowMethodName,activation);
        }
    }

    /**
     *
     * Serde.
     *
     * @param in
     * @throws IOException
     * @throws ClassNotFoundException
     */
    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException{
        SpliceLogUtils.trace(LOG,"readExternal");
        super.readExternal(in);
        canCacheRow=in.readBoolean();
        next=in.readBoolean();
        if(in.readBoolean())
            rowMethodName=in.readUTF();
    }

    /**
     *
     * Serde.
     *
     * @param out
     * @throws IOException
     */
    @Override
    public void writeExternal(ObjectOutput out) throws IOException{
        SpliceLogUtils.trace(LOG,"writeExternal");
        super.writeExternal(out);
        out.writeBoolean(canCacheRow);
        out.writeBoolean(next);
        out.writeBoolean(rowMethodName!=null);
        if(rowMethodName!=null){
            out.writeUTF(rowMethodName);
        }
    }

    /**
     *
     * Retrive the row for the operation.
     *
     * @return
     * @throws StandardException
     */
    public ExecRow getRow() throws StandardException{
        if(cachedRow!=null){
            SpliceLogUtils.trace(LOG,"getRow,cachedRow=%s",cachedRow);
            return cachedRow.getClone();
        }

        if(rowMethod!=null){
            currentRow=rowMethod.invoke();
            if(canCacheRow){
                cachedRow=currentRow;
            }
        }
        return currentRow.getClone();
    }

    /**
     * This is not operating against a stored table,
     * so it has no row location to report.
     *
     * @return a null.
     * @see CursorResultSet
     */
    public RowLocation getRowLocation(){
        return null;
    }

    /**
     * This is not used in positioned update and delete,
     * so just return a null.
     *
     * @return a null.
     * @see CursorResultSet
     */
    public ExecRow getCurrentRow(){
        return null;
    }

    /**
     *
     * No Sub operations under a simple row.
     *
     * @return
     */
    @Override
    public List<SpliceOperation> getSubOperations(){
        return Collections.emptyList();
    }

    /**
     *
     * String Representation of a cached row.
     *
      * @return
     */
    @Override
    public String toString(){
        return "RowOp {cachedRow="+cachedRow+"}";
    }

    /**
     *
     * ExecRow Definition of the cached row (types, etc.)
     *
     * @return
     * @throws StandardException
     */
    @Override
    public ExecRow getExecRowDefinition() throws StandardException{
        if(rowDefinition==null){
            ExecRow templateRow=getRow();
            if(templateRow!=null){
                rowDefinition=templateRow.getClone();
            }
            SpliceLogUtils.trace(LOG,"execRowDefinition=%s",rowDefinition);
        }
        return rowDefinition;
    }

    @Override
    public String prettyPrint(int indentLevel){
        String indent="\n"+Strings.repeat("\t",indentLevel);

        return new StringBuilder("RowOp:")
                .append(indent).append("resultSetNumber:").append(resultSetNumber)
                .append(indent).append("canCacheRow:").append(canCacheRow)
                .append(indent).append("rowMethodName:").append(rowMethodName)
                .toString();
    }

        /**
         *
         * Get the Root AccessedCols.  Always null for RowOperation.
         *
          * @param tableNumber
         * @return
         */
    @Override
    public int[] getRootAccessedCols(long tableNumber){
        return null;
    }

    /**
     *
     * Does it reference the table.  Fale for RowOperation.
     *
     * @param tableNumber
     * @return
     */
    @Override
    public boolean isReferencingTable(long tableNumber){
        return false;
    }
    /**
     *
     * Return the dataset abstraction for the operation.
     *
     * @param dsp
     * @return
     * @throws StandardException
     */
    @Override
    public DataSet<LocatedRow> getDataSet(DataSetProcessor dsp) throws StandardException{
        ExecRow execRow=new ValueRow(1);
        execRow.setColumn(1,new SQLInteger(123));
        return dsp.singleRowDataSet(new LocatedRow(new HBaseRowLocation(Bytes.toBytes(1)),execRow))
                .map(new RowOperationFunction(dsp.createOperationContext(this)));
    }

}
