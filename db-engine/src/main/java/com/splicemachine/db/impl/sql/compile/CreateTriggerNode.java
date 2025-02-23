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

import java.sql.Timestamp;
import java.util.*;

import com.splicemachine.db.catalog.UUID;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.reference.SQLState;
import com.splicemachine.db.iapi.services.sanity.SanityManager;
import com.splicemachine.db.iapi.sql.compile.CompilerContext;
import com.splicemachine.db.iapi.sql.conn.Authorizer;
import com.splicemachine.db.iapi.sql.conn.LanguageConnectionContext;
import com.splicemachine.db.iapi.sql.dictionary.ColumnDescriptor;
import com.splicemachine.db.iapi.sql.dictionary.ColumnDescriptorList;
import com.splicemachine.db.iapi.sql.dictionary.DataDictionary;
import com.splicemachine.db.iapi.sql.dictionary.SchemaDescriptor;
import com.splicemachine.db.iapi.sql.dictionary.TableDescriptor;
import com.splicemachine.db.iapi.sql.execute.ConstantAction;
import com.splicemachine.db.impl.sql.execute.TriggerEventDML;

/**
 * A CreateTriggerNode is the root of a QueryTree that represents a CREATE TRIGGER statement.
 */
public class CreateTriggerNode extends DDLStatementNode {

    private TableName triggerName;
    private TableName tableName;
    private TriggerEventDML triggerEventMask;
    private ResultColumnList triggerCols;
    private boolean isBefore;
    private boolean isRow;
    private boolean isEnabled;
    private Vector<TriggerReferencingStruct> refClause;
    private ValueNode whenClause;
    private String whenText;
    private StatementNode actionNode;
    private String actionText;
    private String originalActionText; // text w/o trim of spaces
    private int actionOffset;

    private SchemaDescriptor triggerSchemaDescriptor;
    private SchemaDescriptor compSchemaDescriptor;

    /*
     * The following arrary will include columns that will cause the trigger to
     * fire. This information will get saved in SYSTRIGGERS.
     *
     * The array will be null for all kinds of insert and delete triggers but
     * it will be non-null for a subset of update triggers.
     *
     * For update triggers, the array will be null if no column list is
     * supplied in the CREATE TRIGGER trigger column clause as shown below.
     * The UPDATE trigger below will fire no matter which column in table1
     * gets updated.
     * eg
     * CREATE TRIGGER tr1 AFTER UPDATE ON table1
     *    REFERENCING OLD AS oldt NEW AS newt
     *    FOR EACH ROW UPDATE table2 SET c24=oldt.c14;
     *
     * For update triggers, this array will be non-null if specific trigger
     * column(s) has been specified in the CREATE TRIGGER sql. The UPDATE
     * trigger below will fire when an update happens on column c12 in table1.
     * eg
     * CREATE TRIGGER tr1 AFTER UPDATE OF c12 ON table1
     *    REFERENCING OLD AS oldt NEW AS newt
     *    FOR EACH ROW UPDATE table2 SET c24=oldt.c14;
     *
     * Array referencedColInts along with referencedColsInTriggerAction will
     * be used to determine which columns from the triggering table need to
     * be read in when the trigger fires, thus making sure that we do not
     * read the columns from the trigger table that are not required for
     * trigger execution.
     */
    private int[] referencedColInts;

