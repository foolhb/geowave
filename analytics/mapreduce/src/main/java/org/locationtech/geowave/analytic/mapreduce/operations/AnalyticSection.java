/**
 * Copyright (c) 2013-2019 Contributors to the Eclipse Foundation
 *
 * <p> See the NOTICE file distributed with this work for additional information regarding copyright
 * ownership. All rights reserved. This program and the accompanying materials are made available
 * under the terms of the Apache License, Version 2.0 which accompanies this distribution and is
 * available at http://www.apache.org/licenses/LICENSE-2.0.txt
 */
package org.locationtech.geowave.analytic.mapreduce.operations;

import com.beust.jcommander.Parameters;
import org.locationtech.geowave.core.cli.annotations.GeowaveOperation;
import org.locationtech.geowave.core.cli.api.DefaultOperation;
import org.locationtech.geowave.core.cli.operations.GeowaveTopLevelSection;

@GeowaveOperation(name = "analytic", parentOperation = GeowaveTopLevelSection.class)
@Parameters(
    commandDescription = "Commands that run mapreduce or spark processing to enhance an existing GeoWave dataset")
public class AnalyticSection extends DefaultOperation {
}
