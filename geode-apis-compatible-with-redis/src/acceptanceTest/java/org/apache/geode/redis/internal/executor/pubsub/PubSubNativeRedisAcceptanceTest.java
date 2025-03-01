/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.geode.redis.internal.executor.pubsub;

import org.apache.logging.log4j.Logger;
import org.junit.AfterClass;
import org.junit.ClassRule;

import org.apache.geode.NativeRedisTestRule;
import org.apache.geode.logging.internal.log4j.api.LogService;

public class PubSubNativeRedisAcceptanceTest extends AbstractPubSubIntegrationTest {

  private static final Logger logger = LogService.getLogger();

  @ClassRule
  public static NativeRedisTestRule redis = new NativeRedisTestRule();

  @AfterClass
  public static void cleanup() throws InterruptedException {
    // This test consumes a lot of sockets and any subsequent tests may fail because of spurious
    // bind exceptions. Even though sockets are closed, they will remain in TIME_WAIT state so we
    // need to wait for that to clear up. It shouldn't take more than a minute or so.
    // There will be a better solution for this from GEODE-9495, but for now a thread sleep is the
    // simplest way to wait for the sockets to be out of the TIME_WAIT state. The timeout of 240 sec
    // was chosen because that is the default duration for TIME_WAIT on Windows. The timeouts for
    // both mac and linux are significantly shorter.
    Thread.sleep(240000);
  }

  @Override
  public int getPort() {
    return redis.getPort();
  }
}