    /*
     * The following array (which was added as part of DERBY-1482) will
     * include columns referenced in the trigger action through the
     * REFERENCING clause(old/new transition variables), in other trigger
     * action columns. This information will get saved in SYSTRIGGERS
     * (with the exception of triggers created in pre-10.7 dbs. For
     * pre-10.7 dbs, this information will not get saved in SYSTRIGGERS
     * in order to maintain backward compatibility.
     *
     * Unlike referencedColInts, this array can be non-null for all 3 types
     * of triggers, namely, INSERT, UPDATE AND DELETE triggers. This array
     * will be null if no columns in the trigger action are referencing
     * old/new transition variables
     *
     * eg of a trigger in 10.7 and higher dbs which will cause
     * referencedColsInTriggerAction to be null
     * CREATE TRIGGER tr1 NO CASCADE BEFORE UPDATE of c12 ON table1
     *    SELECT c24 FROM table2 WHERE table2.c21 = 1
     *
     * eg of a trigger in 10.7 and higher dbs which will cause
     * referencedColsInTriggerAction to be non-null
     * For the trigger below, old value of column c14 from trigger table is
     * used in the trigger action through old/new transition variables. A
     * note of this requirement to read c14 will be made in
     * referencedColsInTriggerAction array.
     * eg
     * CREATE TRIGGER tr1 AFTER UPDATE ON table1
     *    REFERENCING OLD AS oldt NEW AS newt
     *    FOR EACH ROW UPDATE table2 SET c24=oldt.c14;
     *
     * The exception to the rules above for trigger action columns information
     * in referencedColsInTriggerAction is a trigger that was created with
     * pre-10.7 release. Prior to 10.7, we did not collect any information
     * about trigger action columns. So, any of the 2 kinds of trigger shown
     * above prior to 10.7 will not have any trigger action column info on
     * them in SYSTRIGGERS table. In order to cover the pre-existing pre-10.7
     * triggers and all the other kinds of triggers, we will follow following
     * 4 rules during trigger execution.
     *   Rule1)If trigger column information is null, then read all the
     *   columns from trigger table into memory irrespective of whether
     *   there is any trigger action column information. 2 egs of such
     *   triggers
     *      create trigger tr1 after update on t1 for each row values(1);
     *      create trigger tr1 after update on t1 referencing old as oldt
     *          for each row insert into t2 values(2,oldt.j,-2);
     *   Rule2)If trigger column information is available but no trigger
     *   action column information is found and no REFERENCES clause is
     *   used for the trigger, then only read the columns identified by
     *   the trigger column. eg
     *      create trigger tr1 after update of c1 on t1
     *          for each row values(1);
     *   Rule3)If trigger column information and trigger action column
     *   information both are not null, then only those columns will be
     *   read into memory. This is possible only for triggers created in
     *   release 10.7 or higher. Because prior to that we did not collect
     *   trigger action column informatoin. eg
     *      create trigger tr1 after update of c1 on t1
     *          referencing old as oldt for each row
     *          insert into t2 values(2,oldt.j,-2);
     *   Rule4)If trigger column information is available but no trigger
     *   action column information is found but REFERENCES clause is used
     *   for the trigger, then read all the columns from the trigger
     *   table. This will cover soft-upgrade and hard-upgrade scenario
     *   for triggers created pre-10.7. This rule prevents us from having
     *   special logic for soft-upgrade. Additionally, this logic makes
     *   invalidation of existing triggers unnecessary during
     *   hard-upgrade. The pre-10.7 created triggers will work just fine
     *   even though for some triggers, they would have trigger action
     *   columns missing from SYSTRIGGERS. A user can choose to drop and
     *   recreate such triggers to take advantage of Rule 3 which will
     *   avoid unnecessary column reads during trigger execution.
     *   eg trigger created prior to 10.7
     *      create trigger tr1 after update of c1 on t1
     *          referencing old as oldt for each row
     *          insert into t2 values(2,oldt.j,-2);
     *   To reiterate, Rule4) is there to cover triggers created with
     *   pre-10,7 releases but now that database has been
     *   hard/soft-upgraded to 10.7 or higher version. Prior to 10.7,
     *   we did not collect any information about trigger action columns.
     *
     *   The only place we will need special code for soft-upgrade is during
     *   trigger creation. If we are in soft-upgrade mode, we want to make sure
     *   that we do not save information about trigger action columns in
     *   SYSTRIGGERS because the releases prior to 10.7 do not understand
     *   trigger action column information.
     *
     * Array referencedColInts along with referencedColsInTriggerAction will
     * be used to determine which columns from the triggering table needs to
     * be read in when the trigger fires, thus making sure that we do not
     * read the columns from the trigger table that are not required for
     * trigger execution.
     */
    private int[] referencedColsInTriggerAction;
    private TableDescriptor triggerTableDescriptor;
    private UUID actionCompSchemaId;

