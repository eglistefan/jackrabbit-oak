/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.blob.cloud.aws.s3;

import java.io.IOException;

import javax.jcr.RepositoryException;

import org.apache.jackrabbit.core.data.CachingDataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * Test {@link org.apache.jackrabbit.core.data.CachingDataStore} with S3Backend and with very small size (@link
 * {@link org.apache.jackrabbit.core.data.LocalCache}. It requires to pass aws config file via system property.
 * For e.g. -Dconfig=/opt/cq/aws.properties. Sample aws properties located at
 * src/test/resources/aws.properties
 */
public class TestS3DSWithSmallCache extends TestS3Ds {

    protected static final Logger LOG = LoggerFactory.getLogger(TestS3DSWithSmallCache.class);
    public TestS3DSWithSmallCache() throws IOException {
        config = System.getProperty(CONFIG);
        memoryBackend = false;
        noCache = false;
    }

    protected CachingDataStore createDataStore() throws RepositoryException {
        ds = new S3TestDataStore(props);
        ds.setConfig(config);
        ds.setCacheSize(dataLength * 10);
        ds.setCachePurgeTrigFactor(0.5d);
        ds.setCachePurgeResizeFactor(0.4d);
        ds.init(dataStoreDir);
        sleep(1000);
        return ds;
    }
}