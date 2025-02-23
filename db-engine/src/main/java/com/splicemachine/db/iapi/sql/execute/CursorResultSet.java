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

package com.splicemachine.db.iapi.sql.execute;

import com.splicemachine.db.iapi.sql.ResultSet;
import com.splicemachine.db.iapi.types.RowLocation;
import com.splicemachine.db.iapi.error.StandardException;

/**
 * The CursorResultSet interface is used to provide additional
 * operations on result sets that can be used in cursors.
 * <p>
 * Since the ResulSet operations must also be supported by
 * cursor result sets, we extend that interface here as well.
 *
 */
public interface CursorResultSet extends ResultSet {

	/**
	 * Returns the row location of the current base table row of the cursor.
	 * If this cursor's row is composed of multiple base tables' rows,
	 * i.e. due to a join, then a null is returned.
	 *
	 * @return the row location of the current cursor row.
	 * @exception StandardException thrown on failure to
	 *	get location from storage engine
	 */
	RowLocation getRowLocation() throws StandardException;

	/**
	 * Returns the current row of the result set.
	 * REMIND: eventually, this will only return the current row
	 * for result sets that need to return it; either some field
	 * in the activation or a parameter in the constructor will be
	 * used to signal that this needs to function. This will let us
	 * limit the number of live objects we are holding on to.
	 * <p>
	 * @return the last row returned by getNextRow. null if closed.
	 * @exception StandardException thrown on failure.
	 */
	ExecRow getCurrentRow() throws StandardException;

}