    /*
    ** Names of old and new table.  By default we have
    ** OLD/old and NEW/new.  The casing is dependent on
    ** the language connection context casing as the rest
    ** of other code. Therefore we will set the value of the
    ** String at the init() time.
    ** However, if there is a referencing clause
    ** we will reset these values to be whatever the user
    ** wants.
    */
    private String oldTableName;
    private String newTableName;

    private boolean oldTableInReferencingClause;
    private boolean newTableInReferencingClause;


    /**
     * Initializer for a CreateTriggerNode
     *
     * @param triggerName      name of the trigger
     * @param tableName        name of the table which the trigger is declared upon
     * @param triggerEventMask TriggerDescriptor.TRIGGER_EVENT_XXX
     * @param triggerCols      columns trigger is to fire upon.  Valid
     *                         for UPDATE case only.
     * @param isBefore         is before trigger (false for after)
     * @param isRow            true for row trigger, false for statement
     * @param isEnabled        true if enabled
     * @param refClause        the referencing clause
     * @param whenClause       the WHEN clause tree
     * @param whenText         the text of the WHEN clause
     * @param whenOffset       offset of start of WHEN clause
     * @param actionNode       the trigger action tree
     * @param actionText       the text of the trigger action
     * @param actionOffset     offset of start of action clause
     */
    @Override
    public void init(
            Object triggerName,
            Object tableName,
            Object triggerEventMask,
            Object triggerCols,
            Object isBefore,
            Object isRow,
            Object isEnabled,
            Object refClause,
            Object whenClause,
            Object whenText,
            Object whenOffset,
            Object actionNode,
            Object actionText,
            Object actionOffset) throws StandardException {
        initAndCheck(triggerName);
        this.triggerName = (TableName) triggerName;
        this.tableName = (TableName) tableName;
        this.triggerEventMask = (TriggerEventDML) triggerEventMask;
        this.triggerCols = (ResultColumnList) triggerCols;
        this.isBefore = (Boolean) isBefore;
        this.isRow = (Boolean) isRow;
        this.isEnabled = (Boolean) isEnabled;
        this.refClause = (Vector<TriggerReferencingStruct>) refClause;
        this.whenClause = (ValueNode) whenClause;
        this.whenText = (whenText == null) ? null : ((String) whenText).trim();
        this.actionNode = (StatementNode) actionNode;
        this.originalActionText = (String) actionText;
        this.actionText = (actionText == null) ? null : ((String) actionText).trim();
        this.actionOffset = (Integer) actionOffset;

        implicitCreateSchema = true;
    }

    @Override
    public String statementToString() {
        return "CREATE TRIGGER";
    }

    /**
     * Prints the sub-nodes of this object.  See QueryTreeNode.java for
     * how tree printing is supposed to work.
     *
     * @param depth The depth of this node in the tree
     */
    @Override
    public void printSubNodes(int depth) {
        if (SanityManager.DEBUG) {
            super.printSubNodes(depth);

            if (triggerCols != null) {
                printLabel(depth, "triggerColumns: ");
                triggerCols.treePrint(depth + 1);
            }
            if (whenClause != null) {
                printLabel(depth, "whenClause: ");
                whenClause.treePrint(depth + 1);
            }
            if (actionNode != null) {
                printLabel(depth, "actionNode: ");
                actionNode.treePrint(depth + 1);
            }
        }
    }


    // accessors


    // We inherit the generate() method from DDLStatementNode.

