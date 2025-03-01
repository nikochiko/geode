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
 *
 */
package org.apache.geode.redis.internal.pubsub;


import org.apache.geode.redis.internal.executor.GlobPattern;
import org.apache.geode.redis.internal.netty.Client;
import org.apache.geode.redis.internal.pubsub.Subscriptions.ForEachConsumer;

class PatternClientSubscriptionManager
    extends ChannelClientSubscriptionManager {
  /**
   * Since all the subscriptions in an instance of this manager
   * have the same pattern, its compiled form is kept in this field
   * instead of on each PatternSubscription instance.
   */
  private final GlobPattern pattern;

  public PatternClientSubscriptionManager(Client client, byte[] patternBytes,
      Subscription subscription) {
    super(client, subscription);
    pattern = new GlobPattern(patternBytes);
  }

  private boolean matches(byte[] channel) {
    return pattern.matches(channel);
  }

  @Override
  public int getSubscriptionCount(byte[] channel) {
    if (matches(channel)) {
      return getSubscriptionCount();
    }
    return 0;
  }

  @Override
  public void forEachSubscription(byte[] subscriptionName, byte[] channelToMatch,
      ForEachConsumer action) {
    if (matches(channelToMatch)) {
      super.forEachSubscription(subscriptionName, channelToMatch, action);
    }
  }

}
