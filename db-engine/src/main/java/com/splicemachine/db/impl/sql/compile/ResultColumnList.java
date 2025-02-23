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

package com.splicemachine.db.impl.sql.compile;


import com.splicemachine.db.catalog.types.DefaultInfoImpl;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.reference.ClassName;
import com.splicemachine.db.iapi.reference.SQLState;
import com.splicemachine.db.iapi.services.classfile.VMOpcode;
import com.splicemachine.db.iapi.services.compiler.LocalField;
import com.splicemachine.db.iapi.services.compiler.MethodBuilder;
import com.splicemachine.db.iapi.services.context.ContextManager;
import com.splicemachine.db.iapi.services.io.FormatableBitSet;
import com.splicemachine.db.iapi.services.loader.ClassFactory;
import com.splicemachine.db.iapi.services.sanity.SanityManager;
import com.splicemachine.db.iapi.sql.ResultColumnDescriptor;
import com.splicemachine.db.iapi.sql.compile.C_NodeTypes;
import com.splicemachine.db.iapi.sql.compile.CostEstimate;
import com.splicemachine.db.iapi.sql.compile.NodeFactory;
import com.splicemachine.db.iapi.sql.conn.LanguageConnectionContext;
import com.splicemachine.db.iapi.sql.dictionary.*;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.db.iapi.store.access.ConglomerateController;
import com.splicemachine.db.iapi.store.access.StoreCostController;
import com.splicemachine.db.iapi.store.access.TransactionController;
import com.splicemachine.db.iapi.types.DataTypeDescriptor;
import com.splicemachine.db.iapi.types.DataValueDescriptor;
import com.splicemachine.db.iapi.types.RowLocation;
import com.splicemachine.db.iapi.types.TypeId;
import com.splicemachine.db.iapi.util.JBitSet;
import com.splicemachine.db.iapi.util.ReuseFactory;

import java.lang.reflect.Modifier;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.*;

/**
 * A ResultColumnList is the target list of a SELECT, INSERT, or UPDATE.
 *
 * @see ResultColumn
 */

public class ResultColumnList extends QueryTreeNodeVector<ResultColumn>{
    /* Is this the ResultColumnList for an index row? */
    protected boolean indexRow;
    protected long conglomerateId;

    int orderBySelect=0; // the number of result columns pulled up
    // from ORDERBY list
    /*
     * A comment on 'orderBySelect'. When we encounter a SELECT .. ORDER BY
     * statement, the columns (or expressions) in the ORDER BY clause may
     * or may not have been explicitly mentioned in the SELECT column list.
     * If the columns were NOT explicitly mentioned in the SELECT column
     * list, then the parsing of the ORDER BY clause implicitly generates
     * them into the result column list, because we'll need to have those
     * columns present at execution time in order to sort by them. Those
     * generated columns are added to the *end* of the ResultColumnList, and
     * we keep track of the *number* of those columns in 'orderBySelect',
     * so we can tell whether we are looking at a generated column by seeing
     * whether its position in the ResultColumnList is in the last
     * 'orderBySelect' number of columns. If the SELECT .. ORDER BY
     * statement uses the "*" token to select all the columns from a table,
     * then during ORDER BY parsing we redundantly generate the columns
     * mentioned in the ORDER BY clause into the ResultColumnlist, but then
     * later in getOrderByColumnToBind we determine that these are
     * duplicates and we take them back out again.
     */

    /*
    ** Is this ResultColumnList for a FromBaseTable for an index
	** that is to be updated?
	*/
    protected boolean forUpdate;

    // Is a count mismatch allowed - see set/get methods for details.
    private boolean countMismatchAllowed;

    // Number of RCs in this RCL at "init" time, before additional
    // ones were added internally.
    private int initialListSize=0;

    public ResultColumnList(){
    }

    /**
     * Add a ResultColumn (at this point, ResultColumn or
     * AllResultColumn) to the list
     *
     * @param resultColumn The ResultColumn to add to the list
     */

    public void addResultColumn(ResultColumn resultColumn){
		/* Vectors are 0-based, ResultColumns are 1-based */
        resultColumn.setVirtualColumnId(size()+1);
        addElement(resultColumn);
    }

    /**
     * Append a given ResultColumnList to this one, resetting the virtual
     * column ids in the appended portion.
     *
     * @param resultColumns   The ResultColumnList to be appended
     * @param destructiveCopy Whether or not this is a descructive copy
     *                        from resultColumns
     */
    public void appendResultColumns(ResultColumnList resultColumns, boolean destructiveCopy){
        int oldSize=size();
        int newID=oldSize+1;

		/*
		** Set the virtual column ids in the list being appended.
		** Vectors are zero-based, and virtual column ids are one-based,
		** so the new virtual column ids start at the original size
		** of this list, plus one.
		*/
        int otherSize=resultColumns.size();
        for(int index=0;index<otherSize;index++){
			/* ResultColumns are 1-based */
            resultColumns.elementAt(index).setVirtualColumnId(newID);
            newID++;
        }

        if(destructiveCopy){
            destructiveAppend(resultColumns);
        }else{
            nondestructiveAppend(resultColumns);
        }
    }

    /**
     * Get a ResultColumn from a column position (1-based) in the list
     *
     * @param position The ResultColumn to get from the list (1-based)
     * @return the column at that position.
     */

    public ResultColumn getResultColumn(int position){
		/*
		** First see if it falls in position x.  If not,
		** search the whole shebang
		*/
        if(position<=size()){
            // this wraps the cast needed, 
            // and the 0-based nature of the Vectors.
            ResultColumn rc=elementAt(position-1);
            if(rc.getColumnPosition()==position){
                return rc;
            }
        }
		
		/*
		** Check each column
		*/
        int size=size();
        for(int index=0;index<size;index++){
            ResultColumn rc=elementAt(index);
            if(rc.getColumnPosition()==position){
                return rc;
            }
        }
        return null;
    }

    /**
     * Take a column position and a ResultSetNode and find the ResultColumn
     * in this RCL whose source result set is the same as the received
     * RSN and whose column position is the same as the received column
     * position.
     *
     * @param colNum The column position (w.r.t rsn) for which we're searching
     * @param rsn    The result set node for which we're searching.
     * @return The ResultColumn in this RCL whose source is column colNum
     * in result set rsn.  That ResultColumn's position w.r.t to this RCL
     * is also returned via the whichRC parameter.  If no match is found,
     * return null and leave whichRC untouched.
     */
    public ResultColumn getResultColumn(int colNum,ResultSetNode rsn, int[] whichRC) throws StandardException{
        if(colNum==-1)
            return null;

        ResultColumn rc;
        ColumnReference colRef;
        int[] crColNum=new int[]{-1};

        for(int index=size()-1;index>=0;index--){
            rc=elementAt(index);
            if(!(rc.getExpression() instanceof ColumnReference)){
                // If the rc's expression isn't a column reference then
                // it can't be pointing to rsn, so just skip it.
                continue;
            }

            colRef=(ColumnReference)rc.getExpression();
            if((rsn==colRef.getSourceResultSet(crColNum)) &&
                    (crColNum[0]==colNum)){
                // Found a match.
                whichRC[0]=index+1;
                return rc;
            }
        }

        return null;
    }

    /**
     * Get a ResultColumn from a column position (1-based) in the list,
     * null if out of range (for order by).
     *
     * @param position The ResultColumn to get from the list (1-based)
     * @return the column at that position, null if out of range
     */
    public ResultColumn getOrderByColumn(int position){
        // this wraps the cast needed, and the 0-based nature of the Vectors.
        if(position==0)
            return null;

        return getResultColumn(position);
    }

    /**
     * Get a ResultColumn that matches the specified columnName and
     * mark the ResultColumn as being referenced.
     *
     * @param columnName The ResultColumn to get from the list
     * @return the column that matches that name.
     */

    public ResultColumn getResultColumn(String columnName){
        return getResultColumn(columnName,true);
    }

    /**
     * Get a ResultColumn that matches the specified columnName. If requested
     * to, mark the column as referenced.
     *
     * @param columnName       The ResultColumn to get from the list
     * @param markIfReferenced True if we should mark this column as referenced.
     * @return the column that matches that name.
     */

    public ResultColumn getResultColumn(String columnName,boolean markIfReferenced){
        int size=size();
        for(int index=0;index<size;index++){
            ResultColumn resultColumn=elementAt(index);

            if(columnName.equals(resultColumn.getName())){
                /* Mark ResultColumn as referenced and return it */
                if(markIfReferenced){
                    resultColumn.setReferenced();
                }
                return resultColumn;
            }
        }
        return null;
    }

    public ResultColumn getResultColumnFullName(String fullName,boolean markIfReferenced){
        int size=size();
        for(int index=0;index<size;index++){
            ResultColumn resultColumn=elementAt(index);

            if(fullName.equals(resultColumn.getFullName())){
                /* Mark ResultColumn as referenced and return it */
                if(markIfReferenced){
                    resultColumn.setReferenced();
                }
                return resultColumn;
            }
        }
        return null;
    }

    /**
     * Return a result column, if any found, which contains in its
     * expression/&#123;VCN,CR&#125; chain a result column with the given
     * columnNumber from a FromTable with the given tableNumber.
     * <p/>
     * Used by the optimizer preprocess phase when it is flattening queries,
     * which has to use the pair &#123;table number, column number&#125; to
     * uniquely distinguish the column desired in situations where the same
     * table may appear multiple times in the queries with separate correlation
     * names, and/or column names from different tables may be the same (hence
     * looking up by column name will not always work), cf DERBY-4679.
     * <p/>
     * {@code columnName} is used to assert that we find the right column.
     * If we found a match on (tn, cn) but columnName is wrong, return null.
     * Once we trust table numbers and column numbers to always be correct,
     * cf. DERBY-4695, we could remove this parameter.
     *
     * @param tableNumber  the table number to look for
     * @param columnNumber the column number to look for
     * @param columnName   name of the desired column
     */
    public ResultColumn getResultColumn(int tableNumber, int columnNumber, String columnName){
        int size=size();

        for(int index=0;index<size;index++){
            ResultColumn resultColumn=elementAt(index);
            ResultColumn rc=resultColumn;

            while(rc!=null){
                ValueNode exp=rc.getExpression();

                if(exp instanceof VirtualColumnNode){
                    VirtualColumnNode vcn=(VirtualColumnNode)exp;
                    ResultSetNode rsn=vcn.getSourceResultSet();

                    if(rsn instanceof FromTable){
                        FromTable ft=(FromTable)rsn;

                        if(ft.getTableNumber()==tableNumber){
                            // We have the right table, now try to match the
                            // column number. Looking at a join, for a base
                            // table participant, we will find the correct
                            // column position in the
                            // JOIN's ColumnDescriptor. Normally, we could just
                            // call rc.getColumnPosition, but this doesn't work
                            // if we have a join with a subquery participant
                            // (it would give us the virtualColumnId one level
                            // too high up, since the column descriptor is null
                            // in that case inside a JOIN's RC.
                            //
                            // If FromTable is a FromSubquery we need to look
                            // at the JOIN RC's source column to match the
                            // table column number. However, at that level, the
                            // table number would be that of the underlying
                            // SELECT (for example), rather than the
                            // FromSubquery's, so we need to match the table
                            // number one level above, cf the test cases in
                            // JoinTest#testDerby_4679 which have subqueries.

                            ColumnDescriptor cd=rc.getTableColumnDescriptor();

                            assert cd!=null || ft instanceof FromSubquery;

                            if((cd!=null && cd.getPosition()== columnNumber)
                                    || (vcn.getSourceColumn().getColumnPosition()== columnNumber)){

                                // Found matching (t,c) within this top
                                // resultColumn. Now do sanity check that column
                                // name is correct. Remove when DERBY-4695 is
                                // fixed.
                                if(columnName.equals(vcn.getSourceColumn().getName())){
                                    resultColumn.setReferenced();
                                    return resultColumn;
                                }else{
                                    if(SanityManager.DEBUG){
                                        SanityManager.THROWASSERT(
                                                "wrong (tn,cn) for column "+columnName+" found: this pair points to "+
                                                        vcn.getSourceColumn().getName());
                                    }
                                    // Fall back on column name based lookup,
                                    // cf. DERBY-4679. See ColumnReference#
                                    // remapColumnReferencesToExpressions
                                    return null;
                                }
                            }else{
                                rc=vcn.getSourceColumn();
                            }
                        }else{
                            rc=vcn.getSourceColumn();
                        }
                    }else{
                        rc=null;
                    }
                }else if(exp instanceof ColumnReference){
                    ColumnReference cr=(ColumnReference)exp;

                    if(cr.getTableNumber()==tableNumber && cr.getColumnNumber()==columnNumber){
                        // Found matching (t,c) within this top resultColumn
                        resultColumn.setReferenced();
                        return resultColumn;
                    }else{
                        rc=null;
                    }
                }else{
                    assert exp instanceof BaseColumnNode: "expected BaseColumnNode, found: "+ exp.getClass();
                    rc=null;
                }
            }

        }
        return null;
    }


    /**
     * Get a ResultColumn that matches the specified columnName and
     * mark the ResultColumn as being referenced.
     *
     * @param columnsTableName Qualifying name for the column
     * @param columnName       The ResultColumn to get from the list
     * @return the column that matches that name.
     */

    public ResultColumn getResultColumn(String columnsTableName,String columnName){
        int size=size();
        for(int index=0;index<size;index++){
            ResultColumn resultColumn=elementAt(index);

			/* If the column's table name is non-null, then we have found a match
			 * only if the RC's table name is non-null and the same as the
			 * the CR's table name.
			 */
            if(columnsTableName!=null){
                if(resultColumn.getTableName()==null){
                    continue;
                }

                if(!columnsTableName.equals(resultColumn.getTableName())){
                    continue;
                }
            }
            if(columnName.equals(resultColumn.getName())){
				/* Mark ResultColumn as referenced and return it */
                resultColumn.setReferenced();
                return resultColumn;
            }
        }
        return null;
    }

    /**
     * Get a ResultColumn that matches the specified columnName and
     * mark the ResultColumn as being referenced.
     * NOTE - this flavor enforces no ambiguity (at most 1 match)
     * Only FromSubquery needs to call this flavor since
     * it can have ambiguous references in its own list.
     *
     * @param cr                       The ColumnReference to resolve
     * @param exposedTableName         Exposed table name for FromTable
     * @param considerGeneratedColumns Also consider columns that are generated.
     *                                 One example of this is group by where columns are added to the select list
     *                                 if they are referenced in the group by but are not present in the select
     *                                 list.
     * @throws StandardException Thrown on error
     * @return the column that matches that name.
     */

    public ResultColumn getAtMostOneResultColumn(ColumnReference cr,
                                                 String exposedTableName,
                                                 boolean considerGeneratedColumns) throws StandardException{
        int size=size();
        ResultColumn retRC=null;
        String columnName=cr.getColumnName();

        for(int index=0;index<size;index++){
            ResultColumn resultColumn=elementAt(index);

            if(columnName.equals(resultColumn.getName())){
                if(resultColumn.isGenerated() && !considerGeneratedColumns){
                    continue;
                }
				/* We should get at most 1 match */
                if(retRC!=null){
                    throw StandardException.newException(SQLState.LANG_AMBIGUOUS_COLUMN_NAME_IN_TABLE,
                            columnName,exposedTableName);
                }
				/* Mark ResultColumn as referenced and return it */
                resultColumn.setReferenced();
                retRC=resultColumn;
            }
        }
        return retRC;
    }