    /**
     * Bind this CreateTriggerNode.  This means doing any static error
     * checking that can be done before actually creating the table.
     */
    @Override
    public void bindStatement() throws StandardException {
        CompilerContext compilerContext = getCompilerContext();
        DataDictionary dd = getDataDictionary();
        /*
        ** Grab the current schema.  We will use that for
        ** sps compilation
        */
        LanguageConnectionContext lcc = getLanguageConnectionContext();
        compSchemaDescriptor = lcc.getDefaultSchema();

        /*
        ** Get and check the schema descriptor for this
        ** trigger.  This check will throw the proper exception
        ** if someone tries to create a trigger in the SYS
        ** schema.
        */
        triggerSchemaDescriptor = getSchemaDescriptor();

        /*
        ** Get the trigger table.
        */
        triggerTableDescriptor = getTableDescriptor(tableName);

        //throw an exception if user is attempting to create a trigger on a temporary table
        if (isSessionSchema(triggerTableDescriptor.getSchemaDescriptor())) {
            throw StandardException.newException(SQLState.LANG_OPERATION_NOT_ALLOWED_ON_SESSION_SCHEMA_TABLES);
        }
        if (isPrivilegeCollectionRequired()) {
            compilerContext.pushCurrentPrivType(Authorizer.TRIGGER_PRIV);
            compilerContext.addRequiredTablePriv(triggerTableDescriptor);
            compilerContext.popCurrentPrivType();
        }

        /*
        ** Regenerates the actionText and actionNode if necessary.
        */
        boolean needInternalSQL = bindReferencesClause(dd);

        lcc.pushTriggerTable(triggerTableDescriptor);
        try {
            /*
            ** Bind the trigger action and the trigger
            ** when clause to make sure that they are
            ** ok.  Note that we have already substituted 
            ** in various replacements for OLD/NEW transition
            ** tables/variables and reparsed if necessary.
            */
            if (needInternalSQL)
                compilerContext.setReliability(CompilerContext.INTERNAL_SQL_LEGAL);

            // For before triggers, the action statement cannot contain calls
            // to procedures that modify SQL data. If the action statement
            // contains a procedure call, this reliability will be used during
            // bind of the call statement node.
            if (isBefore)
                compilerContext.setReliability(CompilerContext.MODIFIES_SQL_DATA_PROCEDURE_ILLEGAL);

            actionNode.bindStatement();

            /* when clause is always null
            if (whenClause != null)
            {
                whenClause.bind();
            }
            */
        } finally {
            lcc.popTriggerTable(triggerTableDescriptor);
        }

        /* 
        ** Statement is dependent on the TableDescriptor 
        */
        compilerContext.createDependency(triggerTableDescriptor);

        /*
        ** If there is a list of columns, then no duplicate columns, and all columns must be found.
        */
        if (triggerCols != null && triggerCols.size() != 0) {
            Hashtable<String, ResultColumn> columnNames = new Hashtable<>();
            int tcSize = triggerCols.size();
            for (int i = 0; i < tcSize; i++) {
                ResultColumn rc = triggerCols.elementAt(i);
                if (columnNames.put(rc.getName(), rc) != null) {
                    throw StandardException.newException(SQLState.LANG_DUPLICATE_COLUMN_IN_TRIGGER_UPDATE,
                            rc.getName(),
                            triggerName);
                }

                ColumnDescriptor cd = triggerTableDescriptor.getColumnDescriptor(rc.getName());
                if (cd == null) {
                    throw StandardException.newException(SQLState.LANG_COLUMN_NOT_FOUND_IN_TABLE,
                            rc.getName(),
                            tableName);
                }
            }
        }

        //If attempting to reference a SESSION schema table (temporary or permanent) in the trigger action, throw an exception
        if (actionNode.referencesSessionSchema())
            throw StandardException.newException(SQLState.LANG_OPERATION_NOT_ALLOWED_ON_SESSION_SCHEMA_TABLES);

    }

    /**
     * Return true if the node references SESSION schema tables (temporary or permanent)
     *
     * @return true if references SESSION schema tables, else false
     * @throws StandardException Thrown on error
     */
    @Override
    public boolean referencesSessionSchema() throws StandardException {
        //If create trigger is part of create statement and the trigger is defined on or it references SESSION schema tables,
        //it will get caught in the bind phase of trigger and exception will be thrown by the trigger bind.
        return (isSessionSchema(triggerTableDescriptor.getSchemaName()) || actionNode.referencesSessionSchema());
    }

