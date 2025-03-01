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
package org.apache.geode.redis.internal.executor.sortedset;

import java.util.List;

import org.apache.geode.redis.internal.RedisException;
import org.apache.geode.redis.internal.data.RedisKey;
import org.apache.geode.redis.internal.executor.AbstractExecutor;
import org.apache.geode.redis.internal.executor.RedisResponse;
import org.apache.geode.redis.internal.netty.Command;
import org.apache.geode.redis.internal.netty.ExecutionHandlerContext;

public abstract class AbstractSortedSetRangeExecutor<T extends AbstractSortedSetRangeOptions<?>>
    extends AbstractExecutor {
  @Override
  public RedisResponse executeCommand(Command command, ExecutionHandlerContext context) {

    T options;
    try {
      options = createRangeOptions(command.getProcessedCommand());
    } catch (RedisException ex) {
      return RedisResponse.error(ex.getMessage());
    }

    if (options.containsNoEntries()) {
      return getEmptyResponse();
    }

    return executeRangeCommand(context.getSortedSetCommands(), command.getKey(), options);
  }

  public abstract boolean isRev();

  public abstract T createRangeOptions(List<byte[]> commandElements);

  public abstract RedisResponse getEmptyResponse();

  public abstract RedisResponse executeRangeCommand(RedisSortedSetCommands commands, RedisKey key,
      T options);
}
