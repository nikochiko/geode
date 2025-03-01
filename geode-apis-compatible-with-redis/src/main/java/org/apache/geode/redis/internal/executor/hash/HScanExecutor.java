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
package org.apache.geode.redis.internal.executor.hash;

import static org.apache.geode.redis.internal.data.RedisDataType.REDIS_HASH;

import java.util.List;

import org.apache.commons.lang3.tuple.Pair;

import org.apache.geode.redis.internal.data.RedisDataType;
import org.apache.geode.redis.internal.data.RedisKey;
import org.apache.geode.redis.internal.executor.GlobPattern;
import org.apache.geode.redis.internal.executor.key.AbstractScanExecutor;
import org.apache.geode.redis.internal.netty.ExecutionHandlerContext;

public class HScanExecutor extends AbstractScanExecutor {

  @Override
  protected Pair<Integer, List<byte[]>> executeScan(ExecutionHandlerContext context, RedisKey key,
      GlobPattern pattern, int count, int cursor) {
    return context.getHashCommands().hscan(key, pattern, count, cursor);
  }

  @Override
  protected RedisDataType getDataType() {
    return REDIS_HASH;
  }
}