    /**
     * Comparator that can be used for sorting lists of FromBaseTables
     * on the position they have in the SQL query string.
     */
    private static final Comparator OFFSET_COMPARATOR = new Comparator() {
        @Override
        public int compare(Object o1, Object o2) {
            // Return negative int, zero, or positive int if the offset of the
            // first table is less than, equal to, or greater than the offset
            // of the second table.
            return ((FromBaseTable) o1).getTableNameField().getBeginOffset() -
                    ((FromBaseTable) o2).getTableNameField().getBeginOffset();
        }
    };

    /*
    ** BIND OLD/NEW TRANSITION TABLES/VARIABLES AND collect TRIGGER ACTION
    ** COLUMNS referenced through REFERECING CLAUSE in CREATE TRIGGER statement
    **
    ** 1) validate the referencing clause (if any)
    **
    ** 2) convert trigger action text.  e.g.
    **    DELETE FROM t WHERE c = old.c
    ** turns into
    **    DELETE FROM t WHERE c = com.splicemachine.db.iapi.db.Factory::
    **        getTriggerExecutionContext().getOldRow().
    **      getInt(columnNumberFor'C'inRuntimeResultset);
    ** or
    **    DELETE FROM t WHERE c in (SELECT c FROM OLD)
    ** turns into
    **    DELETE FROM t WHERE c in (
    **      SELECT c FROM new TriggerOldTransitionTable OLD)
    **
    ** 3) check all column references against new/old transition
    **    variables (since they are no longer 'normal' column references
    **     that will be checked during bind)
    **
    ** 4) collect all column references in trigger action through new/old
    ** transition variables. Information about them will be saved in
    ** SYSTRIGGERS table DERBY-1482(if we are dealing with pre-10.7 db, then we
    ** will not put any information about trigger action columns in the system
    ** table to ensure backward compatibility). This information along with the
    ** trigger columns will decide what columns from the trigger table will be
    ** fetched into memory during trigger execution.
    **
    ** 5) reparse the new action text
    **
    ** You might be wondering why we regenerate the text and reparse
    ** instead of just reworking the tree to have the nodes we want.
    ** Well, the primary reason is that if we screwed with the tree,
    ** then we would have a major headache if this trigger action
    ** was ever recompiled -- spses don't really know that they are
    ** triggers so it would be quite arduous to figure out that an
    ** sps is a trigger and munge up its query tree after figuring
    ** out what its OLD/NEW tables are, etc.  Also, it is just plain
    ** easier to just generate the sql and rebind.
    **
    */
    private boolean bindReferencesClause(DataDictionary dd) throws StandardException {
        validateReferencesClause(dd);

        // the actions of before triggers may not reference generated columns
        if (isBefore) {
            forbidActionsOnGenCols();
        }

        String transformedActionText;
        int start = 0;
        if (triggerCols != null && triggerCols.size() != 0) {
            //If the trigger is defined on speific columns, then collect
            //their column positions and ensure that those columns do
            //indeed exist in the trigger table.
            referencedColInts = new int[triggerCols.size()];
            ResultColumn rc;
            ColumnDescriptor cd;
            //This is the most interesting case for us. If we are here,
            //then it means that the trigger is defined at the row level
            //and a set of trigger columns are specified in the CREATE
            //TRIGGER statement. This can only happen for an UPDATE
            //trigger.
            //eg
            //CREATE TRIGGER tr1 AFTER UPDATE OF c12 ON table1
            //    REFERENCING OLD AS oldt NEW AS newt
            //    FOR EACH ROW UPDATE table2 SET c24=oldt.c14;

            for (int i = 0; i < triggerCols.size(); i++) {
                rc = triggerCols.elementAt(i);
                cd = triggerTableDescriptor.getColumnDescriptor(rc.getName());
                //Following will catch the case where an invalid trigger column
                //has been specified in CREATE TRIGGER statement.
                //CREATE TRIGGER tr1 AFTER UPDATE OF c1678 ON table1
                //    REFERENCING OLD AS oldt NEW AS newt
                //    FOR EACH ROW UPDATE table2 SET c24=oldt.c14;
                if (cd == null) {
                    throw StandardException.newException(SQLState.LANG_COLUMN_NOT_FOUND_IN_TABLE,
                            rc.getName(),
                            tableName);
                }
                referencedColInts[i] = cd.getPosition();
            }
            // sort the list
            java.util.Arrays.sort(referencedColInts);
        }

        if (isRow) {
            //Create an array for column positions of columns referenced in
            //trigger action. Initialize it to -1. The call to
            //DataDictoinary.getTriggerActionSPS will find out the actual
            //columns, if any, referenced in the trigger action and put their
            //column positions in the array.
            referencedColsInTriggerAction = new int[triggerTableDescriptor.getNumberOfColumns()];
            java.util.Arrays.fill(referencedColsInTriggerAction, -1);
            //Now that we have verified that are no invalid column references
            //for trigger columns, let's go ahead and transform the OLD/NEW
            //transient table references in the trigger action sql.
            transformedActionText = getDataDictionary().getTriggerActionString(actionNode,
                    oldTableName,
                    newTableName,
                    originalActionText,
                    referencedColInts,
                    referencedColsInTriggerAction,
                    actionOffset,
                    triggerTableDescriptor,
                    triggerEventMask,
                    true
            );
            //Now that we know what columns we need for REFERENCEd columns in
            //trigger action, we can get rid of -1 entries for the remaining
            //columns from trigger table. This information will be saved in
            //SYSTRIGGERS and will be used at trigger execution time to decide
            //which columns need to be read into memory for trigger action
            referencedColsInTriggerAction = justTheRequiredColumns(
                    referencedColsInTriggerAction);
        } else {
            //This is a table level trigger
            //Total Number of columns in the trigger table
            int numberOfColsInTriggerTable = triggerTableDescriptor.getNumberOfColumns();
            StringBuilder newText = new StringBuilder();
            /*
            ** For a statement trigger, we find all FromBaseTable nodes.  If
            ** the from table is NEW or OLD (or user designated alternates
            ** REFERENCING), we turn them into a trigger table VTI.
            */
            CollectNodesVisitor visitor = new CollectNodesVisitor(FromBaseTable.class);
            actionNode.accept(visitor);
            Vector tabs = visitor.getList();
            Collections.sort(tabs, OFFSET_COMPARATOR);
            for (Object tab : tabs) {
                FromBaseTable fromTable = (FromBaseTable) tab;
                String refTableName = fromTable.getTableName().getTableName();
                String baseTableName = fromTable.getBaseTableName();
                if ((baseTableName == null) ||
                        ((oldTableName == null || !oldTableName.equals(baseTableName)) &&
                                (newTableName == null || !newTableName.equals(baseTableName)))) {
                    continue;
                }
                int tokBeginOffset = fromTable.getTableNameField().getBeginOffset();
                int tokEndOffset = fromTable.getTableNameField().getEndOffset();
                if (tokBeginOffset == -1) {
                    continue;
                }

                checkInvalidTriggerReference(baseTableName);

                newText.append(originalActionText.substring(start, tokBeginOffset - actionOffset));
                newText.append("new com.splicemachine.db.catalog.TriggerNewTransitionRows() ");
//                newText.append(baseTableName.equals(oldTableName) ?
//                                "new com.splicemachine.db.catalog.TriggerOldTransitionRows() " :
//                                "new com.splicemachine.db.catalog.TriggerNewTransitionRows() ");
                /*
                ** If the user supplied a correlation, then just
                ** pick it up automatically; otherwise, supply
                ** the default.
                */
                if (refTableName.equals(baseTableName)) {
                    newText.append(baseTableName).append(" ");
                }
                start = tokEndOffset - actionOffset + 1;
                //If we are dealing with statement trigger, then we will read
                //all the columns from the trigger table since trigger will be
                //fired for any of the columns in the trigger table.
                referencedColInts = new int[numberOfColsInTriggerTable];
                for (int j = 0; j < numberOfColsInTriggerTable; j++)
                    referencedColInts[j] = j + 1;
            }
            if (start < originalActionText.length()) {
                newText.append(originalActionText.substring(start));
            }
            transformedActionText = newText.toString();
        }

        if (referencedColsInTriggerAction != null)
            java.util.Arrays.sort(referencedColsInTriggerAction);

        /*
        ** Parse the new action text with the substitutions.
        ** Also, we reset the actionText to this new value.  This
        ** is what we are going to stick in the system tables.
        */
        boolean regenNode = false;
        if (!transformedActionText.equals(actionText)) {
            regenNode = true;
            actionText = transformedActionText;
            actionNode = parseStatement(actionText, true);
        }

        return regenNode;
    }

