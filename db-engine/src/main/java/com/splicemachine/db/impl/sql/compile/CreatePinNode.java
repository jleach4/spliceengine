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

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.sql.compile.Visitor;
import com.splicemachine.db.iapi.sql.execute.ConstantAction;

/**
 * Create Pin Node can Pin either a Table or a Schema in memory.  Schema is still a todo...  JL
 */

public class CreatePinNode extends DDLStatementNode  {
	private String schemaName;
	private TableName tableName;

	 /**
	 * Initializer for a CreateTableNode for a base table
	 *
	 * @param schemaName		Schema to Pin
	 * @param tableName			Table to Pin
	 *
	 * @exception StandardException		Thrown on error
	 */
	public void init(
			Object schemaName,
			Object tableName) throws StandardException {
		this.schemaName = (String) schemaName;
		this.tableName = (TableName) tableName;
	}

	/**
	 * Convert this object to a String.
	 *
	 * @return	This object as a String
	 */

	public String toString() {
		return String.format("schemaName=%s, tableName=%s",schemaName,tableName);
	}

	/**
	 * Prints the sub-nodes of this object.  See QueryTreeNode.java for
	 * how tree printing is supposed to work.
	 * @param depth		The depth to indent the sub-nodes
	 */
	public void printSubNodes(int depth) {
		// No Sub Nodes
	}


	public String statementToString() {
		if (schemaName != null)
			return "DECLARE GLOBAL TEMPORARY TABLE";
		else
			return "CREATE TABLE";
	}

	// We inherit the generate() method from DDLStatementNode.

	/**
	 * Bind this CreateTableNode.  This means doing any static error checking that can be
	 * done before actually creating the base table or declaring the global temporary table.
	 * For eg, verifying that the TableElementList does not contain any duplicate column names.
	 *
	 *
	 * @exception StandardException		Thrown on error
	 */

	public void bindStatement() throws StandardException {

	}

	/**
	 * Create the Constant information that will drive the guts of Execution.
	 *
	 * @exception StandardException		Thrown on failure
	 */
	public ConstantAction	makeConstantAction() throws StandardException {
           return getGenericConstantActionFactory().getPinTableConstantAction(
                getSchemaDescriptor(schemaName!=null?schemaName:tableName.getSchemaName(),false).getSchemaName(),
					tableName.getTableName());
	}

	/**
	 * Accept the visitor for all visitable children of this node.
	 * 
	 * @param v the visitor
	 */
    @Override
	public void acceptChildren(Visitor v) throws StandardException {
		super.acceptChildren(v);
	}
}