    /**
     * For order by column bind, get a ResultColumn that matches the specified
     * columnName.
     * <p/>
     * This method is called during bind processing, in the special
     * "bind the order by" call that is made by CursorNode.bindStatement().
     * The OrderByList has a special set of bind processing routines
     * that analyzes the columns in the ORDER BY list and verifies that
     * each column is one of:
     * - a direct reference to a column explicitly mentioned in
     * the SELECT list
     * - a direct reference to a column implicitly mentioned as "SELECT *"
     * - a direct reference to a column "pulled up" into the result
     * column list
     * - or a valid and fully-bound expression ("c+2", "YEAR(hire_date)", etc.)
     * <p/>
     * At this point in the processing, it is possible that we'll find
     * the column present in the RCL twice: once because it was pulled
     * up during statement compilation, and once because it was added
     * when "SELECT *" was expanded into the table's actual column list.
     * If we find such a duplicated column, we can, and do, remove the
     * pulled-up copy of the column and point the OrderByColumn
     * to the actual ResultColumn from the *-expansion.
     * <p/>
     * Note that the association of the OrderByColumn with the
     * corresponding ResultColumn in the RCL occurs in
     * OrderByColumn.resolveAddedColumn.
     *
     * @param columnName  The ResultColumn to get from the list
     * @param tableName   The table name on the OrderByColumn, if any
     * @param tableNumber The tableNumber corresponding to the FromTable with the
     *                    exposed name of tableName, if tableName != null.
     * @param obc         The OrderByColumn we're binding.
     * @throws StandardException thrown on ambiguity
     * @return the column that matches that name.
     */
    public ResultColumn getOrderByColumnToBind(String columnName,
                                               TableName tableName,
                                               int tableNumber,
                                               OrderByColumn obc) throws StandardException{
        ResultColumn retVal=null, resultColumn;

        int size=size();
        for(int index=0;index<size;index++){
            resultColumn=elementAt(index);

			/* The order by column is qualified, then it is okay to consider
			 * this RC if:
			 *	o  The RC is qualified and the qualifiers on the order by column
			 *	   and the RC are equal().
			 *	o  The RC is not qualified, but its expression is a ColumnReference
			 *	   from the same table (as determined by the tableNumbers).
			 */
            boolean columnNameMatches;
            if(tableName!=null){
                ValueNode rcExpr=resultColumn.getExpression();
                if(!(rcExpr instanceof ColumnReference))
                    continue;

                ColumnReference cr=(ColumnReference)rcExpr;
                if((!tableName.equals(cr.getTableNameNode())) && tableNumber!=cr.getTableNumber())
                    continue;
                columnNameMatches= columnName.equals(resultColumn.getSourceColumnName());
            }else
                columnNameMatches= resultColumn.columnNameMatches(columnName);


			/* We finally got past the qualifiers, now see if the column
			 * names are equal. If they are, then we appear to have found
			* our order by column. If we find our order by column multiple
			* times, make sure that they are truly duplicates, otherwise
			* we have an ambiguous situation. For example, the query
			*   SELECT b+c AS a, d+e AS a FROM t ORDER BY a
			* is ambiguous because we don't know which "a" is meant. But
			*   SELECT t.a, t.* FROM t ORDER BY a
			* is not ambiguous, even though column "a" is selected twice.
			* If we find our ORDER BY column at the end of the
			* SELECT column list, in the last 'orderBySelect' number
			* of columns, then this column was not explicitly mentioned
			* by the user in their SELECT column list, but was implicitly 
			* added by the parsing of the ORDER BY clause, and it
			* should be removed from the ResultColumnList and returned
			* to the caller.
			 */
            if(columnNameMatches){
                if(retVal==null){
                    retVal=resultColumn;
                }else if(!retVal.isEquivalent(resultColumn)){
                    throw StandardException.newException(SQLState.LANG_DUPLICATE_COLUMN_FOR_ORDER_BY,columnName);
                }else if(index>=size-orderBySelect){// remove the column due to pullup of orderby item
                    removeElement(resultColumn);
                    decOrderBySelect();
                    obc.clearAddedColumnOffset();
                    collapseVirtualColumnIdGap(resultColumn.getColumnPosition());
                    break;
                }
            }
        }
        return retVal;
    }

    /**
     * Adjust virtualColumnId values due to result column removal
     * <p/>
     * This method is called when a duplicate column has been detected and
     * removed from the list. We iterate through each of the other columns
     * in the list and notify them of the column removal so they can adjust
     * their virtual column id if necessary.
     *
     * @param gap id of the column which was just removed.
     */
    private void collapseVirtualColumnIdGap(int gap){
        for(int index=0;index<size();index++)
            elementAt(index).collapseVirtualColumnIdGap(gap);
    }


    /**
     * For order by, get a ResultColumn that matches the specified
     * columnName.
     * <p/>
     * This method is called during pull-up processing, at the very
     * start of bind processing, as part of
     * OrderByList.pullUpOrderByColumns. Its job is to figure out
     * whether the provided column (from the ORDER BY list) already
     * exists in the ResultColumnList or not. If the column does
     * not exist in the RCL, we return NULL, which signifies that
     * a new ResultColumn should be generated and added ("pulled up")
     * to the RCL by our caller.
     * <p/>
     * Note that at this point in the processing, we should never
     * find this column present in the RCL multiple times; if the
     * column is already present in the RCL, then we don't need to,
     * and won't, pull a new ResultColumn up into the RCL.
     * <p/>
     * If the caller specified "SELECT *", then the RCL at this
     * point contains a special AllResultColumn object. This object
     * will later be expanded and replaced by the actual set of
     * columns in the table, but at this point we don't know what
     * those columns are, so we may pull up an OrderByColumn
     * which duplicates a column in the *-expansion; such
     * duplicates will be removed at the end of bind processing
     * by OrderByList.bindOrderByColumns.
     *
     * @param columnName The ResultColumn to get from the list
     * @param tableName  The table name on the OrderByColumn, if any
     * @throws StandardException thrown on ambiguity
     * @return the column that matches that name, or NULL if pull-up needed
     */
    public ResultColumn findResultColumnForOrderBy(String columnName,TableName tableName) throws StandardException{
        int size=size();
        ResultColumn retVal=null, resultColumn;
        for(int index=0;index<size;index++){
            resultColumn=elementAt(index);

            // We may be checking on "ORDER BY T.A" against "SELECT *".
            // exposedName will not be null and "*" will not have an expression
            // or tablename.
            // We may be checking on "ORDER BY T.A" against "SELECT T.B, T.A".
            boolean columnNameMatches;
            if(tableName!=null){
                ValueNode rcExpr=resultColumn.getExpression();
                if(rcExpr==null || !(rcExpr instanceof ColumnReference)){
                    continue;
                }
                ColumnReference cr=(ColumnReference)rcExpr;
                if(!tableName.equals(cr.getTableNameNode()))
                    continue;
                columnNameMatches=columnName.equals(resultColumn.getSourceColumnName());
            }else
                columnNameMatches=resultColumn.columnNameMatches(columnName);

			/* We finally got past the qualifiers, now see if the column
			 * names are equal.
			 */
            if(columnNameMatches){
                if(retVal==null){
                    retVal=resultColumn;
                }else if(!retVal.isEquivalent(resultColumn)){
                    throw StandardException.newException(SQLState.LANG_DUPLICATE_COLUMN_FOR_ORDER_BY,columnName);
                }else if(index>=size-orderBySelect){
                    if(SanityManager.DEBUG)
                        SanityManager.THROWASSERT("Unexpectedly found ORDER BY column '"+
                                        columnName+"' pulled up at position "+index);
                }
            }
        }
        return retVal;
    }

    /**
     * Copy the result column names from the given ResultColumnList
     * to this ResultColumnList.  This is useful for insert-select,
     * where the columns being inserted into may be different from
     * the columns being selected from.  The result column list for
     * an insert is supposed to have the column names being inserted
     * into.
     *
     * @param nameList The ResultColumnList from which to copy
     *                 the column names
     */

    void copyResultColumnNames(ResultColumnList nameList){
		/* List checking is done during bind().  Lists should be the
		 * same size when we are called.
		 */
        if(SanityManager.DEBUG){
            if((!countMismatchAllowed) && visibleSize()!=nameList.visibleSize()){
                SanityManager.THROWASSERT( "The size of the 2 lists is expected to be the same. "+
                                "visibleSize() = "+visibleSize()+
                                ", nameList.visibleSize() = "+nameList.visibleSize());
            }
        }

        int size=(countMismatchAllowed)?nameList.visibleSize():visibleSize();

        for(int index=0;index<size;index++){
            ResultColumn thisResultColumn=elementAt(index);
            ResultColumn nameListResultColumn=nameList.elementAt(index);
            thisResultColumn.setName(nameListResultColumn.getName());
            thisResultColumn.setNameGenerated(nameListResultColumn.isNameGenerated());
        }
    }


    /**
     * Bind the expressions in this ResultColumnList.  This means binding
     * the expression under each ResultColumn node.
     *
     * @param fromList        The FROM list for the query this
     *                        expression is in, for binding columns.
     * @param subqueryList    The subquery list being built as we find SubqueryNodes
     * @param aggregateVector The aggregate vector being built as we find AggregateNodes
     * @throws StandardException Thrown on error
     */
    public void bindExpressions(FromList fromList,
                                SubqueryList subqueryList,
                                List<AggregateNode> aggregateVector) throws StandardException{
		/* First we expand the *'s in the result column list */
        expandAllsAndNameColumns(fromList);

		/* Now we bind each result column */
        int size=size();
        for(int index=0;index<size;index++){
            ValueNode vn=elementAt(index);
            vn=vn.bindExpression(fromList,subqueryList,aggregateVector);
            //-sf- this cast is safe because ResultColumn returns a ResultColumn from bindExpression()
            setElementAt((ResultColumn)vn,index);
        }
    }

    /**
     * Bind the result columns to the expressions that live under them.
     * All this does is copy the datatype information to from each expression
     * to each result column.  This is useful for SELECT statements, where
     * the result type of each column is the type of the column's expression.
     *
     * @throws StandardException Thrown on error
     */
    public void bindResultColumnsToExpressions() throws StandardException{
        int size=size();
        for(int index=0;index<size;index++){
            elementAt(index).bindResultColumnToExpression();
        }
    }

    /**
     * Bind the result columns by their names.  This is useful for update, grant, and revoke
     * statements, and for INSERT statements like "insert into t (a, b, c)
     * values (1, 2, 3)" where the user specified a column list.
     * If the statment is an insert or update verify that the result column list does not contain any duplicates.
     * NOTE: We pass the ResultColumns position in the ResultColumnList so
     * that the VirtualColumnId gets set.
     *
     * @param targetTableDescriptor The descriptor for the table being
     *                              updated or inserted into
     * @param statement             DMLStatementNode containing this list, null if no duplicate checking is to be done
     * @return A FormatableBitSet representing the set of columns with respect to the table
     * @throws StandardException Thrown on error
     */
    public FormatableBitSet bindResultColumnsByName(TableDescriptor targetTableDescriptor,
                                                    DMLStatementNode statement) throws StandardException{
        int size=size();
        FormatableBitSet columnBitSet=new FormatableBitSet(targetTableDescriptor.getMaxColumnID());

        for(int index=0;index<size;index++){
            ResultColumn rc=elementAt(index);
            rc.bindResultColumnByName(targetTableDescriptor, index+1);
            int colIdx=rc.getColumnPosition()-1;
            if(statement!=null && columnBitSet.isSet(colIdx)){
                String colName=rc.getName();
                if(statement instanceof UpdateNode){
                    throw StandardException.newException(SQLState.LANG_DUPLICATE_COLUMN_NAME_UPDATE,colName);
                }else{
                    throw StandardException.newException(SQLState.LANG_DUPLICATE_COLUMN_NAME_INSERT,colName);
                }
            }
            columnBitSet.set(colIdx);
        }
        return columnBitSet;
    }

    /**
     * Bind the result columns by their names.  This is useful for update
     * VTI statements, and for INSERT statements like "insert into new t() (a, b, c)
     * values (1, 2, 3)" where the user specified a column list.
     * Also, verify that the result column list does not contain any duplicates.
     * NOTE: We pass the ResultColumns position in the ResultColumnList so
     * that the VirtualColumnId gets set.
     *
     * @param fullRCL   The full RCL for the target table
     * @param statement DMLStatementNode containing this list
     * @throws StandardException Thrown on error
     */
    public void bindResultColumnsByName(ResultColumnList fullRCL,
                                        FromVTI targetVTI,
                                        DMLStatementNode statement) throws StandardException{
        int size=size();
        Map<String,String> ht=new HashMap<>(size+2,(float).999);

        for(int index=0;index<size;index++){
            ResultColumn matchRC;
            ResultColumn rc=elementAt(index);

			/* Verify that this column's name is unique within the list */
            String colName=rc.getName();

            String object=ht.put(colName,colName);

            if(object!=null && object.equals(colName)){
                if(SanityManager.DEBUG){
                    SanityManager.ASSERT((statement instanceof UpdateNode) || (statement instanceof InsertNode),
                            "statement is expected to be instanceof UpdateNode or InsertNode");
                }
                if(statement instanceof UpdateNode){
                    throw StandardException.newException(SQLState.LANG_DUPLICATE_COLUMN_NAME_UPDATE,colName);
                }else{
                    throw StandardException.newException(SQLState.LANG_DUPLICATE_COLUMN_NAME_INSERT,colName);
                }
            }

            matchRC=fullRCL.getResultColumn(null,rc.getName());
            if(matchRC==null){
                throw StandardException.newException(SQLState.LANG_COLUMN_NOT_FOUND_IN_TABLE,
                        rc.getName(),
                        targetVTI.getMethodCall().getJavaClassName());
        }

			/* We have a match.  We need to create a dummy ColumnDescriptor
			 * since calling code expects one to get column info.
			 */
            ColumnDescriptor cd=new ColumnDescriptor(rc.getName(),matchRC.getVirtualColumnId(),matchRC.getVirtualColumnId(),matchRC.getType(),null,null,null,null,0,0,-1);
            rc.setColumnDescriptor(null,cd);
            rc.setVirtualColumnId(index+1);
        }
    }

    /**
     * Bind the result columns by ordinal position.  This is useful for
     * INSERT statements like "insert into t values (1, 2, 3)", where the
     * user did not specify a column list.
     *
     * @param targetTableDescriptor The descriptor for the table being
     *                              inserted into
     * @throws StandardException Thrown on error
     */
    public void bindResultColumnsByPosition(TableDescriptor targetTableDescriptor) throws StandardException{
        int size=size();
        for(int index=0;index<size;index++){
			/*
			** Add one to the iterator index, because iterator indexes start at zero,
			** and column numbers start at one.
			*/
            elementAt(index).bindResultColumnByPosition(targetTableDescriptor,index+1);
        }
    }