    /*
     * The arrary passed will have either -1 or a column position as it's
     * elements. If the array only has -1 as for all it's elements, then
     * this method will return null. Otherwise, the method will create a
     * new arrary with all -1 entries removed from the original arrary.
     */
    private int[] justTheRequiredColumns(int[] columnsArrary) {
        int countOfColsRefedInArray = 0;
        int numberOfColsInTriggerTable = triggerTableDescriptor.getNumberOfColumns();

        //Count number of non -1 entries
        for (int i = 0; i < numberOfColsInTriggerTable; i++) {
            if (columnsArrary[i] != -1)
                countOfColsRefedInArray++;
        }

        if (countOfColsRefedInArray > 0) {
            int[] tempArrayOfNeededColumns = new int[countOfColsRefedInArray];
            int j = 0;
            for (int i = 0; i < numberOfColsInTriggerTable; i++) {
                if (columnsArrary[i] != -1)
                    tempArrayOfNeededColumns[j++] = columnsArrary[i];
            }
            return tempArrayOfNeededColumns;
        } else
            return null;
    }

    /*
     * Forbid references to generated columns in the actions of BEFORE triggers.
     * This is DERBY-3948, enforcing the following section of the SQL standard:
     * part 2, section 11.39 (<trigger definition>), syntax rule 12c:
     *
     * <blockquote>
     *    12) If BEFORE is specified, then:
     * :
     * c) The <triggered action> shall not contain a <field reference> that
     * references a field in the new transition variable corresponding to a
     * generated column of T. 
     * </blockquote>
     */
    private void forbidActionsOnGenCols() throws StandardException {
        ColumnDescriptorList generatedColumns = triggerTableDescriptor.getGeneratedColumns();
        int genColCount = generatedColumns.size();

        if (genColCount == 0) {
            return;
        }

        CollectNodesVisitor visitor = new CollectNodesVisitor(ColumnReference.class);

        actionNode.accept(visitor);

        Vector<ColumnReference> columnRefs = visitor.getList();

        for (ColumnReference cr : columnRefs) {
            String colRefName = cr.getColumnName();
            String tabRefName = cr.getTableName();

            for (int gc_idx = 0; gc_idx < genColCount; gc_idx++) {
                String genColName = generatedColumns.elementAt(gc_idx).getColumnName();

                if (genColName.equals(colRefName) && equals(newTableName, tabRefName)) {
                    throw StandardException.newException(SQLState.LANG_GEN_COL_BEFORE_TRIG, genColName);
                }
            }
        }
    }

