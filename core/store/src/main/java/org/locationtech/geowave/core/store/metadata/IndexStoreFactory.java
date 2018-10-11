/*******************************************************************************
 * Copyright (c) 2013-2018 Contributors to the Eclipse Foundation
 *   
 *  See the NOTICE file distributed with this work for additional
 *  information regarding copyright ownership.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Apache License,
 *  Version 2.0 which accompanies this distribution and is available at
 *  http://www.apache.org/licenses/LICENSE-2.0.txt
 ******************************************************************************/
package org.locationtech.geowave.core.store.metadata;

import org.locationtech.geowave.core.store.BaseStoreFactory;
import org.locationtech.geowave.core.store.StoreFactoryHelper;
import org.locationtech.geowave.core.store.StoreFactoryOptions;
import org.locationtech.geowave.core.store.index.IndexStore;

public class IndexStoreFactory extends
		BaseStoreFactory<IndexStore>
{

	public IndexStoreFactory(
			String typeName,
			String description,
			StoreFactoryHelper helper ) {
		super(
				typeName,
				description,
				helper);
	}

	@Override
	public IndexStore createStore(
			final StoreFactoryOptions options ) {
		return new IndexStoreImpl(
				helper.createOperations(options),
				options.getStoreOptions());
	}

}