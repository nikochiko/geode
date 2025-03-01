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

import static org.apache.geode.redis.internal.netty.Coder.bytesToString;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Logger;

import org.apache.geode.annotations.VisibleForTesting;
import org.apache.geode.cache.execute.FunctionContext;
import org.apache.geode.cache.execute.FunctionService;
import org.apache.geode.cache.execute.ResultCollector;
import org.apache.geode.distributed.DistributedMember;
import org.apache.geode.internal.cache.execute.InternalFunction;
import org.apache.geode.logging.internal.executors.LoggingThreadFactory;
import org.apache.geode.logging.internal.log4j.api.LogService;
import org.apache.geode.redis.internal.RegionProvider;
import org.apache.geode.redis.internal.netty.Client;
import org.apache.geode.redis.internal.services.StripedExecutorService;
import org.apache.geode.redis.internal.services.StripedRunnable;

/**
 * Concrete class that manages publish and subscribe functionality. Since Redis subscriptions
 * require a persistent connection we need to have a way to track the existing clients that are
 * expecting to receive published messages.
 */
public class PubSubImpl implements PubSub {
  private static final int MAX_PUBLISH_THREAD_COUNT =
      Integer.getInteger("redis.max-publish-thread-count", 10);

  private static final Logger logger = LogService.getLogger();

  private final Subscriptions subscriptions;
  private final ExecutorService executor;

  /**
   * Inner class to wrap the publish action and pass it to the {@link StripedExecutorService}.
   */
  private static class PublishingRunnable implements StripedRunnable {

    private final Runnable runnable;
    private final Client client;

    public PublishingRunnable(Runnable runnable, Client client) {
      this.runnable = runnable;
      this.client = client;
    }

    @Override
    public Object getStripe() {
      return client;
    }

    @Override
    public void run() {
      runnable.run();
    }
  }

  public PubSubImpl(Subscriptions subscriptions) {
    this.subscriptions = subscriptions;
    executor = createExecutorService();
    registerPublishFunction();
  }

  @VisibleForTesting
  PubSubImpl(Subscriptions subscriptions, ExecutorService executorService) {
    this.subscriptions = subscriptions;
    executor = executorService;
    // since this is used for unit testing, do not call registerPublishFunction
  }

  private static ExecutorService createExecutorService() {
    ThreadFactory threadFactory = new LoggingThreadFactory("GeodeRedisServer-Publish-", true);
    BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>();
    ExecutorService innerPublishExecutor = new ThreadPoolExecutor(1, MAX_PUBLISH_THREAD_COUNT,
        60, TimeUnit.SECONDS, workQueue, threadFactory);

    return new StripedExecutorService(innerPublishExecutor);
  }

  @VisibleForTesting
  public int getSubscriptionCount() {
    return subscriptions.size();
  }

  @Override
  public long publish(RegionProvider regionProvider, byte[] channel, byte[] message,
      Client client) {
    executor.submit(
        new PublishingRunnable(() -> internalPublish(regionProvider, channel, message), client));

    return subscriptions.getAllSubscriptionCount(channel);
  }

  @SuppressWarnings("unchecked")
  private void internalPublish(RegionProvider regionProvider, byte[] channel, byte[] message) {
    Set<DistributedMember> remoteMembers = regionProvider.getRemoteRegionMembers();
    try {
      ResultCollector<?, ?> resultCollector = null;
      try {
        if (!remoteMembers.isEmpty()) {
          // send function to remotes
          resultCollector = FunctionService
              .onMembers(remoteMembers)
              .setArguments(new byte[][] {channel, message})
              .execute(PublishFunction.ID);
        }
      } finally {
        // execute it locally
        publishMessageToLocalSubscribers(channel, message);
        if (resultCollector != null) {
          // block until remote execute completes
          resultCollector.getResult();
        }
      }
    } catch (Exception e) {
      // the onMembers contract is for execute to throw an exception
      // if one of the members goes down.
      if (logger.isDebugEnabled()) {
        logger.debug(
            "Exception executing publish function on channel {}. If a server departed during the publish then an exception is expected.",
            bytesToString(channel), e);
      }
    }
  }

  @Override
  public SubscribeResult subscribe(byte[] channel, Client client) {
    return subscriptions.subscribe(channel, client);
  }

  @Override
  public SubscribeResult psubscribe(byte[] pattern, Client client) {
    return subscriptions.psubscribe(pattern, client);
  }

  private void registerPublishFunction() {
    FunctionService.registerFunction(new PublishFunction(this));
  }

  @Override
  public Collection<Collection<?>> unsubscribe(List<byte[]> channels, Client client) {
    return subscriptions.unsubscribe(channels, client);
  }

  @Override
  public Collection<Collection<?>> punsubscribe(List<byte[]> patterns, Client client) {
    return subscriptions.punsubscribe(patterns, client);
  }

  @Override
  public List<byte[]> findChannelNames() {
    return subscriptions.findChannelNames();
  }

  @Override
  public List<byte[]> findChannelNames(byte[] pattern) {
    return subscriptions.findChannelNames(pattern);
  }

  @Override
  public List<Object> findNumberOfSubscribersPerChannel(List<byte[]> names) {
    List<Object> result = new ArrayList<>(names.size() * 2);
    names.forEach(name -> {
      result.add(name);
      result.add(subscriptions.getChannelSubscriptionCount(name));
    });
    return result;
  }

  @Override
  public long findNumberOfSubscribedPatterns() {
    return subscriptions.getPatternSubscriptionCount();
  }

  @Override
  public void clientDisconnect(Client client) {
    subscriptions.remove(client);
  }

  @VisibleForTesting
  void publishMessageToLocalSubscribers(byte[] channel, byte[] message) {
    subscriptions.forEachSubscription(channel,
        (subscriptionName, channelToMatch, client, subscription) -> subscription.publishMessage(
            channelToMatch != null, subscriptionName,
            client, channel, message));
  }

  private static class PublishFunction implements InternalFunction<byte[][]> {
    public static final String ID = "redisPubSubFunctionID";
    /**
     * this class is never serialized (since it implemented getId)
     * but make tools happy by setting its serialVersionUID.
     */
    private static final long serialVersionUID = -1L;

    private final PubSubImpl pubSub;

    public PublishFunction(PubSubImpl pubSub) {
      this.pubSub = pubSub;
    }

    @Override
    public String getId() {
      return ID;
    }

    @Override
    public void execute(FunctionContext<byte[][]> context) {
      byte[][] publishMessage = context.getArguments();
      pubSub.publishMessageToLocalSubscribers(publishMessage[0], publishMessage[1]);
      context.getResultSender().lastResult(true);
    }

    /**
     * Since the publish process uses an onMembers function call, we don't want to re-publish
     * to members if one fails.
     * TODO: Revisit this in the event that we instead use an onMember call against individual
     * members.
     */
    @Override
    public boolean isHA() {
      return false;
    }

    @Override
    public boolean hasResult() {
      return true; // this is needed to preserve ordering
    }
  }
}