    /**
     * Preprocess the expression trees under the RCL.
     * We do a number of transformations
     * here (including subqueries, IN lists, LIKE and BETWEEN) plus
     * subquery flattening.
     * NOTE: This is done before the outer ResultSetNode is preprocessed.
     *
     * @throws StandardException Thrown on error
     * @param    numTables            Number of tables in the DML Statement
     * @param    outerFromList        FromList from outer query block
     * @param    outerSubqueryList    SubqueryList from outer query block
     * @param    outerPredicateList    PredicateList from outer query block
     */
    public void preprocess(int numTables,
                           FromList outerFromList,
                           SubqueryList outerSubqueryList,
                           PredicateList outerPredicateList) throws StandardException{
        int size=size();

        for(int index=0;index<size;index++){
            ResultColumn resultColumn=elementAt(index);
            //-sf- this cast is safe because preprocess() returns a ResultColumn instance
            setElementAt((ResultColumn)resultColumn.preprocess(numTables,
                            outerFromList,
                            outerSubqueryList,
                            outerPredicateList),
                    index);
        }
    }

    /**
     * Verify that all the result columns have expressions that
     * are storable for them.  Check versus the given ResultColumnList.
     *
     * @throws StandardException Thrown on error
     */
    void checkStorableExpressions(ResultColumnList toStore) throws StandardException{
        int size=size();

        for(int index=0;index<size;index++){
            ResultColumn otherRC=toStore.elementAt(index);

            elementAt(index).checkStorableExpression(otherRC);
        }
    }

    /**
     * Return an array holding the 0 based heap offsets of
     * the StreamStorable columns in this ResultColumnList.
     * This returns null if this list does not contain any
     * StreamStorableColumns. The list this returns does not
     * contain duplicates. This should only be used for
     * a resultColumnList the refers to a single heap
     * such as the target for an Insert, Update or Delete.
     *
     * @param heapColCount the number of heap columns
     * @throws StandardException Thrown on error
     */
    public int[] getStreamStorableColIds(int heapColCount) throws StandardException{
        int ssCount=0;
        int size=size();
        // Sometimes size < heapColCount (delete), other times they match,
        // other times size > heapColCount (insert after a drop column),
        // so ensure array is large enough for any case.
        boolean[] isSS=new boolean[Math.max(size, heapColCount)];

        for(int index=0;index<size;index++){
            ResultColumn rc=elementAt(index);

            if(rc.getTypeId().streamStorable()){
                ColumnDescriptor cd=rc.getTableColumnDescriptor();
                if (cd != null) { // can be null if placeholder for dropped column
                    isSS[cd.getPosition()-1]=true;
                }
            }
        }

        for(boolean isS : isSS) if(isS) ssCount++;

        if(ssCount==0) return null;

        int[] result=new int[ssCount];
        int resultOffset=0;
        for(int heapOffset=0;heapOffset<isSS.length;heapOffset++){
            if(isSS[heapOffset])
                result[resultOffset++]=heapOffset;
        }

        return result;
    }

    /**
     * Verify that all the result columns have expressions that
     * are storable for them.  Check versus the expressions under the
     * ResultColumns.
     *
     * @throws StandardException Thrown on error
     */
    void checkStorableExpressions() throws StandardException{
        int size=size();

        for(int index=0;index<size;index++){
            elementAt(index).checkStorableExpression();
        }
    }


    /**
     * Generate the code to place the columns' values into
     * a row variable named "r". This wrapper is here
     * rather than in ResultColumn, because that class does
     * not know about the position of the columns in the list.
     *
     * @throws StandardException Thrown on error
     */
    @Override
    public void generate(ActivationClassBuilder acb,MethodBuilder mb) throws StandardException{
        generateCore(acb,mb,false);
    }

    /**
     * Generate the code to place the columns' values into
     * a row variable named "r". This wrapper is here
     * rather than in ResultColumn, because that class does
     * not know about the position of the columns in the list.
     *
     * @throws StandardException Thrown on error
     */
    void generateNulls(ActivationClassBuilder acb, MethodBuilder mb) throws StandardException{
        generateCore(acb,mb,true);
    }

    /**
     * Generate the code to place the columns' values into
     * a row variable named "r". This wrapper is here
     * rather than in ResultColumn, because that class does
     * not know about the position of the columns in the list.
     * <p/>
     * This is the method that does the work.
     */
    void generateCore(ExpressionClassBuilder acb, MethodBuilder mb, boolean genNulls) throws StandardException{
        // generate the function and initializer:
        // private ExecRow fieldX;
        // In the constructor:
        //	 fieldX = getExecutionFactory().getValueRow(# cols);
        // private ExecRow exprN()
        // { 
        //   fieldX.setColumn(1, col(1).generateColumn(ps)));
        //   ... and so on for each column ...
        //   return fieldX;
        // }
        // static Method exprN = method pointer to exprN;

        // this sets up the method and the static field.
        MethodBuilder userExprFun=acb.newUserExprFun();

		/* Declare the field */
        LocalField field=acb.newFieldDeclaration(Modifier.PRIVATE,ClassName.ExecRow);

        // Generate the code to create the row in the constructor
        genCreateRow(acb,field,"getValueRow",ClassName.ExecRow,size());

        acb.pushGetExecutionFactoryExpression(userExprFun); // instance
        userExprFun.push(size());
        userExprFun.callMethod(VMOpcode.INVOKEINTERFACE,null,"getValueRow",ClassName.ExecRow,1);
        userExprFun.setField(field);

        ResultColumn rc;
        int size=size();

        MethodBuilder cb=acb.getConstructor();

        for(int index=0;index<size;index++){
            // generate statements of the form
            // fieldX.setColumn(columnNumber, (DataValueDescriptor) columnExpr);
            // and add them to exprFun.
            rc=elementAt(index);

			/* If we are not generating nulls, then we can skip this RC if
			 * it is simply propagating a column from the source result set.
			 */
            if(!genNulls){
                ValueNode sourceExpr=rc.getExpression();

                if(sourceExpr instanceof VirtualColumnNode && !(((VirtualColumnNode)sourceExpr).getCorrelated())){
                    continue;
                }

                //DERBY-4631 - For INNER JOINs and LEFT OUTER
                // JOINs, Derby retrieves the join column values 
                // from the left table's join column. But for 
                // RIGHT OUTER JOINs, the join column's value
                // will be picked up based on following logic.
                //1)if the left table's column value is null
                // then pick up the right table's column's value.
                //2)If the left table's column value is non-null, 
                // then pick up that value 
                if(rc.getJoinResultSet()!=null){
                    //We are dealing with a join column for 
                    // RIGHT OUTER JOIN with USING/NATURAL eg
                    //	 select c from t1 right join t2 using (c)
                    //We are talking about column c as in "select c"
                    ResultColumnList jnRCL= rc.getJoinResultSet().getResultColumns();
                    ResultColumn joinColumn;
                    int joinResultSetNumber= rc.getJoinResultSet().getResultSetNumber();

                    //We need to know the column positions of left
                    // table's join column and right table's join
                    // column to generate the code explained above
                    int virtualColumnIdRightTable=-1;
                    int virtualColumnIdLeftTable=-1;
                    for(int i=0;i<jnRCL.size();i++){
                        joinColumn=jnRCL.elementAt(i);
                        if(joinColumn.getName().equals(rc.getUnderlyingOrAliasName())){
                            if(joinColumn.isRightOuterJoinUsingClause())
                                virtualColumnIdRightTable=joinColumn.getVirtualColumnId();
                            else
                                virtualColumnIdLeftTable=joinColumn.getVirtualColumnId();
                        }
                    }

                    userExprFun.getField(field); // instance
                    userExprFun.push(index+1); // arg1

                    String resultTypeName=
                            getTypeCompiler(
                                    DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BOOLEAN).getTypeId()).interfaceName();
                    String receiverType=ClassName.DataValueDescriptor;

                    //Our plan is to generate DERBY-4631
                    //  if(lefTablJoinColumnValue is null) 
                    //  then
                    //    use rightTablJoinColumnValue 
                    //   else
                    //    use lefTablJoinColumnValue 

                    //Following will generate 
                    //  if(lefTablJoinColumnValue is null) 
                    acb.pushColumnReference(userExprFun,joinResultSetNumber, virtualColumnIdLeftTable);
                    userExprFun.cast(rc.getTypeCompiler().interfaceName());
                    userExprFun.cast(receiverType);
                    userExprFun.callMethod(VMOpcode.INVOKEINTERFACE,null, "isNullOp",resultTypeName,0);
                    //Then call generateExpression on left Table's column
                    userExprFun.cast(ClassName.BooleanDataValue);
                    userExprFun.push(true);
                    userExprFun.callMethod(VMOpcode.INVOKEINTERFACE,null,"equals","boolean",1);
                    //Following will generate 
                    //  then
                    //    use rightTablJoinColumnValue 
                    userExprFun.conditionalIf();
                    acb.pushColumnReference(userExprFun,joinResultSetNumber,
                            virtualColumnIdRightTable);
                    userExprFun.cast(rc.getTypeCompiler().interfaceName());
                    //Following will generate 
                    //   else
                    //    use lefTablJoinColumnValue 
                    userExprFun.startElseCode();
                    acb.pushColumnReference(userExprFun,joinResultSetNumber, virtualColumnIdLeftTable);
                    userExprFun.cast(rc.getTypeCompiler().interfaceName());
                    userExprFun.completeConditional();
                    userExprFun.cast(ClassName.DataValueDescriptor);
                    userExprFun.callMethod(VMOpcode.INVOKEINTERFACE,ClassName.Row,"setColumn","void",2);
                    continue;
                }

                if(sourceExpr instanceof ColumnReference &&
                        !(((ColumnReference)sourceExpr).getCorrelated()) &&
                        sourceExpr.getColumnName().compareToIgnoreCase("ROWID")!=0){
                    continue;
                }
            }


            // row add is 1-based, and iterator index is 0-based
            if(SanityManager.DEBUG){
                if(index+1!=rc.getVirtualColumnId()){
                    SanityManager.THROWASSERT(
                            "VirtualColumnId ("+ rc.getVirtualColumnId()+
                                    ") does not agree with position within Vector ("+ (index+1)+ ")");
                }
            }

            //
            // Generated columns should be populated after the base row because
            // the generation clauses may refer to base columns that have to be filled
            // in first. Population of generated columns is done in another
            // method, which (like CHECK CONSTRAINTS) is explicitly called by
            // InsertResultSet and UpdateResultSet.
            //
            // For LEFT JOINs, we may need to stuff a NULL into the generated column slot,
            // just as we do for non-generated columns in a LEFT JOIN. We look at the source
            // expression for the ResultColumn to determine whether this ResultColumnList
            // represents an INSERT/UPDATE vs. a SELECT. If this ResultColumnList represents a
            // LEFT JOIN, then the source expression will be a VirtualColumnNode.
            // See DERBY-6346.
            //
            if(rc.hasGenerationClause()) {
                ValueNode expr = rc.getExpression();
                if ((expr != null) && !(expr instanceof VirtualColumnNode)) {
                    continue;
                }
            }
            // we need the expressions to be Columns exactly.

			/* SPECIAL CASE:  Expression is a non-null constant.
			 *	Generate the setColumn() call in the constructor
			 *  so that it will only be executed once per instantiation.
			 *
		 	 * Increase the statement counter in constructor.  Code size in
		 	 * constructor can become too big (more than 64K) for Java compiler
		 	 * to handle (beetle 4293).  We set constant columns in other
		 	 * methods if constructor has too many statements already.
		 	 */
//            if((!genNulls) &&
//                    (rc.getExpression() instanceof ConstantNode) &&
//                    !((ConstantNode)rc.getExpression()).isNull() &&
//                    !cb.statementNumHitLimit(1)){
//
//
//                cb.getField(field); // instance
//                cb.push(index+1); // first arg;
//
//                rc.generateExpression(acb,cb);
//                cb.cast(ClassName.DataValueDescriptor); // second arg
//                cb.callMethod(VMOpcode.INVOKEINTERFACE,ClassName.Row,"setColumn","void",2);
//                continue;
//            }

            userExprFun.getField(field); // instance
            userExprFun.push(index+1); // arg1

			/* We want to reuse the null values instead of doing a new each time
			 * if the caller said to generate nulls or the underlying expression
			 * is a typed null value.
			 */
            boolean needDVDCast=true;

            if(rc.isAutoincrementGenerated()){
                // (com.ibm.db2j.impl... DataValueDescriptor)
                // this.getSetAutoincValue(column_number)

                userExprFun.pushThis();

                userExprFun.push(rc.getColumnPosition());
                userExprFun.push(rc.getTableColumnDescriptor().getAutoincInc());

                userExprFun.callMethod(VMOpcode.INVOKEVIRTUAL,ClassName.BaseActivation,
                        "getSetAutoincrementValue",ClassName.DataValueDescriptor,2);
                needDVDCast=false;

            }else if(genNulls ||
                    ((rc.getExpression() instanceof ConstantNode) && ((ConstantNode)rc.getExpression()).isNull())){
                userExprFun.getField(field);
                userExprFun.push(index+1);
                userExprFun.callMethod(VMOpcode.INVOKEINTERFACE,ClassName.Row,"getColumn",ClassName.DataValueDescriptor,1); // the express

                acb.generateNullWithExpress(userExprFun,rc.getTypeCompiler(),
                        rc.getTypeServices().getCollationType(),
                        rc.getTypeServices().getPrecision(),
                        rc.getTypeServices().getScale());
            }else{
                rc.generateExpression(acb,userExprFun);
            }
            if(needDVDCast)
                userExprFun.cast(ClassName.DataValueDescriptor);

            userExprFun.callMethod(VMOpcode.INVOKEINTERFACE,ClassName.Row,"setColumn","void",2);
        }

        userExprFun.getField(field);
        userExprFun.methodReturn();

        // we are now done modifying userExprFun
        userExprFun.complete();