    /*
     * Compare two strings.
     */
    private boolean equals(String left, String right) {
        if (left == null) {
            return (right == null);
        } else {
            return left.equals(right);
        }
    }

    /*
    ** Check for illegal combinations here: insert & old or delete and new
    */
    private void checkInvalidTriggerReference(String tableName) throws StandardException {
        if (tableName.equals(oldTableName) && triggerEventMask == TriggerEventDML.INSERT) {
            throw StandardException.newException(SQLState.LANG_TRIGGER_BAD_REF_MISMATCH, "INSERT", "new");
        } else if (tableName.equals(newTableName) && triggerEventMask == TriggerEventDML.DELETE) {
            throw StandardException.newException(SQLState.LANG_TRIGGER_BAD_REF_MISMATCH, "DELETE", "old");
        }
    }

    /*
    ** Make sure that the referencing clause is legitimate. While we are at it we set the new/oldTableName to
    ** be whatever the user wants.
    */
    private void validateReferencesClause(DataDictionary dd) throws StandardException {
        if ((refClause == null) || (refClause.isEmpty())) {
            return;
        }

        for (Enumeration e = refClause.elements(); e.hasMoreElements(); ) {
            TriggerReferencingStruct trn = (TriggerReferencingStruct) e.nextElement();

            /*
            ** 1) Make sure that we don't try to refer
            ** to a table for a row trigger or a row for
            ** a table trigger.
            */
            if (isRow && !trn.isRow) {
                throw StandardException.newException(SQLState.LANG_TRIGGER_BAD_REF_MISMATCH, "ROW", "row");
            } else if (!isRow && trn.isRow) {
                throw StandardException.newException(SQLState.LANG_TRIGGER_BAD_REF_MISMATCH, "STATEMENT", "table");
            }

            /*
            ** 2) Make sure we have no dups
            */
            if (trn.isNew) {

                if (newTableInReferencingClause) {
                    throw StandardException.newException(SQLState.LANG_TRIGGER_BAD_REF_CLAUSE_DUPS);
                }

                /*
                ** 3a) No NEW reference in delete trigger
                */
                if (triggerEventMask == TriggerEventDML.DELETE) {
                    throw StandardException.newException(SQLState.LANG_TRIGGER_BAD_REF_MISMATCH, "DELETE", "old");
                }
                newTableName = trn.identifier;
                newTableInReferencingClause = true;
            } else {
                if (oldTableInReferencingClause) {
                    throw StandardException.newException(SQLState.LANG_TRIGGER_BAD_REF_CLAUSE_DUPS);
                }
                /*
                ** 3b) No OLD reference in insert trigger
                */
                if (triggerEventMask == TriggerEventDML.INSERT) {
                    throw StandardException.newException(SQLState.LANG_TRIGGER_BAD_REF_MISMATCH, "INSERT", "new");
                }
                oldTableName = trn.identifier;
                oldTableInReferencingClause = true;
            }

            /*
            ** 4) Additional restriction on BEFORE triggers
            */
            if (this.isBefore && !trn.isRow) {
                // OLD TABLE and NEW TABLE not allowed for BEFORE triggers.
                throw StandardException.newException(SQLState.LANG_TRIGGER_BAD_REF_MISMATCH, "BEFORE", "row");
            }

        }

    }

