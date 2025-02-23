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

package com.splicemachine.derby.utils.marshall.dvd;

import com.splicemachine.encoding.MultiFieldDecoder;
import com.splicemachine.pipeline.Exceptions;
import com.splicemachine.storage.EntryDecoder;
import com.splicemachine.storage.EntryEncoder;
import com.splicemachine.storage.index.BitIndex;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.db.iapi.types.DataValueDescriptor;

import java.io.IOException;

/**
 * @author Scott Fines
 *         Date: 4/3/14
 */
public class EntryRowSerializer extends RowSerializer{

		protected EntryRowSerializer(DescriptorSerializer[] serializers, int[] columnMap, boolean[] columnSortOrder) {
				super(serializers, columnMap, columnSortOrder);
		}

		public void encode(EntryEncoder entryEncoder,ExecRow row) throws StandardException {
				initialize();
				encode(entryEncoder.getEntryEncoder(),row);
		}

		public void decode(EntryDecoder entryDecoder,ExecRow row) throws StandardException {
				initialize();
				if(columnMap==null)
						decodeAll(entryDecoder, row);
				else if(columnSortOrder==null)
						decodeAscending(entryDecoder, row);
				else
						decodeFields(entryDecoder, row);

		}

		private void decodeAll(EntryDecoder entryDecoder, ExecRow row) throws StandardException {
				BitIndex index = entryDecoder.getCurrentIndex();
				MultiFieldDecoder decoder = getFieldDecoder(entryDecoder);

				DataValueDescriptor[] fields = row.getRowArray();
				for(int i=index.nextSetBit(0);i>=0 && i<fields.length;i=index.nextSetBit(i+1)){
						DataValueDescriptor dvd = fields[i];
						DescriptorSerializer serializer = serializers[i];
						serializer.decode(decoder,dvd,false);
				}
		}

		private void decodeAscending(EntryDecoder entryDecoder, ExecRow row) throws StandardException {
				BitIndex index = entryDecoder.getCurrentIndex();
				MultiFieldDecoder decoder = getFieldDecoder(entryDecoder);

				DataValueDescriptor[] fields = row.getRowArray();
				for(int i=index.nextSetBit(0);i>=0 && i<columnMap.length;i=index.nextSetBit(i+1)){
						int pos = columnMap[i];
						DataValueDescriptor dvd = fields[pos];
						DescriptorSerializer serializer = serializers[pos];
						serializer.decode(decoder,dvd,false);
				}
		}

		private void decodeFields(EntryDecoder entryDecoder, ExecRow row) throws StandardException {
				BitIndex index = entryDecoder.getCurrentIndex();
				MultiFieldDecoder decoder = getFieldDecoder(entryDecoder);

				DataValueDescriptor[] fields = row.getRowArray();
				for(int i=index.nextSetBit(0);i>=0 && i<columnMap.length;i=index.nextSetBit(i+1)){
						int pos = columnMap[i];
						DataValueDescriptor dvd = fields[pos];
						DescriptorSerializer serializer = serializers[pos];
						boolean sortOrder = !columnSortOrder[i];
						serializer.decode(decoder,dvd,sortOrder);
				}
		}

		private MultiFieldDecoder getFieldDecoder(EntryDecoder entryDecoder) throws StandardException {
				try {
						return entryDecoder.getEntryDecoder();
				} catch (IOException e) {
						throw Exceptions.parseException(e);
				}
		}

}
