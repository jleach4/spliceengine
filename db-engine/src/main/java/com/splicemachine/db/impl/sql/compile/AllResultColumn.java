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

import com.splicemachine.db.iapi.sql.compile.C_NodeTypes;

import com.splicemachine.db.iapi.services.sanity.SanityManager;

import com.splicemachine.db.iapi.error.StandardException;

/**
 * An AllResultColumn represents a "*" result column in a SELECT
 * statement.  It gets replaced with the appropriate set of columns
 * at bind time.
 *
 */

public class AllResultColumn extends ResultColumn
{
	private TableName		tableName;

	/**
	 * This initializer is for use in the parser for a "*".
	 * 
	 * @param tableName	Dot expression qualifying "*"
	 */
	public void init(Object tableName)
	{
		this.tableName = (TableName) tableName;
	}

	/** 
	 * Return the full table name qualification for this node
	 *
	 * @return Full table name qualification as a String
	 */
	public String getFullTableName()
	{
		if (tableName == null)
		{
			return null;
		}
		else
		{
			return tableName.getFullTableName();
		}
	}

	/**
	 * Make a copy of this ResultColumn in a new ResultColumn
	 *
	 * @return	A new ResultColumn with the same contents as this one
	 *
	 * @exception StandardException		Thrown on error
	 */
	// Splice fork: changed from package protected to public
	public ResultColumn cloneMe() throws StandardException
	{
		if (SanityManager.DEBUG)
		{
			SanityManager.ASSERT(columnDescriptor == null,
					"columnDescriptor is expected to be non-null");
		}

		return (ResultColumn) getNodeFactory().getNode(
									C_NodeTypes.ALL_RESULT_COLUMN,
									tableName,
									getContextManager());
	}


    public TableName getTableNameObject() {
        return tableName;
    }
}