    /**
     * Create the Constant information that will drive the guts of Execution.
     */
    @Override
    public ConstantAction makeConstantAction() throws StandardException {
        String oldReferencingName = (oldTableInReferencingClause) ? oldTableName : null;
        String newReferencingName = (newTableInReferencingClause) ? newTableName : null;

        return getGenericConstantActionFactory().getCreateTriggerConstantAction(
                triggerSchemaDescriptor.getSchemaName(),
                getRelativeName(),
                triggerEventMask,
                isBefore,
                isRow,
                isEnabled,
                triggerTableDescriptor,
                (UUID) null,            // when SPSID
                whenText,
                (UUID) null,            // action SPSid
                actionText,
                (actionCompSchemaId == null) ? compSchemaDescriptor.getUUID() : actionCompSchemaId,
                (Timestamp) null,    // creation time
                referencedColInts,
                referencedColsInTriggerAction,
                originalActionText,
                oldTableInReferencingClause,
                newTableInReferencingClause,
                oldReferencingName,
                newReferencingName
        );
    }


    /**
     * Convert this object to a String.  See comments in QueryTreeNode.java for how this should be done for tree printing.
     */
    @Override
    public String toString() {
        if (SanityManager.DEBUG) {
            String refString = "null";
            if (refClause != null) {
                StringBuilder buf = new StringBuilder();
                for (Enumeration e = refClause.elements(); e.hasMoreElements(); ) {
                    buf.append("\t");
                    TriggerReferencingStruct trn = (TriggerReferencingStruct) e.nextElement();
                    buf.append(trn.toString());
                    buf.append("\n");
                }
                refString = buf.toString();
            }

            return super.toString() +
                    "tableName: " + tableName +
                    "\ntriggerEventMask: " + triggerEventMask +
                    "\nisBefore: " + isBefore +
                    "\nisRow: " + isRow +
                    "\nisEnabled: " + isEnabled +
                    "\nwhenText: " + whenText +
                    "\nrefClause: " + refString +
                    "\nactionText: " + actionText +
                    "\n";
        } else {
            return "";
        }
    }

}
