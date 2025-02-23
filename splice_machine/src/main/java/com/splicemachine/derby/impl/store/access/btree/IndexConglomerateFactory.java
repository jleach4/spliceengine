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

package com.splicemachine.derby.impl.store.access.btree;

import java.util.Properties;
import com.splicemachine.derby.impl.store.access.SpliceTransactionManager;
import com.splicemachine.derby.utils.ConglomerateUtils;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.store.access.ColumnOrdering;
import com.splicemachine.db.iapi.store.access.conglomerate.Conglomerate;
import com.splicemachine.db.iapi.store.access.conglomerate.ConglomerateFactory;
import com.splicemachine.db.iapi.store.access.conglomerate.TransactionManager;
import com.splicemachine.db.iapi.types.DataValueDescriptor;
import org.apache.log4j.Logger;

import com.splicemachine.derby.impl.store.access.base.SpliceConglomerateFactory;

public class IndexConglomerateFactory extends SpliceConglomerateFactory {
	protected static Logger LOG = Logger.getLogger(IndexConglomerateFactory.class);
	public IndexConglomerateFactory() {
		super();
	}

	@Override
	protected String getImplementationID() {
		return "BTREE";
	}

	@Override
	protected String getFormatUUIDString() {
		return "C6CEEEF0-DAD3-11d0-BB01-0060973F0942";
	}
	
	@Override
    public int getConglomerateFactoryId() {
        return(ConglomerateFactory.BTREE_FACTORY_ID);
    }

	/**
	Create the conglomerate and return a conglomerate object for it.

	@exception StandardException Standard exception policy.

	@see ConglomerateFactory#createConglomerate
	**/
	@Override
	public Conglomerate createConglomerate(
	boolean 				isExternal,
    TransactionManager      xact_mgr,
    long                    input_containerid,
    DataValueDescriptor[]   template,
	ColumnOrdering[]        columnOrder,
    int[]                   collationIds,
    Properties              properties,
	int                     temporaryFlag) throws StandardException {
		IndexConglomerate index = new IndexConglomerate();
		index.create(isExternal,
            xact_mgr.getRawStoreXact(), input_containerid,
            template, columnOrder, collationIds, properties, 
            index.getTypeFormatId(), 
            temporaryFlag,operationFactory,partitionFactory);

		return index;
	}

    /**
     * Return Conglomerate object for conglomerate with container_key.
     * <p>
     * Return the Conglomerate Object.  This is implementation specific.
     * Examples of what will be done is using the key to find the file where
     * the conglomerate is located, and then executing implementation specific
     * code to instantiate an object from reading a "special" row from a
     * known location in the file.  In the btree case the btree conglomerate
     * is stored as a column in the control row on the root page.
     * <p>
     * This operation is costly so it is likely an implementation using this
     * will cache the conglomerate row in memory so that subsequent accesses
     * need not perform this operation.
     *
     * @param xact_mgr      transaction to perform the create in.
     * @param containerId The unique id of the existing conglomerate.
     *
	 * @return An instance of the conglomerate.
     *
	 * @exception  StandardException  Standard exception policy.
	 * 
	 * FIXME: need to 
     **/
    public Conglomerate readConglomerate(TransactionManager xact_mgr,long containerId) throws StandardException {
    	return ConglomerateUtils.readConglomerate(containerId, IndexConglomerate.class, ((SpliceTransactionManager)xact_mgr).getActiveStateTxn());
    }
	
}
	