        // what we return is the access of the field, i.e. the pointer to the method.
        acb.pushMethodReference(mb,userExprFun);
    }

    /**
     * Build an empty row with the size and shape of the ResultColumnList.
     *
     * @throws StandardException Thrown on error
     * @return an empty row of the correct size and shape.
     */
    public ExecRow buildEmptyRow() throws StandardException{
        int columnCount=size();
        ExecRow row=getExecutionFactory().getValueRow(columnCount);
        int position=1;

        for(int index=0;index<columnCount;index++){
            ResultColumn rc=elementAt(index);
            DataTypeDescriptor dataType=rc.getTypeServices();
            DataValueDescriptor dataValue=dataType.getNull();

            row.setColumn(position++,dataValue);
        }

        return row;
    }

    /**
     * Build an empty index row for the given conglomerate.
     *
     * @throws StandardException Thrown on error
     * @return an empty row of the correct size and shape.
     */
    public ExecRow buildEmptyIndexRow(TableDescriptor td,
                                      ConglomerateDescriptor cd,
                                      StoreCostController scc) throws StandardException{
        assert cd.isIndex(): "ConglomerateDescriptor expected to be for index: "+ cd;

        int[] baseCols=cd.getIndexDescriptor().baseColumnPositions();
        ExecRow row=getExecutionFactory().getValueRow(baseCols.length+1);

        for(int i=0;i<baseCols.length;i++){
            ColumnDescriptor coldes=td.getColumnDescriptor(baseCols[i]);
            DataTypeDescriptor dataType=coldes.getType();

            // rc = getResultColumn(baseCols[i]);
            // rc = (ResultColumn) at(baseCols[i] - 1);
            // dataType = rc.getTypeServices();
            DataValueDescriptor dataValue=dataType.getNull();

            row.setColumn(i+1,dataValue);
        }

        RowLocation rlTemplate=scc.newRowLocationTemplate();

        row.setColumn(baseCols.length+1,rlTemplate);

        return row;
    }


    /**
     * Generates a row with the size and shape of the ResultColumnList.
     * <p/>
     * Some structures, like FromBaseTable and DistinctNode,
     * need to generate rowAllocator functions to get a row
     * the size and shape of their ResultColumnList.
     * <p/>
     * We return the method pointer, which is a field access
     * in the generated class.
     *
     * @throws StandardException
     */
    void generateHolder(ExpressionClassBuilder acb, MethodBuilder mb) throws StandardException{
        generateHolder(acb,mb,null,null);
    }

    /**
     * Generates a row with the size and shape of the ResultColumnList.
     * <p/>
     * Some structures, like FromBaseTable and DistinctNode,
     * need to generate rowAllocator functions to get a row
     * the size and shape of their ResultColumnList.
     * <p/>
     * We return the method pointer, which is a field access
     * in the generated class.
     *
     * @throws StandardException
     */
    void generateHolder(ExpressionClassBuilder acb,
                        MethodBuilder mb,
                        FormatableBitSet referencedCols,
                        FormatableBitSet propagatedCols) throws StandardException{

        // what we return is a pointer to the method.
        acb.pushMethodReference(mb,generateHolderMethod(acb,referencedCols,propagatedCols));
    }

    MethodBuilder generateHolderMethod(ExpressionClassBuilder acb,
                                       FormatableBitSet referencedCols,
                                       FormatableBitSet propagatedCols) throws StandardException{
        int numCols;
        String rowAllocatorMethod;
        String rowAllocatorType;
        int highestColumnNumber=-1;

        if(referencedCols!=null){
            // Find the number of the last column referenced in the table
            for(int i=referencedCols.anySetBit();
                i!=-1;
                i=referencedCols.anySetBit(i)){
                highestColumnNumber=i;
            }
        }else{
            highestColumnNumber=size()-1;
        }

        // Within the constructor:
        //	 fieldX = getExecutionFactory().getValueRow(# cols);
        // The body of the new method:
        // { 
        //   fieldX.setColumn(1, col(1).generateColumn(ps)));
        //   ... and so on for each column ...
        //   return fieldX;
        // }
        // static Method exprN = method pointer to exprN;

        // this sets up the method and the static field
        MethodBuilder exprFun=acb.newExprFun();

        // Allocate the right type of row, depending on
        // whether we're scanning an index or a heap.
        if(indexRow){
            rowAllocatorMethod="getIndexableRow";
            rowAllocatorType=ClassName.ExecIndexRow;
        }else{
            rowAllocatorMethod="getValueRow";
            rowAllocatorType=ClassName.ExecRow;
        }
        numCols=size();

		/* Declare the field */
        LocalField lf=acb.newFieldDeclaration(Modifier.PRIVATE,ClassName.ExecRow);
        // Generate the code to create the row in the constructor
        genCreateRow(acb,lf,rowAllocatorMethod,rowAllocatorType,highestColumnNumber+1);

        // now we fill in the body of the function

        int colNum;

        // If there is a referenced column map, the first column to fill
        // in is the first one in the bit map - otherwise, it is
        // column 0.
        if(referencedCols!=null)
            colNum=referencedCols.anySetBit();
        else
            colNum=0;

        for(int index=0;index<numCols;index++){
            ResultColumn rc=elementAt(index);

			/* Special code generation for RID since expression is CurrentRowLocationNode.
			 * Really need yet another node type that does its own code generation.
			 */
            if(rc.getExpression() instanceof CurrentRowLocationNode){
                ConglomerateController cc;
                int savedItem;
                RowLocation rl;

                LanguageConnectionContext lcc=getLanguageConnectionContext();
                DataDictionary dd=lcc.getDataDictionary();

                int isolationLevel=TransactionController.ISOLATION_NOLOCK;

                cc=lcc.getTransactionCompile().openConglomerate(conglomerateId,false,0,TransactionController.MODE_RECORD,isolationLevel);

                try{
                    rl=cc.newRowLocationTemplate();
                }finally{
                    if(cc!=null){
                        cc.close();
                    }
                }

                savedItem=acb.addItem(rl);

                // get the RowLocation template
                exprFun.getField(lf); // instance for setColumn
                exprFun.push(highestColumnNumber+1); // first arg

                exprFun.pushThis(); // instance for getRowLocationTemplate
                exprFun.push(savedItem); // first arg
                exprFun.callMethod(VMOpcode.INVOKEINTERFACE,ClassName.Activation,"getRowLocationTemplate",
                        ClassName.RowLocation,1);

                exprFun.upCast(ClassName.DataValueDescriptor);
                exprFun.callMethod(VMOpcode.INVOKEINTERFACE,ClassName.Row,"setColumn", "void",2);
                continue;
            }

			/* Skip over those columns whose source is the immediate
			 * child result set.  (No need to generate a wrapper
			 * for a SQL NULL when we are smart enough not to pass
			 * that wrapper to the store.)
			 * NOTE: Believe it or not, we have to check for the case
			 * where referencedCols is not null, but no bits are set.
			 * This can happen when we need to get all of the columns
			 * from the heap due to a check constraint.
			 */
            if(propagatedCols!=null && propagatedCols.getNumBitsSet()!=0){
				/* We can skip this RC if it is simply propagating 
				 * a column from the source result set.
				 */
                ValueNode sourceExpr=rc.getExpression();

                if(sourceExpr instanceof VirtualColumnNode){
                    // There is a referenced columns bit set, so use
                    // it to figure out what the next column number is.
                    // colNum = referencedCols.anySetBit(colNum);
                    continue;
                }
            }

            // generate the column space creation call
            // generate statements of the form
            // r.setColumn(columnNumber, columnShape);
            //
            // This assumes that there are no "holes" in the column positions,
            // and that column positions reflect the stored format/order
            exprFun.getField(lf); // instance
            exprFun.push(colNum+1); // first arg
            rc.generateHolder(acb,exprFun);

            exprFun.callMethod(VMOpcode.INVOKEINTERFACE,ClassName.Row,"setColumn","void",2);

            // If there is a bit map of referenced columns, use it to
            // figure out what the next column is, otherwise just go
            // to the next column.
            if(referencedCols!=null)
                colNum=referencedCols.anySetBit(colNum);
            else
                colNum++;
        }

        // generate:
        // return fieldX;
        // and add to the end of exprFun's body.
        exprFun.getField(lf);
        exprFun.methodReturn();

        // we are done putting stuff in exprFun:
        exprFun.complete();

        return exprFun;
    }

    /**
     * Generate the code to create an empty row in the constructor.
     *
     * @param acb                The ACB.
     * @param field              The field for the new row.
     * @param rowAllocatorMethod The method to call.
     * @param rowAllocatorType   The row type.
     * @param numCols            The number of columns in the row.
     * @throws StandardException Thrown on error
     */
    private void genCreateRow(ExpressionClassBuilder acb,
                              LocalField field,
                              String rowAllocatorMethod,
                              String rowAllocatorType,
                              int numCols) throws StandardException{
        // Create the row in the constructor
        //	 fieldX = getExecutionFactory().getValueRow(# cols);

        MethodBuilder cb=acb.getConstructor();

        acb.pushGetExecutionFactoryExpression(cb); // instance
        cb.push(numCols);
        cb.callMethod(VMOpcode.INVOKEINTERFACE,null, rowAllocatorMethod,rowAllocatorType,1);
        cb.setField(field);
		/* Increase the statement counter in constructor.  Code size in
		 * constructor can become too big (more than 64K) for Java compiler
		 * to handle (beetle 4293).  We set constant columns in other
		 * methods if constructor has too many statements already.
		 */
        cb.statementNumHitLimit(1);        // ignore return value
    }

    /**
     * Make a ResultDescription for use in a ResultSet.
     * This is useful when generating/executing a NormalizeResultSet, since
     * it can appear anywhere in the tree.
     *
     * @return A ResultDescription for this ResultSetNode.
     */
    public ResultColumnDescriptor[] makeResultDescriptors(){
        ResultColumnDescriptor colDescs[]=new ResultColumnDescriptor[size()];
        int size=size();

        for(int index=0;index<size;index++){
            // the ResultColumn nodes are descriptors, so take 'em...
            colDescs[index]=getExecutionFactory().getResultColumnDescriptor(elementAt(index));
        }

        return colDescs;
    }

    /**
     * Expand any *'s in the ResultColumnList.  In addition, we will guarantee that
     * each ResultColumn has a name.  (All generated names will be unique across the
     * entire statement.)
     *
     * @throws StandardException Thrown on error
     */

    public void expandAllsAndNameColumns(FromList fromList) throws StandardException{
        boolean expanded=false;
        ResultColumnList allExpansion;
        TableName fullTableName;

		/* First walk result column list looking for *'s to expand */
        for(int index=0;index<size();index++){
            ResultColumn rc=elementAt(index);
            if(rc instanceof AllResultColumn){
                expanded=true;

                //fullTableName = ((AllResultColumn) rc).getFullTableName();
                TableName temp=rc.getTableNameObject();
                if(temp!=null){
                    String sName=temp.getSchemaName();
                    String tName=temp.getTableName();
                    fullTableName=makeTableName(sName,tName);
                }else
                    fullTableName=null;
                allExpansion=fromList.expandAll(fullTableName);

				/* Make sure that every column has a name */
                allExpansion.nameAllResultColumns();

				/* Replace the AllResultColumn with the expanded list. 
				 * We will update the VirtualColumnIds once below.
				 */
                removeElementAt(index);
                for(int inner=0;inner<allExpansion.size();inner++){
                    insertElementAt(allExpansion.elementAt(inner),index+inner);
                }

                // Move the index position to account for the removals and the
                // insertions. Should be positioned on the last column in the
                // expansion to prevent double processing of the columns.
                // DERBY-4410: If the expansion is empty, this will move the
                // position one step back because the * was removed and nothing
                // was inserted, so all columns to the right of the current
                // position have been moved one position to the left. If we
                // don't adjust the position, we end up skipping columns.
                index+=(allExpansion.size()-1);

                // If the rc was a "*", we need to set the initial list size
                // to the number of columns that are actually returned to
                // the user.
                markInitialSize();
            }else{
				/* Make sure that every column has a name */
                rc.guaranteeColumnName();
            }
        }

		/* Go back and update the VirtualColumnIds if we expanded any *'s */
        if(expanded){
            int size=size();

            for(int index=0;index<size;index++){
				/* Vectors are 0-based, VirtualColumnIds are 1-based. */
                elementAt(index).setVirtualColumnId(index+1);
            }
        }
    }

    /**
     * Generate (unique across the entire statement) column names for those
     * ResultColumns in this list which are not named.
     *
     * @throws StandardException Thrown on error
     */
    public void nameAllResultColumns() throws StandardException{
        int size=size();

        for(int index=0;index<size;index++){
            ResultColumn resultColumn=elementAt(index);

            resultColumn.guaranteeColumnName();
        }
    }


    /**
     * * Check whether the column lengths and types of the result columns
     * * match the expressions under those columns.  This is useful for
     * * INSERT and UPDATE statements.  For SELECT statements this method
     * * should always return true.  There is no need to call this for a
     * * DELETE statement.
     * * NOTE: We skip over generated columns since they won't have a
     * * column descriptor.
     * *
     * * @return	true means all the columns match their expressions,
     * *		false means at least one column does not match its
     * *		expression
     */
    boolean columnTypesAndLengthsMatch() throws StandardException{
        int size=size();

        for(int index=0;index<size;index++){
            ResultColumn resultColumn=elementAt(index);

			/* Skip over generated columns */
            if(resultColumn.isGenerated()){
                continue;
            }

            if(!resultColumn.columnTypeAndLengthMatch())
                return false;
        }

        return true;
    }

    boolean columnTypesAndLengthsMatch(ResultColumnList otherRCL) throws StandardException{
        boolean retval=true;

		/* We check every RC, even after finding 1 that requires
		 * normalization, because the conversion of constants to
		 * the appropriate type occurs under this loop.
		 */
        int size=size();
        for(int index=0;index<size;index++){
            ResultColumn resultColumn=elementAt(index);

            ResultColumn otherResultColumn=otherRCL.elementAt(index);

			/* Skip over generated columns */
            if(resultColumn.isGenerated() || otherResultColumn.isGenerated()){
                continue;
            }

            if(!resultColumn.columnTypeAndLengthMatch(otherResultColumn)){
                retval=false;
            }
        }

        return retval;
    }

    /**
     * Determine whether this RCL is a No-Op projection of the given RCL.
     * It only makes sense to do this if the given RCL is from the child
     * result set of the ProjectRestrict that this RCL is from.
     *
     * @param childRCL The ResultColumnList of the child result set.
     * @return true if this RCL is a No-Op projection of the given RCL.
     */
    public boolean nopProjection(ResultColumnList childRCL){
		/*
		** This RCL is a useless projection if each column in the child
		** if the same as the column in this RCL.  This is impossible
		** if the two RCLs have different numbers of columns.
		*/
        if(this.size()!=childRCL.size()){
            return false;
        }

		/*
		** The two lists have the same numbers of elements.  Are the lists
		** identical?  In other words, is the expression in every ResultColumn
		** in the PRN's RCL a ColumnReference that points to the corresponding
		** column in the child?
		*/
        int size=size();
        for(int index=0;index<size;index++){
            ResultColumn thisColumn=elementAt(index);
            ResultColumn referencedColumn;

			/*
			** A No-Op projection can point to a VirtualColumnNode or a
			** ColumnReference.
			*/
            if(thisColumn.getExpression() instanceof VirtualColumnNode){
                referencedColumn=((VirtualColumnNode)(thisColumn.getExpression())).getSourceColumn();
            }else if(thisColumn.getExpression() instanceof ColumnReference){
                referencedColumn=((ColumnReference)(thisColumn.getExpression())).getSource();
            }else{
                return false;
            }

            ResultColumn childColumn=childRCL.elementAt(index);

            if(referencedColumn!=childColumn){
                return false;
            }
        }

        return true;
    }

    /**
     * Create a shallow copy of a ResultColumnList and its ResultColumns.
     * (All other pointers are preserved.)
     * Useful for building new ResultSetNodes during preprocessing.
     *
     * @return None.
     * @throws StandardException Thrown on error
     */
    public ResultColumnList copyListAndObjects() throws StandardException{
        ResultColumn newResultColumn;
        ResultColumn origResultColumn;
        ResultColumnList newList;

		/* Create the new ResultColumnList */
        newList=(ResultColumnList)getNodeFactory().getNode( C_NodeTypes.RESULT_COLUMN_LIST, getContextManager());

		/* Walk the current list - for each ResultColumn in the list, make a copy
		 * and add it to the new list.
		 */
        int size=size();

        for(int index=0;index<size;index++){
            origResultColumn=elementAt(index);

            newResultColumn=origResultColumn.cloneMe();

            newList.addResultColumn(newResultColumn);
        }
        newList.copyOrderBySelect(this);
        return newList;
    }

    /**
     * Remove any columns that may have been added for an order by clause.
     * In a query like:
     * <pre>select a from t order by b</pre> b is added to the select list
     * However in the final projection, after the sort is complete, b will have
     * to be removed.
     */
    public void removeOrderByColumns(){
        int idx=size()-1;
        for(int i=0;i<orderBySelect;i++,idx--){
            removeElementAt(idx);
        }
        orderBySelect=0;
    }

    /**
     * Walk the list and replace ResultColumn.expression with a new
     * VirtualColumnNode.  This is useful when propagating a ResultColumnList
     * up the query tree.
     * NOTE: This flavor marks all of the underlying RCs as referenced.
     *
     * @param sourceResultSet ResultSetNode that is source of value
     * @throws StandardException Thrown on error
     */
    public void genVirtualColumnNodes(ResultSetNode sourceResultSet,
                                      ResultColumnList sourceResultColumnList) throws StandardException{
        genVirtualColumnNodes(sourceResultSet,sourceResultColumnList,true);
    }


    /**
     * Walk the list and replace ResultColumn.expression with a new
     * VirtualColumnNode.  This is useful when propagating a ResultColumnList
     * up the query tree.
     *
     * @param sourceResultSet ResultSetNode that is source of value
     * @param markReferenced  Whether or not to mark the underlying RCs
     *                        as referenced
     * @throws StandardException Thrown on error
     */
    public void genVirtualColumnNodes(ResultSetNode sourceResultSet,
                                      ResultColumnList sourceResultColumnList,
                                      boolean markReferenced) throws StandardException{
        int size=size();

        for(int index=0;index<size;index++){
            ResultColumn resultColumn=elementAt(index);

			/* dts = resultColumn.getExpression().getTypeServices(); */

			/* Vectors are 0-based, VirtualColumnIds are 1-based */
            resultColumn.expression=(ValueNode)getNodeFactory().getNode(
                    C_NodeTypes.VIRTUAL_COLUMN_NODE,
                    sourceResultSet,
                    sourceResultColumnList.elementAt(index),
                    ReuseFactory.getInteger(index+1),
                    getContextManager());

			/* Mark the ResultColumn as being referenced */
            if(markReferenced){
                resultColumn.setReferenced();
            }
        }
    }

    /**
     * Walk the list and adjust the virtualColumnIds in the ResultColumns
     * by the specified amount.  If ResultColumn.expression is a VirtualColumnNode,
     * then we adjust the columnId there as well.
     *
     * @param adjust The size of the increment.
     */
    public void adjustVirtualColumnIds(int adjust){
        int size=size();

        for(int index=0;index<size;index++){
            ResultColumn resultColumn=elementAt(index);
            resultColumn.adjustVirtualColumnId(adjust);
            if(SanityManager.DEBUG){
                if(!(resultColumn.getExpression() instanceof VirtualColumnNode)){
                    SanityManager.THROWASSERT(
                            "resultColumn.getExpression() is expected to be "+
                                    "instanceof VirtualColumnNode"+
                                    " not "+
                                    resultColumn.getExpression().getClass().getName());
                }
            }

            ((VirtualColumnNode)resultColumn.getExpression()).columnId+=adjust;
        }
    }

    /**
     * Project out any unreferenced ResultColumns from the list and
     * reset the virtual column ids in the referenced ResultColumns.
     * If all ResultColumns are projected out, then the list is not empty.
     *
     * @throws StandardException Thrown on error
     */
    public void doProjection() throws StandardException{
        int numDeleted=0;
        int size=size();
        ResultColumnList deletedRCL=new ResultColumnList();
        for(int index=0;index<size;index++){
            ResultColumn resultColumn=elementAt(index);

			/* RC's for FromBaseTables are marked as referenced during binding.
			 * For other nodes, namely JoinNodes, we need to go 1 level
			 * down the RC/VCN chain to see if the RC is referenced.  This is
			 * because we propagate the referencing info from the bottom up.
			 */
            if((!resultColumn.isReferenced())
                    && (resultColumn.getExpression() instanceof VirtualColumnNode)
                    && !(((VirtualColumnNode)resultColumn.getExpression()).getSourceColumn().isReferenced())){
                // Remember the RC to delete when done
                deletedRCL.addElement(resultColumn);

				/* Remember how many we have deleted and decrement the
				 * VirtualColumnIds for all nodes which appear after us
				 * in the list.
				 */
                numDeleted++;
            }else{
				/* Decrement the VirtualColumnId for each node in the list
				 * after the 1st deleted one.
				 */
                if(numDeleted>=1)
                    resultColumn.adjustVirtualColumnId(-numDeleted);
				/* Make sure that the RC is marked as referenced! */
                resultColumn.setReferenced();
            }
        }

        // Go back and delete the RCs to be delete from the list
        for(int index=0;index<deletedRCL.size();index++){
            removeElement(deletedRCL.elementAt(index));
        }
    }

    /**
     * Check the uniqueness of the column names within a column list.
     *
     * @param errForGenCols Raise an error for any generated column names.
     * @return String    The first duplicate column name, if any.
     */
    public String verifyUniqueNames(boolean errForGenCols) throws StandardException{
        int size=size();
        Set<String> ht=new HashSet<>(size+2,(float).999);
        ResultColumn rc;

        for(int index=0;index<size;index++){
            rc=elementAt(index);
            if(errForGenCols && rc.isNameGenerated())
                throw StandardException.newException(SQLState.LANG_DB2_VIEW_REQUIRES_COLUMN_NAMES);
			/* Verify that this column's name is unique within the list */
            String colName=elementAt(index).getName();

            if(!ht.add(colName))
                return colName;
        }

		/* No duplicate column names */
        return null;
    }

    /**
     * Validate the derived column list (DCL) and propagate the info
     * from the list to the final ResultColumnList.
     *
     * @param derivedRCL The derived column list
     * @param tableName  The table name for the FromTable
     * @throws StandardException Thrown on error
     */
    public void propagateDCLInfo(ResultColumnList derivedRCL,String tableName) throws StandardException{
        String duplicateColName;

		/* Do both lists, if supplied by user, have the same degree? */
        if(derivedRCL.size()!=size()
                && !derivedRCL.getCountMismatchAllowed()){
            if(visibleSize()!=derivedRCL.visibleSize()){
                throw StandardException.newException(SQLState.LANG_DERIVED_COLUMN_LIST_MISMATCH,tableName);
            }
        }

		/* Check the uniqueness of the column names within the derived list */
        duplicateColName=derivedRCL.verifyUniqueNames(false);
        if(duplicateColName!=null){
            throw StandardException.newException(SQLState.LANG_DUPLICATE_COLUMN_NAME_DERIVED,duplicateColName);
        }

		/* We can finally copy the derived names into the final list */
        copyResultColumnNames(derivedRCL);
    }

    /**
     * Look for and reject ? parameters under ResultColumns.  This is done for
     * SELECT statements.
     *
     * @throws StandardException Thrown if a ? parameter found directly
     *                           under a ResultColumn
     */

    void rejectParameters() throws StandardException{
        int size=size();

        for(int index=0;index<size;index++){
            ResultColumn rc=elementAt(index);
            rc.rejectParameter();
        }
    }

    /**
     * Check for (and reject) XML values directly under the ResultColumns.
     * This is done for SELECT/VALUES statements.  We reject values
     * in this case because JDBC does not define an XML type/binding
     * and thus there's no standard way to pass such a type back
     * to a JDBC application.
     * <p/>
     * Note that we DO allow an XML column in a top-level RCL
     * IF that column was added to the RCL by _us_ instead of
     * by the user.  For example, if we have a table:
     * <p/>
     * create table t1 (i int, x xml)
     * <p/>
     * and the user query is:
     * <p/>
     * select i from t1 order by x
     * <p/>
     * the "x" column will be added (internally) to the RCL
     * as part of ORDER BY processing--and so we need to
     * allow that XML column to be bound without throwing
     * an error.  If, as in this case, the XML column reference
     * is invalid (we can't use ORDER BY on an XML column because
     * XML values aren't ordered), a more appropriate error
     * message should be returned to the user in later processing.
     * If we didn't allow for this, the user would get an
     * error saying that XML columns are not valid as part
     * of the result set--but as far as s/he knows, there
     * isn't such a column: only "i" is supposed to be returned
     * (the RC for "x" was added to the RCL by _us_ as part of
     * ORDER BY processing).
     * <p/>
     * ASSUMPTION: Any RCs that are generated internally and
     * added to this RCL (before this RCL is bound) are added
     * at the _end_ of the list.  If that's true, then any
     * RC with an index greater than the size of the initial
     * (user-specified) list must have been added internally
     * and will not be returned to the user.
     *
     * @throws StandardException Thrown if an XML value found
     *                           directly under a ResultColumn
     */
    void rejectXMLValues() throws StandardException{
        int sz=size();
        ResultColumn rc;
        for(int i=1;i<=sz;i++){

            if(i>initialListSize)
                // this RC was generated internally and will not
                // be returned to the user, so don't throw error.
                continue;

            rc=getResultColumn(i);
            if((rc!=null) && (rc.getType()!=null) && rc.getType().getTypeId().isXMLTypeId()){ // Disallow it.
                throw StandardException.newException(
                        SQLState.LANG_ATTEMPT_TO_SELECT_XML);
            }

        }
    }

    /**
     * Set the resultSetNumber in all of the ResultColumns.
     *
     * @param resultSetNumber The resultSetNumber
     */
    public void setResultSetNumber(int resultSetNumber){
        int size=size();

        for(int index=0;index<size;index++){
            elementAt(index).setResultSetNumber(resultSetNumber);
        }
    }

    /**
     * Mark all of the ResultColumns as redundant.
     * Useful when chopping a ResultSetNode out of a tree when there are
     * still references to its RCL.
     */
    public void setRedundant(){
        int size=size();

        for(int index=0;index<size;index++){
            elementAt(index).setRedundant();
        }
    }

    /**
     * Verify that all of the columns in the SET clause of a positioned update
     * appear in the cursor's FOR UPDATE OF list.
     *
     * @param ucl        The cursor's FOR UPDATE OF list.  (May be null.)
     * @param cursorName The cursor's name.
     * @throws StandardException Thrown on error
     */
    public void checkColumnUpdateability(String[] ucl,String cursorName) throws StandardException{
        int size=size();

        for(int index=0;index<size;index++){
            ResultColumn resultColumn=elementAt(index);

            if(resultColumn.updated() &&
                    !resultColumn.foundInList(ucl)){
                throw StandardException.newException(SQLState.LANG_COLUMN_NOT_UPDATABLE_IN_CURSOR,
                        resultColumn.getName(),
                        cursorName);
            }
        }
    }

    /**
     * Set up the result expressions for a UNION, INTERSECT, or EXCEPT:
     * o Verify union type compatiblity
     * o Get dominant type for result (type + max length + nullability)
     * o Create a new ColumnReference with dominant type and name of from this
     * RCL and make that the new expression.
     * o Set the type info for in the ResultColumn to the dominant type
     * <p/>
     * NOTE - We are assuming that caller has generated a new RCL for the UNION
     * with the same names as the left side's RCL and copies of the expressions.
     *
     * @param otherRCL     RCL from other side of the UNION.
     * @param tableNumber  The tableNumber for the UNION.
     * @param level        The nesting level for the UNION.
     * @param operatorName "UNION", "INTERSECT", or "EXCEPT"
     * @throws StandardException Thrown on error
     */
    public void setUnionResultExpression(ResultColumnList otherRCL,
                                         int tableNumber,
                                         int level,
                                         String operatorName) throws StandardException{
        TableName dummyTN;

        if(SanityManager.DEBUG){
            if(visibleSize()!=otherRCL.visibleSize()){
                SanityManager.THROWASSERT(
                        "visibleSize() = ("+
                                visibleSize()+
                                ") is expected to equal otherRCL.visibleSize ("+
                                otherRCL.visibleSize()+
                                ")");
            }

            // Generated grouping columns should have been removed for the RCL
            // of a SetOperatorNode, so that size and visible size are equal
            // (DERBY-3764).
            SanityManager.ASSERT(size()==visibleSize(), "size() and visibleSize() should be equal");
        }

		/* Make a dummy TableName to be shared by all new CRs */
        dummyTN=(TableName)getNodeFactory().getNode(
                C_NodeTypes.TABLE_NAME,
                null,
                null,
                getContextManager());

        int size=visibleSize();
        for(int index=0;index<size;index++){
            ColumnReference newCR;
            ResultColumn thisRC=elementAt(index);
            ResultColumn otherRC=otherRCL.elementAt(index);
            ValueNode thisExpr=thisRC.getExpression();
            ValueNode otherExpr=otherRC.getExpression();

            // If there is one row that is not 'autoincrement', the Union should
            // not be 'autoincrement'.
            if(!otherRC.isAutoincrementGenerated() && thisRC.isAutoincrementGenerated()){
                thisRC.resetAutoincrementGenerated();
            }
			/*
			** If there are ? parameters in the ResultColumnList of a row
			** in a table constructor, their types will not be set.  Just skip
			** these - their types will be set later.  Each ? parameter will
			** get the type of the first non-? in its column, so it can't
			** affect the final dominant type.  It's possible that all the
			** rows for a particular column will have ? parameters - this is
			** an error condition that will be caught later.
			*/
            TypeId thisTypeId=thisExpr.getTypeId();
            if(thisTypeId==null)
                continue;

            TypeId otherTypeId=otherExpr.getTypeId();
            if(otherTypeId==null)
                continue;

			/* 
			** Check type compatability.
			*/
            ClassFactory cf=getClassFactory();
            if(!unionCompatible(thisExpr,otherExpr)){
                throw StandardException.newException(SQLState.LANG_NOT_UNION_COMPATIBLE,
                        thisTypeId.getSQLTypeName(),
                        otherTypeId.getSQLTypeName(),
                        operatorName);
            }

            DataTypeDescriptor resultType=thisExpr.getTypeServices().getDominantType(
                    otherExpr.getTypeServices(),
                    cf);

            newCR=(ColumnReference)getNodeFactory().getNode(
                    C_NodeTypes.COLUMN_REFERENCE,
                    thisRC.getName(),
                    dummyTN,
                    getContextManager());
            newCR.setType(resultType);
			/* Set the tableNumber and nesting levels in newCR.
			 * If thisExpr is not a CR, then newCR cannot be
			 * correlated, hence source and nesting levels are
			 * the same.
			 */
            if(thisExpr instanceof ColumnReference){
                newCR.copyFields((ColumnReference)thisExpr);
            }else{
                newCR.setNestingLevel(level);
                newCR.setSourceLevel(level);
            }
            newCR.setTableNumber(tableNumber);
            thisRC.setExpression(newCR);
            thisRC.setType(thisRC.getTypeServices().getDominantType(otherRC.getTypeServices(),cf));

			/* DB2 requires both sides of union to have same name for the result to
			 * have that name. Otherwise, leave it or set it to a generated name */
            if(thisRC.getName()!=null && !thisRC.isNameGenerated() &&
                    otherRC.getName()!=null){
				/* Result name needs to be changed */
                if(otherRC.isNameGenerated()){
                    thisRC.setName(otherRC.getName());
                    thisRC.setNameGenerated(true);
                }else if(!thisRC.getName().equals(otherRC.getName())){
					/* Both sides have user specified names that don't match */
                    thisRC.setName(null);
                    thisRC.guaranteeColumnName();
                    thisRC.setNameGenerated(true);
                }
            }
        }
    }

    /**
     * Return true if the types of two expressions are union compatible. The rules for union
     * compatibility are found in the SQL Standard, part 2, section 7.3 (<query expression>),
     * syntax rule 20.b.ii. That in turn, refers you to section 9.3 (Result of data type combinations).
     * See, for instance, <a href="https://issues.apache.org/jira/browse/DERBY-4692">DERBY-4692</a>.
     * <p/>
     * This logic may enforce only a weaker set of rules. Here is the original comment
     * on the original logic: "We want to make sure that the types are assignable in either direction
     * and they are comparable." We may need to revisit this code to make it conform to the
     * Standard.
     */
    private boolean unionCompatible(ValueNode left,ValueNode right) throws StandardException{
        TypeId leftTypeId=left.getTypeId();
        TypeId rightTypeId=right.getTypeId();
        ClassFactory cf=getClassFactory();

        return !(!left.getTypeCompiler().storable(rightTypeId,cf) && !right.getTypeCompiler().storable(leftTypeId,cf))
                && leftTypeId.isBooleanTypeId()==rightTypeId.isBooleanTypeId();
    }

    /**
     * Do the 2 RCLs have the same type & length.
     * This is useful for UNIONs when deciding whether a NormalizeResultSet is required.
     *
     * @param otherRCL The other RCL.
     * @return boolean    Whether or not there is an exact UNION type match on the 2 RCLs.
     */
    public boolean isExactTypeAndLengthMatch(ResultColumnList otherRCL) throws StandardException{

        if(SanityManager.DEBUG){
            // The visible size of the two RCLs must be equal.
            SanityManager.ASSERT(visibleSize()==otherRCL.visibleSize(), "visibleSize() should match");
            // The generated grouping columns should have been removed from the
            // RCL of the SetOperatorNode, so size and visible size should be
            // equal (DERBY-3764).
            SanityManager.ASSERT(size()==visibleSize(), "size() and visibleSize() should match");
        }

        int size=visibleSize();
        for(int index=0;index<size;index++){
            ResultColumn thisRC=elementAt(index);
            ResultColumn otherRC=otherRCL.elementAt(index);

            if(!thisRC.getTypeServices().isExactTypeAndLengthMatch(otherRC.getTypeServices())){
                return false;
            }
        }

        return true;
    }

    /**
     * Does the column list contain any of the given column positions
     * that are updated? Implements same named routine in UpdateList.
     *
     * @param columns An array of column positions
     * @return True if this column list contains any of the given columns
     */
    public boolean updateOverlaps(int[] columns){
        int size=size();

        for(int index=0;index<size;index++){
            ResultColumn rc=elementAt(index);

            if(!rc.updated())
                continue;

            int column=rc.getColumnPosition();

            for(int col : columns){
                if(col==column)
                    return true;
            }
        }

        return false;
    }

    /**
     * Return an array that contains references to the columns in this list
     * sorted by position.
     *
     * @return The sorted array.
     */
    ResultColumn[] getSortedByPosition(){
        int size=size();
        ResultColumn[] result;
		
		/*
		** Form an array of the original ResultColumns
		*/
        result=new ResultColumn[size];

		/*
		** Put the ResultColumns in the array
		*/
        for(int index=0;index<size;index++){
            result[index]=elementAt(index);
        }

		/*
		** Sort the array by column position
		*/
        java.util.Arrays.sort(result);
        return result;
    }

    /**
     * Return an array of all my column positions, sorted in
     * ascending order.
     *
     * @return a sorted array
     */
    public int[] sortMe(){
        ResultColumn[] sortedResultColumns=getSortedByPosition();
        int[] sortedColumnIds=new int[sortedResultColumns.length];
        for(int ix=0;ix<sortedResultColumns.length;ix++){
            sortedColumnIds[ix]=sortedResultColumns[ix].getColumnPosition();
        }
        return sortedColumnIds;
    }


    /**
     * Expand this ResultColumnList by adding all columns from the given
     * table that are not in this list.  The result is sorted by column
     * position.
     *
     * @param td        The TableDescriptor for the table in question
     * @param tableName The name of the table as given in the query
     * @throws StandardException Thrown on error
     * @return A new ResultColumnList expanded to include all columns in
     * the given table.
     */
    public ResultColumnList expandToAll(TableDescriptor td,TableName tableName) throws StandardException{
        ResultColumn rc;
        ColumnDescriptor cd;
        ResultColumnList retval;
        ResultColumn[] originalRCS;
        int posn;

		/* Get a new ResultColumnList */
        retval=(ResultColumnList)getNodeFactory().getNode( C_NodeTypes.RESULT_COLUMN_LIST, getContextManager());

		/*
		** Form a sorted array of the ResultColumns
		*/
        originalRCS=getSortedByPosition();

        posn=0;
 
		/* Iterate through the ColumnDescriptors for the given table */
        ColumnDescriptorList cdl=td.getColumnDescriptorList();
        int cdlSize=cdl.size();

        for(int index=0;index<cdlSize;index++){
            cd=cdl.elementAt(index);

            if((posn<originalRCS.length) && (cd.getPosition()==originalRCS[posn].getColumnPosition())){
                rc=originalRCS[posn];
                posn++;
            }else{
				/* Build a ResultColumn/ColumnReference pair for the column */
                rc=makeColumnReferenceFromName(tableName,cd.getColumnName());

				/* Bind the new ResultColumn */
                rc.bindResultColumnByPosition(td,cd.getPosition());
            }

			/* Add the ResultColumn to the list */
            retval.addResultColumn(rc);
        }

        assert posn==originalRCS.length: "ResultColumns in original list not added to expanded ResultColumnList";

        return retval;
    }

    /**
     * Bind any untyped null nodes to the types in the given ResultColumnList.
     * Nodes that don't know their type may pass down nulls to
     * children nodes.  In the case of something like a union, it knows
     * to try its right and left result sets against each other.
     * But if a null reaches us, it means we have a null type that
     * we don't know how to handle.
     *
     * @param bindingRCL The ResultColumnList with the types to bind to.
     * @throws StandardException Thrown on error
     */
    public void bindUntypedNullsToResultColumns(ResultColumnList bindingRCL) throws StandardException{
        if(bindingRCL==null){
            throw StandardException.newException(SQLState.LANG_NULL_IN_VALUES_CLAUSE);
        }

        assert bindingRCL.size()>=this.size(): "More columns in result column list than in base table";

        int size=size();
        for(int index=0;index<size;index++){
            ResultColumn bindingRC=bindingRCL.elementAt(index);
            ResultColumn thisRC=elementAt(index);

            thisRC.typeUntypedNullExpression(bindingRC);
        }
    }

    /**
     * Mark all the columns in this list as updated by an update statement.
     */
    void markUpdated(){
        int size=size();

        for(int index=0;index<size;index++){
            elementAt(index).markUpdated();
        }
    }

    /**
     * Mark all the (base) columns in this list as updatable by a positioned update
     * statement.  This is necessary
     * for positioned update statements, because we expand the column list
     * to include all the columns in the base table, and we need to be able
     * to tell which ones the user is really trying to update so we can
     * determine correctly whether all the updated columns are in the
     * "for update" list.
     */
    void markUpdatableByCursor(){
        int size=size();

        for(int index=0;index<size;index++){
            //determine if the column is a base column and not a derived column
            if(elementAt(index).getSourceTableName()!=null)
                elementAt(index).markUpdatableByCursor();
        }
    }

    /**
     * Verify that all of the column names in this list are contained
     * within the ColumnDefinitionNodes within the TableElementList.
     *
     * @return String    The 1st column name, if any, that is not in the list.
     */
    public String verifyCreateConstraintColumnList(TableElementList tel){
        int size=size();

        for(int index=0;index<size;index++){
            String colName=elementAt(index).getName();

            if(!tel.containsColumnName(colName)){
                return colName;
            }
        }
        return null;
    }

    /**
     * Export the result column names to the passed in String[].
     *
     * @param columnNames String[] to hold the column names.
     */
    public void exportNames(String[] columnNames){
        assert size() == columnNames.length:"size() ("+ size()+ ") is expected to equal columnNames.length ("+ columnNames.length+ ")";

        int size=size();
        for(int index=0;index<size;index++){
            columnNames[index]=elementAt(index).getName();
        }
    }

    /**
     * Mark as updatable all the columns in this result column list
     * that match the columns in the given update column list.
     *
     * @param updateColumns A ResultColumnList representing the columns
     *                      to be updated.
     */
    void markUpdated(ResultColumnList updateColumns){
        ResultColumn updateColumn;
        ResultColumn resultColumn;

        int size=updateColumns.size();

        for(int index=0;index<size;index++){
            updateColumn=updateColumns.elementAt(index);

            resultColumn=getResultColumn(updateColumn.getName());

			/*
			** This ResultColumnList may not be bound yet - for update
			** statements, we mark the updated columns *before* we bind
			** the RCL.  This ordering is important because we add columns
			** to the RCL after marking the update columns and before
			** binding.
			**
			** So, it can happen that there is an invalid column name in
			** the list.  This condition will cause an exception when the
			** RCL is bound.  Just ignore it for now.
			*/
            if(resultColumn!=null){
                resultColumn.markUpdated();
            }
        }
    }

    /**
     * Mark all the columns in the select sql that this result column list represents
     * as updatable if they match the columns in the given update column list.
     *
     * @param updateColumns A Vector representing the columns
     *                      to be updated.
     */
    void markColumnsInSelectListUpdatableByCursor(List<String> updateColumns){
        commonCodeForUpdatableByCursor(updateColumns,true);
    }

    /**
     * dealingWithSelectResultColumnList true means we are dealing with
     * ResultColumnList for a select sql. When dealing with ResultColumnList for
     * select sql, it is possible that not all the updatable columns are
     * projected in the select column list and hence it is possible that we may
     * not find the column to be updated in the ResultColumnList and that is why
     * special handling is required when dealingWithSelectResultColumnList is true.
     * eg select c11, c13 from t1 for update of c11, c12
     * In the eg above, we will find updatable column c11 in the select column
     * list but we will not find updatable column c12 in the select column list
     */
    private void commonCodeForUpdatableByCursor(List<String> updateColumns,boolean dealingWithSelectResultColumnList){
		/*
		** If there is no update column list, or the list is empty, then it means that
		** all the columns which have a base table associated with them are updatable.
		*/
        if((updateColumns==null) || (updateColumns.size()==0)){
            markUpdatableByCursor();
        }else{
            ResultColumn resultColumn;
            String columnName;

            for(String updateColumn : updateColumns){
                columnName=updateColumn;

                resultColumn=getResultColumn(columnName);
                if(SanityManager.DEBUG){
                    if(resultColumn==null && !dealingWithSelectResultColumnList){
                        SanityManager.THROWASSERT("No result column found with name "+ columnName);
                    }
                }
                //Following if means the column specified in FOR UPDATE clause is not
                //part of the select list
                if(resultColumn==null && dealingWithSelectResultColumnList)
                    continue;
                assert resultColumn!=null;
                resultColumn.markUpdatableByCursor();
            }
        }
    }

    /**
     * Mark as updatable all the columns in this result column list
     * that match the columns in the given update column list
     *
     * @param updateColumns A Vector representing the columns
     *                      to be updated.
     */
    void markUpdatableByCursor(List<String> updateColumns){
        commonCodeForUpdatableByCursor(updateColumns,false);
    }

    /**
     * Returns true if the given column position is for a column that will
     * be or could be updated by the positioned update of a cursor.
     *
     * @param columnPosition The position of the column in question
     * @return true if the column is updatable
     */
    boolean updatableByCursor(int columnPosition){
        return getResultColumn(columnPosition).updatableByCursor();
    }


    /**
     * Return whether or not this RCL can be flattened out of a tree.
     * It can only be flattened if the expressions are all cloneable.
     *
     * @return boolean    Whether or not this RCL can be flattened out of a tree.
     */
    public boolean isCloneable(){
        boolean retcode=true;
        int size=size();

        for(int index=0;index<size;index++){
            ResultColumn rc=elementAt(index);

            if(!rc.getExpression().isCloneable()){
                retcode=false;
                break;
            }
        }

        return retcode;
    }

    /**
     * Remap all ColumnReferences in this tree to be clones of the
     * underlying expression.
     *
     * @throws StandardException Thrown on error
     */
    public void remapColumnReferencesToExpressions() throws StandardException{
        int size=size();
        for(int index=0;index<size;index++){
            ResultColumn rc=elementAt(index);

            // The expression may be null if this column is an identity
            // column generated always. If the expression is not null, it
            // is a ColumnReference; we call through to the ColumnReference
            // to give it a chance to remap itself from the outer query
            // node to this one.
            if(rc.getExpression()!=null)
                rc.setExpression(rc.getExpression().remapColumnReferencesToExpressions());
        }
    }

    /*
	** Indicate that the conglomerate is an index, so we need to generate a
	** RowLocation as the last column of the result set.
	**
	** @param cid	The conglomerate id of the index
	*/
    void setIndexRow(long cid,boolean forUpdate){
        indexRow=true;
        conglomerateId=cid;
        this.forUpdate=forUpdate;
    }

    /**
     * Return whether or not this RCL contains an AllResultColumn.
     * This is useful when dealing with SELECT * views which
     * reference tables that may have had columns added to them via
     * ALTER TABLE since the view was created.
     *
     * @return Whether or not this RCL contains an AllResultColumn.
     */
    public boolean containsAllResultColumn(){
        boolean containsAllResultColumn=false;

        int size=size();
        for(int index=0;index<size;index++){
            if(elementAt(index) instanceof AllResultColumn){
                containsAllResultColumn=true;
                break;
            }
        }

        return containsAllResultColumn;
    }

    /**
     * Count the number of RCs in the list that are referenced.
     *
     * @return The number of RCs in the list that are referenced.
     */
    public int countReferencedColumns(){
        int numReferenced=0;

        int size=size();
        for(int index=0;index<size;index++){
            ResultColumn rc=elementAt(index);
            if(rc.isReferenced()){
                numReferenced++;
            }
        }
        return numReferenced;
    }

    /**
     * Record the column ids of the referenced columns in the specified array.
     *
     * @param idArray int[] for column ids
     * @param basis   0 (for 0-based ids) or 1 (for 1-based ids)
     */
    public void recordColumnReferences(int[] idArray,int basis){
        int currArrayElement=0;
        int size=size();
        for(int index=0;index<size;index++){
            ResultColumn rc=elementAt(index);

            if(rc.isReferenced()){
                idArray[currArrayElement++]=index+basis;
            }
        }
    }

    /**
     * Get the position of first result column with the given name.
     *
     * @param name  Name of the column
     * @param basis 0 (for 0-based ids) or 1 (for 1-based ids)
     */
    public int getPosition(String name,int basis){
        int size=size();
        for(int index=0;index<size;index++){
            ResultColumn rc=elementAt(index);

            if(name.equals(rc.getName())){
                return index+basis;
            }
        }

        return -1;
    }

    /**
     * Record the top level ColumnReferences in the specified array
     * and table map
     * This is useful when checking for uniqueness conditions.
     * NOTE: All top level CRs assumed to be from the same table.
     * The size of the array is expected to be the # of columns
     * in the table of interest + 1, so we use 1-base column #s.
     *
     * @param colArray1   boolean[] for columns
     * @param tableColMap JBitSet[] for tables
     * @param tableNumber Table number of column references
     */
    public void recordColumnReferences(boolean[] colArray1,JBitSet[] tableColMap, int tableNumber){
        int size=size();
        for(int index=0;index<size;index++){
            int columnNumber;
            ResultColumn rc=elementAt(index);

            if(!(rc.getExpression() instanceof ColumnReference)){
                continue;
            }

            columnNumber=((ColumnReference)rc.getExpression()).getColumnNumber();
            colArray1[columnNumber]=true;
            tableColMap[tableNumber].set(columnNumber);
        }
    }

    /**
     * Return whether or not all of the RCs in the list whose
     * expressions are ColumnReferences are
     * from the same table.  One place this
     * is useful for distinct elimination based on the existence
     * of a uniqueness condition.
     *
     * @return    -1 if all of the top level CRs in the RCL
     * are not ColumnReferences from the same table,
     * else the tableNumber
     */
    int allTopCRsFromSameTable(){
        int tableNumber=-1;

        int size=size();
        for(int index=0;index<size;index++){
            ResultColumn rc=elementAt(index);
            ValueNode vn=rc.getExpression();
            if(!(vn instanceof ColumnReference)){
                continue;
            }

            // Remember the tableNumber from the first CR
            ColumnReference cr=(ColumnReference)vn;
            if(tableNumber==-1){
                tableNumber=cr.getTableNumber();
            }else if(tableNumber!=cr.getTableNumber()){
                return -1;
            }
        }
        return tableNumber;
    }

    /**
     * Clear the column references from the RCL. (Restore RCL back to a state
     * where none of the RCs are marked as referenced.)
     */
    public void clearColumnReferences(){
        int size=size();
        for(int index=0;index<size;index++){
            ResultColumn rc=elementAt(index);

            if(rc.isReferenced()){
                rc.setUnreferenced();
            }
        }
    }

    /**
     * Copy the referenced RCs from this list to the supplied target list.
     *
     * @param targetList The list to copy to
     */
    public void copyReferencedColumnsToNewList(ResultColumnList targetList){
        int size=size();
        for(int index=0;index<size;index++){
            ResultColumn rc=elementAt(index);

            if(rc.isReferenced()){
                targetList.addElement(rc);
            }
        }
    }

    /**
     * Or in any isReferenced booleans from the virtual column chain. That is the isReferenced bits on each
     * ResultColumn on the list will be set if the ResultColumn is referenced or if any VirtualColumnNode in its
     * expression chain refers to a referenced column.
     */
    void pullVirtualIsReferenced(){
        int size=size();
        for(int index=0;index<size;index++){
            ResultColumn rc=elementAt(index);
            rc.pullVirtualIsReferenced();
        }
    } // end of pullVirtualIsReferenced

    /**
     * Set the value of whether or not a count mismatch is allowed between
     * this RCL, as a derived column list, and an underlying RCL.  This is allowed
     * for SELECT * views when an underlying table has had columns added to it
     * via ALTER TABLE.
     *
     * @param allowed Whether or not a mismatch is allowed.
     */
    protected void setCountMismatchAllowed(boolean allowed){
        countMismatchAllowed=allowed;
    }

    /**
     * Return whether or not a count mismatch is allowed between this RCL,
     * as a derived column list, and an underlying RCL.  This is allowed
     * for SELECT * views when an underlying table has had columns added to it
     * via ALTER TABLE.
     * <p/>
     * return Whether or not a mismatch is allowed.
     */

    protected boolean getCountMismatchAllowed(){
        return countMismatchAllowed;
    }

    /**
     * Get the size of all the columns added
     * together.  Does <B>NOT</B> include the
     * column overhead that the store requires.
     * Also, will be a very rough estimate for
     * user types.
     *
     * @return the size
     */
    public int getTotalColumnSize(){
        int colSize=0;
        int size=size();
        for(int index=0;index<size;index++){
            colSize+=elementAt(index).getMaximumColumnSize();
        }
        return colSize;
    }

    /**
     * Generate an RCL to match the contents of a ResultSetMetaData.
     * This is useful when dealing with VTIs.
     *
     * @param rsmd          The ResultSetMetaData.
     * @param tableName     The TableName for the BCNs.
     * @param javaClassName The name of the VTI
     * @throws StandardException Thrown on error
     */
    public void createListFromResultSetMetaData(ResultSetMetaData rsmd,
                                                TableName tableName,
                                                String javaClassName) throws StandardException{
        try{
            // JDBC columns #s are 1-based
            // Check to make sure # of columns >= 1
            int numColumns=rsmd.getColumnCount();

            if(numColumns<=0){
                throw StandardException.newException(SQLState.LANG_INVALID_V_T_I_COLUMN_COUNT,
                        javaClassName,String.valueOf(numColumns));
            }

            for(int index=1;index<=numColumns;index++){
                boolean nullableResult=
                        (rsmd.isNullable(index)!=ResultSetMetaData.columnNoNulls);

                TypeId cti;

                int jdbcColumnType=rsmd.getColumnType(index);

                switch(jdbcColumnType){
                    case Types.JAVA_OBJECT:
                    case Types.OTHER:{
                        cti=TypeId.getUserDefinedTypeId(rsmd.getColumnTypeName(index),false);
                        break;
                    }
                    default:{
                        cti=TypeId.getBuiltInTypeId(jdbcColumnType);
                        break;
                    }
                }

                // Handle the case where a VTI returns a bad column type
                if(cti==null){
                    throw StandardException.newException(SQLState.LANG_BAD_J_D_B_C_TYPE_INFO,Integer.toString(index));
                }

                // Get the maximum byte storage for this column
                int maxWidth;

				/* Get maximum byte storage from rsmd for variable
				 * width types, set it to MAXINT for the long types,
				 * otherwise get it from the TypeId
				 */
                if(cti.variableLength()){
                    maxWidth=rsmd.getColumnDisplaySize(index);
                }else if(jdbcColumnType==Types.LONGVARCHAR || jdbcColumnType==Types.LONGVARBINARY){
                    maxWidth=Integer.MAX_VALUE;
                }else{
                    maxWidth=0;
                }

                int precision=cti.isDecimalTypeId()?rsmd.getPrecision(index):0;
                int scale=cti.isDecimalTypeId()?rsmd.getScale(index):0;
                DataTypeDescriptor dts=new DataTypeDescriptor(cti, precision, scale, nullableResult, maxWidth);
                addColumn(tableName,rsmd.getColumnName(index),dts);
            }
        }catch(Throwable t){
            if(t instanceof StandardException){
                throw (StandardException)t;
            }else{
                throw StandardException.unexpectedUserException(t);
            }
        }
    }

    /**
     * Add a column to the list given a tablename, columnname, and datatype.
     */
    public void addColumn(TableName tableName,String columnName,DataTypeDescriptor dts)
            throws StandardException{
        ValueNode bcn=(ValueNode)getNodeFactory().getNode(
                C_NodeTypes.BASE_COLUMN_NODE,
                columnName,
                tableName,
                dts,
                getContextManager());
        ResultColumn rc=(ResultColumn)getNodeFactory().getNode(
                C_NodeTypes.RESULT_COLUMN,
                columnName,
                bcn,
                getContextManager());
        rc.setType(dts);
        addResultColumn(rc);
    }

    /**
     * Add an RC to the end of the list for the RID from an index.
     * NOTE: RC.expression is a CurrentRowLocationNode.  This was previously only used
     * for non-select DML.  We test for this node when generating the holder above
     * and generate the expected code.  (We really should create yet another new node
     * type with its own code generation.)
     *
     * @throws StandardException Thrown on error
     */
    public void addRCForRID() throws StandardException{
        ResultColumn rowLocationColumn;
        CurrentRowLocationNode rowLocationNode;

		/* Generate the RowLocation column */
        rowLocationNode=(CurrentRowLocationNode)getNodeFactory().getNode(C_NodeTypes.CURRENT_ROW_LOCATION_NODE,getContextManager());
        rowLocationColumn=
                (ResultColumn)getNodeFactory().getNode(
                        C_NodeTypes.RESULT_COLUMN,
                        "",
                        rowLocationNode,
                        getContextManager());
        rowLocationColumn.markGenerated();

		/* Append to the ResultColumnList */
        addResultColumn(rowLocationColumn);
    }

    /**
     * Walk the list and mark all RCs as unreferenced.  This is useful
     * when recalculating which RCs are referenced at what level like
     * when deciding which columns need to be returned from a non-matching
     * index scan (as opposed to those returned from the base table).
     *
     * @throws StandardException Thrown on error
     */
    public void markAllUnreferenced() throws StandardException{
        int size=size();

        for(int index=0;index<size;index++){
            ResultColumn resultColumn=elementAt(index);
            resultColumn.setUnreferenced();
        }
    }

    /**
     * Determine if all of the RC.expressions are columns in the source result set.
     * This is useful for determining if we need to do reflection
     * at execution time.
     *
     * @param sourceRS The source ResultSet.
     * @return Whether or not all of the RC.expressions are columns in the source result set.
     */
    boolean allExpressionsAreColumns(ResultSetNode sourceRS){
        int size=size();

        for(int index=0;index<size;index++){
            ResultColumn resultColumn;
            ValueNode expr;

            resultColumn=elementAt(index);
            //DERBY-4631
            //Following if condition if true means that the 
            // ResultColumn is a join column for a RIGHT OUTER
            // JOIN with USING/NATURAL clause. At execution 
            // time, a join column's value should be determined 
            // by generated code which is equivalent to 
            // COALESCE(leftTableJoinColumn,rightTableJoinColumn).
            // By returning false here, we allow Derby to generate
            // code for functionality equivalent to COALESCE to
            // determine join column's value. 
            if(resultColumn.isRightOuterJoinUsingClause())
                return false;

            expr=resultColumn.getExpression();
            if(!(expr instanceof VirtualColumnNode) && !(expr instanceof ColumnReference)){
                return false;
            }

			/* If the expression is a VirtualColumnNode, make sure that the column
			 * is coming from the source result set, ie, that it is not a correlated
			 * column.
			 */
            if(expr instanceof VirtualColumnNode){
                VirtualColumnNode vcn=(VirtualColumnNode)expr;
                if(vcn.getSourceResultSet()!=sourceRS){
                    vcn.setCorrelated();
                    return false;
                }
            }

			/* Make sure this is not a correlated CR */
            if(expr instanceof ColumnReference){
                ColumnReference cr=(ColumnReference)expr;
                if(cr.getCorrelated()){
                    return false;
                }

                /*if (cr.getColumnName().compareToIgnoreCase("ROWID") == 0) {
                    return false;
                }*/
            }
        }
        return true;
    }

    /**
     * Map the source columns to these columns.  Build an array to represent the mapping.
     * For each RC, if the expression is simply a VCN or a CR then set the array element to be
     * the virtual column number of the source RC.  Otherwise, set the array element to
     * -1.
     * This is useful for determining if we need to do reflection
     * at execution time.
     * <p/>
     * Also build an array of boolean for columns that point to the same virtual
     * column and have types that are streamable to be able to determine if
     * cloning is needed at execution time.
     *
     * @return Array representiong mapping of RCs to source RCs.
     */
    ColumnMapping mapSourceColumns(){
        int[] mapArray=new int[size()];
        boolean[] cloneMap=new boolean[size()];

        ResultColumn resultColumn;

        // key: virtual column number, value: index
        Map<Integer, Integer> seenMap=new HashMap<>();

        int size=size();

        for(int index=0;index<size;index++){
            resultColumn=elementAt(index);
            if(resultColumn.getExpression() instanceof VirtualColumnNode){
                VirtualColumnNode vcn=(VirtualColumnNode)resultColumn.getExpression();

                // Can't deal with correlated VCNs
                if(vcn.getCorrelated()){
                    mapArray[index]=-1;
                }else{
                    // Virtual column #s are 1-based
                    ResultColumn rc=vcn.getSourceColumn();
                    updateArrays(mapArray,cloneMap,seenMap,rc,index);

                }
            }else if(resultColumn.isRightOuterJoinUsingClause()){
                mapArray[index]=-1;
            }else if(resultColumn.getExpression() instanceof ColumnReference){
                ColumnReference cr=(ColumnReference)resultColumn.getExpression();

                // Can't deal with correlated CRs
                if(cr.getCorrelated()){
                    mapArray[index]=-1;
                }else{
                    // Virtual column #s are 1-based
                    ResultColumn rc=cr.getSource();

                    updateArrays(mapArray,cloneMap,seenMap,rc,index);
                }
            }else{
                mapArray[index]=-1;
            }
        }

        return new ColumnMapping(mapArray,cloneMap);
    }

    /**
     * Set the nullability of every ResultColumn in this list
     *
     * @throws StandardException
     */
    public void setNullability(boolean nullability) throws StandardException{
        int size=size();

        for(int index=0;index<size;index++){
            ResultColumn resultColumn=elementAt(index);
            resultColumn.setNullability(nullability);
        }
    }

    private int getMaxColumnPosition() {
        int i = 0;
        for (ResultColumn rc: this) {
            i= Math.max(rc.getColumnPosition(),i);
        }
        return i;
    }

    private int getMaxStoragePosition() {
        int i = 0;
        for (ResultColumn rc: this) {
            i= Math.max(rc.getStoragePosition(),i);
        }
        return i;
    }


    /**
     * Generate a FormatableBitSet representing the columns that are referenced in this RCL.
     * The caller decides if they want this FormatableBitSet if every RC is referenced.
     *
     * @param positionedUpdate Whether or not the scan that the RCL
     *                         belongs to is for update w/o a column list
     * @param always           Whether or not caller always wants a non-null FormatableBitSet if
     *                         all RCs are referenced.
     * @param onlyBCNs         If true, only set bit if expression is a BaseColumnNode,
     *                         otherwise set bit for all referenced RCs.
     * @return The FormatableBitSet representing the referenced RCs.
     */

    FormatableBitSet getReferencedFormatableBitSet(boolean positionedUpdate,boolean always,boolean onlyBCNs, boolean isIndex){
        int index;
        int colsAdded=0;
        int size=size();
        FormatableBitSet newReferencedCols=new FormatableBitSet(getMaxStoragePosition());

		/*
		** For an updatable cursor, we need
		** all columns.
		*/
        if(positionedUpdate){
            if(always){
				/* Set all bits in the bit map */
                for(index=0;index<size;index++){
                    if (isIndex)
                    newReferencedCols.set(index);
                    else
                        newReferencedCols.set(this.getResultColumn(index).getStoragePosition()-1);
                }

                return newReferencedCols;
            }else{
                return null;
            }
        }

        for(index=0;index<size;index++){
            ResultColumn oldCol=elementAt(index);
            if(oldCol.isReferenced()){
				/* Skip RCs whose expression is not a BCN
				 * when requested to do so.
				 */
                if(onlyBCNs && !(oldCol.getExpression() instanceof BaseColumnNode)){
                    continue;
                }
                if (isIndex) {
                newReferencedCols.set(index);
                colsAdded++;
            }
                else {
                    newReferencedCols.set(oldCol.getStoragePosition()-1);
                }
            }
        }

		/* Return the FormatableBitSet if not all RCs are referenced or if
		 * the caller always wants the FormatableBitSet returned.
		 */
            return newReferencedCols;
    }

    /**
     * Create a new, compacted RCL based on the referenced RCs
     * in this list.  If the RCL being compacted is for an
     * updatable scan, then we simply return this.
     * <p/>
     * The caller tells us whether or not they want a new list
     * if there is no compaction because all RCs are referenced.
     * This is useful in the case where the caller needs a new
     * RCL for existing RCs so that it can augment the new list.
     *
     * @param positionedUpdate Whether or not the scan that the RCL
     *                         belongs to is for update w/o a column list
     * @param always           Whether or not caller always wants a new RCL
     * @return The compacted RCL if compaction occurred, otherwise return this RCL.
     * @throws StandardException Thrown on error
     */
    ResultColumnList compactColumns(boolean positionedUpdate,boolean always)
            throws StandardException{
        int index;
        int colsAdded=0;

		/*
		** For an updatable cursor, we need
		** all columns.
		*/
        if(positionedUpdate){
            return this;
        }

        ResultColumnList newCols=(ResultColumnList)getNodeFactory().getNode(
                C_NodeTypes.RESULT_COLUMN_LIST,
                getContextManager());

        int size=size();
        for(index=0;index<size;index++){
            ResultColumn oldCol=elementAt(index);
            if(oldCol.isReferenced()){
                newCols.addResultColumn(oldCol);
                colsAdded++;
            }
        }

		/* Return new RCL if we found unreferenced columns or if
		 * the caller always wants a new list. 
		 */
        if(colsAdded!=index || always){
            return newCols;
        }else{
            return this;
        }
    }

    /**
     * Remove the columns which are join columns (in the
     * joinColumns RCL) from this list.  This is useful
     * for a JOIN with a USING clause.
     *
     * @param joinColumns The list of join columns
     */
    void removeJoinColumns(ResultColumnList joinColumns){
        int jcSize=joinColumns.size();
        for(int index=0;index<jcSize;index++){
            ResultColumn joinRC=joinColumns.elementAt(index);
            String columnName=joinRC.getName();

            // columnName should always be non-null
            if(SanityManager.DEBUG){
                SanityManager.ASSERT(columnName!=null,
                        "columnName should be non-null");
            }

            ResultColumn rightRC=getResultColumn(columnName);

            // Remove the RC from this list.
            if(rightRC!=null){
                removeElement(rightRC);
            }
        }
    }

    /**
     * Get the join columns from this list.
     * This is useful for a join with a USING clause.
     * (ANSI specifies that the join columns appear 1st.)
     *
     * @param joinColumns A list of the join columns.
     * @return A list of the join columns from this list
     */
    ResultColumnList getJoinColumns(ResultColumnList joinColumns)
            throws StandardException{
        ResultColumnList newRCL=new ResultColumnList();

		/* Find all of the join columns and put them 1st on the
		 * new RCL.
		 */
        int jcSize=joinColumns.size();
        for(int index=0;index<jcSize;index++){
            ResultColumn joinRC=joinColumns.elementAt(index);
            String columnName=joinRC.getName();

            // columnName should always be non-null
            if(SanityManager.DEBUG){
                SanityManager.ASSERT(columnName!=null,
                        "columnName should be non-null");
            }

            ResultColumn xferRC=getResultColumn(columnName);

            if(xferRC==null){
                throw StandardException.newException(
                        SQLState.LANG_COLUMN_NOT_FOUND,columnName);
            }

            // Add the RC to the new list.
            newRCL.addElement(xferRC);
        }
        return newRCL;
    }

    /**
     * Reset the virtual column ids for all of the
     * underlying RCs.  (Virtual column ids are 1-based.)
     */
    void resetVirtualColumnIds(){
        int size=size();

        for(int index=0;index<size;index++){
			/* ResultColumns are 1-based */
            elementAt(index).setVirtualColumnId(index+1);
        }
    }

    /**
     * Return whether or not the same result row can be used for all
     * rows returned by the associated ResultSet.  This is possible
     * if all entries in the list are constants or AggregateNodes.
     *
     * @return Whether or not the same result row can be used for all
     * rows returned by the associated ResultSet.
     */
    boolean reusableResult(){
        int size=size();

        for(int index=0;index<size;index++){
            ResultColumn rc=elementAt(index);

            if((rc.getExpression() instanceof ConstantNode) ||
                    (rc.getExpression() instanceof AggregateNode)){
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * Get an array of strings for all the columns
     * in this RCL.
     *
     * @return the array of strings
     */
    public String[] getColumnNames(){
        String strings[]=new String[size()];

        int size=size();

        for(int index=0;index<size;index++){
            ResultColumn resultColumn=elementAt(index);
            strings[index]=resultColumn.getName();
        }
        return strings;
    }

    /**
     * Replace any DEFAULTs with the associated tree for the default if
     * allowed, or flag.
     *
     * @param ttd           The TableDescriptor for the target table.
     * @param tcl           The RCL for the target table.
     * @param allowDefaults true if allowed
     * @throws StandardException Thrown on error
     */
    void replaceOrForbidDefaults(TableDescriptor ttd, ResultColumnList tcl, boolean allowDefaults) throws StandardException{
        int size=size();

        for(int index=0;index<size;index++){
            ResultColumn rc=elementAt(index);

            if(rc.isDefaultColumn()){
                if(!allowDefaults){
                    throw StandardException.newException( SQLState.LANG_INVALID_USE_OF_DEFAULT);
                }

                //				DefaultNode defaultNode = (DefaultNode) rc.getExpression();
                // Get ColumnDescriptor by name or by position?
                ColumnDescriptor cd=null;
                if(tcl==null){
                    cd=ttd.getColumnDescriptor(index+1);
                }else if(index<tcl.size()){
                    ResultColumn trc=tcl.elementAt(index);
                    cd=ttd.getColumnDescriptor(trc.getName());
                }

                // Too many RCs if no ColumnDescriptor
                if(cd==null){
                    throw StandardException.newException(SQLState.LANG_TOO_MANY_RESULT_COLUMNS, ttd.getQualifiedName());
                }

                if(cd.isAutoincrement()){
                    rc.setAutoincrementGenerated();
                } // end of if ()

                DefaultInfoImpl defaultInfo=(DefaultInfoImpl)cd.getDefaultInfo();

                //
                // For generated columns, we don't have enough context at this
                // point to bind the generation clause (the default) and
                // unfortunately that step occurs very soon after defaults are substituted in. The
                // parsing and binding of generation clauses happens considerably
                // later on. At this juncture, we can be patient and just plug
                // in a NULL literal as a placeholder. For generated columns,
                // the generation clause tree is plugged in in DMLModStatementNode.parseAndBindGenerationClauses().
                //
                if((defaultInfo!=null) && !defaultInfo.isGeneratedColumn()){
					/* Query is dependent on the DefaultDescriptor */
                    DefaultDescriptor defaultDescriptor=cd.getDefaultDescriptor(getDataDictionary());
                    getCompilerContext().createDependency(defaultDescriptor);

                    rc.setExpression(
                            DefaultNode.parseDefault(
                                    defaultInfo.getDefaultText(),
                                    getLanguageConnectionContext(),
                                    getCompilerContext()));
                }else{
                    rc.setExpression(
                            (ValueNode)getNodeFactory().getNode(
                                    C_NodeTypes.UNTYPED_NULL_CONSTANT_NODE,
                                    getContextManager()));
                    rc.setWasDefaultColumn(true);
                }
                rc.setDefaultColumn(false);
            }
        }
    }

    /**
     * Walk the RCL and check for DEFAULTs.  DEFAULTs
     * are invalid at the time that this method is called,
     * so we throw an exception if found.
     * NOTE: The grammar allows:
     * VALUES DEFAULT;
     *
     * @throws StandardException Thrown on error
     */
    void checkForInvalidDefaults() throws StandardException{
        int size=size();

        for(int index=0;index<size;index++){
            ResultColumn rc=elementAt(index);

            if(rc.isAutoincrementGenerated())
                continue;

            if(rc.isDefaultColumn()){
                throw StandardException.newException(SQLState.LANG_INVALID_USE_OF_DEFAULT);
            }
        }
    }

    /**
     * Verify that all of the RCs in this list are comparable.
     *
     * @throws StandardException Thrown on error
     */
    void verifyAllOrderable() throws StandardException{
        int size=size();

        for(int index=0;index<size;index++){
            ResultColumn rc=elementAt(index);
            rc.verifyOrderable();
        }
    }

    /**
     * Build this ResultColumnList from a table description and
     * an array of column IDs.
     *
     * @throws StandardException Thrown on error
     * @param    table        describes the table
     * @param    columnIDs    column positions in that table (1-based)
     */
    public void populate ( TableDescriptor table, int[] columnIDs ) throws StandardException{
        if(columnIDs==null){
            return;
        }

        String columnName;
        int columnPosition;
        ResultColumn rc;

        for(int columnID : columnIDs){
            columnPosition=columnID;
            columnName=table.getColumnDescriptor(columnPosition).getColumnName();

            rc=makeColumnFromName(columnName);

            addResultColumn(rc);
        }

    }

    private ResultColumn makeColumnFromName(String columnName) throws StandardException{
        return (ResultColumn)getNodeFactory().getNode(C_NodeTypes.RESULT_COLUMN,columnName,null,getContextManager());
    }

    private ResultColumn makeColumnReferenceFromName ( TableName tableName, String columnName ) throws StandardException{
        ContextManager cm=getContextManager();
        NodeFactory nodeFactory=getNodeFactory();

        return (ResultColumn)nodeFactory.getNode(
                C_NodeTypes.RESULT_COLUMN,
                columnName,
                nodeFactory.getNode(C_NodeTypes.COLUMN_REFERENCE, columnName, tableName, cm),
                cm
        );
    }

    /**
     * check if any autoincrement or generated columns exist in the result column list.
     * called from insert or update where you cannot insert/update the value
     * of a generated or autoincrement column.
     *
     * @throws StandardException If the column is an ai column
     */
    public void forbidOverrides(ResultColumnList sourceRSRCL) throws StandardException{
        int size=size();

        for(int index=0;index<size;index++){
            ResultColumn rc=elementAt(index);
            ResultColumn sourceRC= (sourceRSRCL==null)?null:sourceRSRCL.elementAt(index);
            ColumnDescriptor cd=rc.getTableColumnDescriptor();

            if((cd!=null) && cd.hasGenerationClause()){
                if((sourceRC!=null) && !sourceRC.hasGenerationClause() && !sourceRC.wasDefaultColumn()){
                    throw StandardException.newException(SQLState.LANG_CANT_OVERRIDE_GENERATION_CLAUSE,rc.getName());
                }

                if(sourceRC!=null){
                    sourceRC.setColumnDescriptor(cd.getTableDescriptor(),cd);
                }
            }

            if((cd!=null) && (cd.isAutoincrement())){
                if((sourceRC!=null) && (sourceRC.isAutoincrementGenerated())){
                    sourceRC.setColumnDescriptor(cd.getTableDescriptor(),cd);

                }else{
                    if(cd.isAutoincAlways())
                        throw StandardException.newException(SQLState.LANG_AI_CANNOT_MODIFY_AI, rc.getName());
                }
            }
        }
    }

    public void incOrderBySelect(){
        orderBySelect++;
    }

    private void decOrderBySelect(){
        orderBySelect--;
    }

    public int getOrderBySelect(){
        return orderBySelect;
    }

    public void copyOrderBySelect(ResultColumnList src){
        orderBySelect=src.orderBySelect;
    }

    /* ****
	 * Take note of the size of this RCL _before_ we start
	 * processing/binding it.  This is so that, at bind time,
	 * we can tell if any columns in the RCL were added
	 * internally by us (i.e. they were not specified by the
	 * user and thus will not be returned to the user).
	 */
    protected void markInitialSize(){
        initialListSize=size();
    }

    private int numGeneratedColumns(){
        int numGenerated=0;
        int sz=size();
        boolean inVisibleRange=false;
        for(int i=sz-1;i>=0;i--){
            ResultColumn rc=elementAt(i);
            if(rc.isGenerated()){
                assert !inVisibleRange: "Encountered generated column in expected visible range at rcl["+i+"]";
                numGenerated++;
            }else{
                // We are counting down, so as soon as we see one visible column, the rest should be th same
                inVisibleRange=true;
            }
        }
        return numGenerated;
    }

    /**
     * @return the number of generated columns in this RCL.
     */
    int numGeneratedColumnsForGroupBy(){
        int numGenerated=0;
        int sz=size();
        for(int i=sz-1;i>=0;i--){
            ResultColumn rc=elementAt(i);
            if(rc.isGenerated() && rc.isGroupingColumn()){
                numGenerated++;
            }
        }
        return numGenerated;
    }

    /**
     * Remove any generated columns from this RCL.
     */
    void removeGeneratedGroupingColumns(){
        int sz=size();
        for(int i=sz-1;i>=0;i--){
            ResultColumn rc=elementAt(i);
            if(rc.isGenerated() && rc.isGroupingColumn()){
                removeElementAt(i);
            }
        }
    }

    /**
     * @return the number of columns that will be visible during execution.
     * During compilation we can add columns for a group by/order by but these
     * to an RCL but these are projected out during query execution.
     */
    public int visibleSize(){
        return size()-orderBySelect-numGeneratedColumns();
    }

    /**
     * Convert this object to a String.  See comments in QueryTreeNode.java
     * for how this should be done for tree printing.
     *
     * @return This object as a String
     */
    public String toString(){
        if(SanityManager.DEBUG){
            return "indexRow: "+indexRow+"\n"+
                    "orderBySelect: "+orderBySelect+"\n"+
                    (indexRow?"conglomerateId: "+conglomerateId+"\n"
                            :"")+
                    super.toString();
        }else{
            return "";
        }
    }

    public String printCols() {
        StringBuilder buf = new StringBuilder("[");
        for (ResultColumn rc : this) {
            try {
                buf.append(rc.getSchemaName()).append(".");
            } catch (StandardException e) {
                // do nothing
            }
            buf.append(rc.getFullName()).append(",");
        }
        if (buf.length() > 1) {
            buf.setLength(buf.length()-1);
        }
        buf.append("]");
        return buf.toString();
    }

    @Override
    public String toHTMLString() {
        return "" +
                "conglomerateId: " + conglomerateId + "<br/>" +
                "indexRow: " + indexRow + "<br/>" +
                "orderBySelect: " + orderBySelect + "<br/>" +
                "forUpdate: " + forUpdate + "<br/>" +
                "countMismatchAllowed: " + countMismatchAllowed + "<br/>" +
                "initialListSize: " + initialListSize + "<br/>" +
                super.toHTMLString();
    }

    private static boolean streamableType(ResultColumn rc){
        DataTypeDescriptor dtd=rc.getType();
        TypeId s=TypeId.getBuiltInTypeId(dtd.getTypeName());

        return s!=null && s.streamStorable();
    }


    private static void updateArrays(int[] mapArray,
                                     boolean[] cloneMap,
                                     Map<Integer, Integer> seenMap,
                                     ResultColumn rc,
                                     int index){

        int vcId=rc.getVirtualColumnId();

        mapArray[index]=vcId;

        if(streamableType(rc)){
            Integer seenIndex=seenMap.get(vcId);

            if(seenIndex!=null){
                // We have already mapped this column at index
                // seenIndex, so mark occurence 2..n  for cloning.
                cloneMap[index]=true;
            }else{
                seenMap.put(vcId,index);
            }
        }
    }


    public class ColumnMapping{

        public final int[] mapArray;
        public final boolean[] cloneMap;

        public ColumnMapping(int[] mapArray,boolean[] cloneMap){
            this.mapArray=mapArray;
            this.cloneMap=cloneMap;
        }
    }

    /**
     *
     * Compute Distinct Cardinality from statistics using the non-zero cardinality.
     *
     * @param costEstimate
     * @throws StandardException
     */
    public void computeDistinctCardinality(CostEstimate costEstimate) throws StandardException {
        assert costEstimate != null:"Null Cost Estimate passed in";
        double cardinality = 1.0d;
        for (ResultColumn column: this) {
            cardinality*=column.nonZeroCardinality((long)costEstimate.rowCount());
        }
        if (cardinality > costEstimate.rowCount()) // Cannot create more rows than you have...
            return;
        costEstimate.setRowCount((double) cardinality);
        costEstimate.setSingleScanRowCount((double) cardinality);
        // TODO JL
    }


}
