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


import com.splicemachine.encoding.Encoding;
import com.splicemachine.encoding.MultiFieldDecoder;
import com.splicemachine.encoding.MultiFieldEncoder;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.services.io.StoredFormatIds;
import com.splicemachine.db.iapi.types.DataValueDescriptor;

import java.io.IOException;

/**
 * @author Scott Fines
 * Date: 4/2/14
 */
public class StringDescriptorSerializer implements DescriptorSerializer{
		private static final DescriptorSerializer INSTANCE = new StringDescriptorSerializer();
		public static final Factory INSTANCE_FACTORY = new Factory() {
				@Override public DescriptorSerializer newInstance() { return INSTANCE; }

				@Override public boolean applies(DataValueDescriptor dvd) { return dvd!=null && applies(dvd.getTypeFormatId()); }

				@Override
				public boolean applies(int typeFormatId) {
						switch(typeFormatId){
								case StoredFormatIds.SQL_CHAR_ID:
								case StoredFormatIds.SQL_VARCHAR_ID:
								case StoredFormatIds.SQL_LONGVARCHAR_ID:
								case StoredFormatIds.SQL_CLOB_ID:
								case StoredFormatIds.XML_ID:
										return true;
								default:
										return false;
						}
				}

				@Override public boolean isScalar() { return false; }
				@Override public boolean isFloat() { return false; }
				@Override public boolean isDouble() { return false; }
		};

		private StringDescriptorSerializer() { }

		@Override
		public void encode(MultiFieldEncoder fieldEncoder, DataValueDescriptor dvd, boolean desc) throws StandardException {
				fieldEncoder.encodeNext(dvd.getString(),desc);
		}

		@Override
		public byte[] encodeDirect(DataValueDescriptor dvd, boolean desc) throws StandardException {
				return Encoding.encode(dvd.getString(), desc);
		}

		@Override
		public void decode(MultiFieldDecoder fieldDecoder, DataValueDescriptor destDvd, boolean desc) throws StandardException {
				destDvd.setValue(fieldDecoder.decodeNextString(desc));
		}

		@Override
		public void decodeDirect(DataValueDescriptor dvd, byte[] data, int offset, int length, boolean desc) throws StandardException {
				dvd.setValue(Encoding.decodeString(data,offset,length,desc));
		}

		@Override public boolean isScalarType() { return false; }
		@Override public boolean isFloatType() { return false; }
		@Override public boolean isDoubleType() { return false; }

		@Override public void close() throws IOException {  }
}
