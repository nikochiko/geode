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
package org.apache.geode.internal.cache.wan.serial;

import static org.apache.geode.test.awaitility.GeodeAwaitility.await;
import static org.apache.geode.test.dunit.IgnoredException.addIgnoredException;
import static org.apache.geode.test.dunit.Wait.pause;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.geode.cache.wan.GatewaySender.OrderPolicy;
import org.apache.geode.internal.cache.wan.WANTestBase;
import org.apache.geode.test.dunit.AsyncInvocation;
import org.apache.geode.test.junit.categories.WanTest;

@Category({WanTest.class})
public class SerialWANStatsDUnitTest extends WANTestBase {

  private static final long serialVersionUID = 1L;

  private String testName;

  public SerialWANStatsDUnitTest() {
    super();
  }

  @Override
  protected final void postSetUpWANTestBase() throws Exception {
    this.testName = getUniqueName();
    addIgnoredException("java.net.ConnectException");
    addIgnoredException("java.net.SocketException");
    addIgnoredException("Unexpected IOException");
  }

  @Test
  public void testReplicatedSerialPropagation() {
    Integer lnPort = vm0.invoke(() -> WANTestBase.createFirstLocatorWithDSId(1));
    Integer nyPort = vm1.invoke(() -> WANTestBase.createFirstRemoteLocator(2, lnPort));

    createCacheInVMs(nyPort, vm2);
    vm2.invoke(() -> WANTestBase.createReceiver());

    createCacheInVMs(lnPort, vm4, vm5, vm6, vm7);

    vm4.invoke(() -> WANTestBase.createSender("ln", 2, false, 100, 10, false, false, null, true));
    vm5.invoke(() -> WANTestBase.createSender("ln", 2, false, 100, 10, false, false, null, true));

    vm2.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", null, isOffHeap()));

    startSenderInVMs("ln", vm4, vm5);

    vm4.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));
    vm5.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));
    vm6.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));
    vm7.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));

    vm5.invoke(() -> WANTestBase.doPuts(testName + "_RR", 1000));

    vm2.invoke(() -> WANTestBase.validateRegionSize(testName + "_RR", 1000));

    pause(2000);
    vm2.invoke(() -> WANTestBase.checkGatewayReceiverStats(100, 1000, 1000));

    vm4.invoke(() -> WANTestBase.checkQueueStats("ln", 0, 1000, 1000, 1000));
    vm4.invoke(() -> WANTestBase.checkBatchStats("ln", 100));

    vm5.invoke(() -> WANTestBase.checkQueueStats("ln", 0, 1000, 0, 0));
    vm5.invoke(() -> WANTestBase.checkBatchStats("ln", 0));

  }

  @Test
  public void testReplicatedSerialPropagationWithoutGroupTransactionEventsSendsBatchesWithIncompleteTransactions() {
    Integer lnPort = vm0.invoke(() -> WANTestBase.createFirstLocatorWithDSId(1));
    Integer nyPort = vm1.invoke(() -> WANTestBase.createFirstRemoteLocator(2, lnPort));

    createCacheInVMs(nyPort, vm2);
    vm2.invoke(() -> WANTestBase.createReceiver());

    createCacheInVMs(lnPort, vm4, vm5, vm6, vm7);

    boolean groupTransactionEvents = false;
    vm4.invoke(
        () -> WANTestBase.createSender("ln", 2, false, 100, 10, false, false, null, true,
            groupTransactionEvents));
    vm5.invoke(
        () -> WANTestBase.createSender("ln", 2, false, 100, 10, false, false, null, true,
            groupTransactionEvents));
    vm6.invoke(
        () -> WANTestBase.createSender("ln", 2, false, 100, 10, false, false, null, true,
            groupTransactionEvents));
    vm7.invoke(
        () -> WANTestBase.createSender("ln", 2, false, 100, 10, false, false, null, true,
            groupTransactionEvents));

    vm2.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", null, isOffHeap()));

    startSenderInVMs("ln", vm4, vm5);

    vm4.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));
    vm5.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));
    vm6.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));
    vm7.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));

    final Map keyValues = new HashMap();
    int entries = 12;
    for (int i = 0; i < entries; i++) {
      keyValues.put(i, i + "_Value");
    }
    int eventsPerTransaction = 3;
    vm5.invoke(() -> WANTestBase.doPutsInsideTransactions(testName + "_RR", keyValues,
        eventsPerTransaction));

    vm2.invoke(() -> WANTestBase.validateRegionSize(testName + "_RR", entries));

    vm2.invoke(() -> WANTestBase.checkGatewayReceiverStats(2, entries, entries, true));

    vm4.invoke(() -> WANTestBase.checkQueueStats("ln", 0, entries, entries, entries));
    vm4.invoke(() -> WANTestBase.checkBatchStats("ln", 2, true, false));

    // wait until queue is empty
    vm5.invoke(() -> await()
        .until(() -> WANTestBase.getSenderStats("ln", -1).get(0) == 0));

    vm5.invoke(() -> WANTestBase.checkQueueStats("ln", 0, entries, 0, 0));
    vm5.invoke(() -> WANTestBase.checkBatchStats("ln", 0));
  }

  @Test
  public void testReplicatedSerialPropagationWithGroupTransactionEventsSendsBatchesWithCompleteTransactions() {
    Integer lnPort = vm0.invoke(() -> WANTestBase.createFirstLocatorWithDSId(1));
    Integer nyPort = vm1.invoke(() -> WANTestBase.createFirstRemoteLocator(2, lnPort));

    createCacheInVMs(nyPort, vm2);
    vm2.invoke(() -> WANTestBase.createReceiver());

    createCacheInVMs(lnPort, vm4, vm5, vm6, vm7);

    boolean groupTransactionEvents = true;
    vm4.invoke(
        () -> WANTestBase.createSender("ln", 2, false, 100, 10, false, false, null, true,
            groupTransactionEvents));
    vm5.invoke(
        () -> WANTestBase.createSender("ln", 2, false, 100, 10, false, false, null, true,
            groupTransactionEvents));
    vm6.invoke(
        () -> WANTestBase.createSender("ln", 2, false, 100, 10, false, false, null, true,
            groupTransactionEvents));
    vm7.invoke(
        () -> WANTestBase.createSender("ln", 2, false, 100, 10, false, false, null, true,
            groupTransactionEvents));

    vm2.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", null, isOffHeap()));

    startSenderInVMs("ln", vm4, vm5, vm6, vm7);

    vm4.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));
    vm5.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));
    vm6.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));
    vm7.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));

    final Map keyValues = new HashMap();
    int entries = 12;
    for (int i = 0; i < entries; i++) {
      keyValues.put(i, i + "_Value");
    }

    // 4 transactions of 3 events each are sent so that the first batch
    // would initially contain the first 3 transactions complete and the first
    // event of the next transaction (10 entries).
    // As --group-transaction-events is configured in the senders, the remaining
    // events of the third transaction are added to the batch which makes
    // that the batch is sent with 12 events.
    int eventsPerTransaction = 3;
    vm5.invoke(() -> WANTestBase.doPutsInsideTransactions(testName + "_RR", keyValues,
        eventsPerTransaction));

    vm2.invoke(() -> WANTestBase.validateRegionSize(testName + "_RR", entries));

    vm2.invoke(() -> WANTestBase.checkGatewayReceiverStats(1, entries, entries, true));

    vm4.invoke(() -> WANTestBase.checkQueueStats("ln", 0, entries, entries, entries));
    vm4.invoke(() -> WANTestBase.checkBatchStats("ln", 1, true));
    vm4.invoke(() -> WANTestBase.checkConflatedStats("ln", 0));

    // wait until queue is empty
    vm5.invoke(() -> await()
        .until(() -> WANTestBase.getSenderStats("ln", -1).get(0) == 0));

    vm5.invoke(() -> WANTestBase.checkQueueStats("ln", 0, entries, 0, 0));
    vm5.invoke(() -> WANTestBase.checkBatchStats("ln", 0, true));
    vm5.invoke(() -> WANTestBase.checkConflatedStats("ln", 0));
  }

  @Test
  public void testReplicatedSerialPropagationWithGroupTransactionEventsWithoutBatchRedistributionSendsBatchesWithCompleteTransactions_SeveralClients() {
    testReplicatedSerialPropagationWithGroupTransactionEventsSendsBatchesWithCompleteTransactions_SeveralClients(
        false);
  }

  @Test
  public void testReplicatedSerialPropagationWithGroupTransactionEventsWithBatchRedistributionSendsBatchesWithCompleteTransactions_SeveralClients() {
    testReplicatedSerialPropagationWithGroupTransactionEventsSendsBatchesWithCompleteTransactions_SeveralClients(
        true);
  }

  public void testReplicatedSerialPropagationWithGroupTransactionEventsSendsBatchesWithCompleteTransactions_SeveralClients(
      boolean isBatchRedistribution) {
    Integer lnPort = vm0.invoke(() -> WANTestBase.createFirstLocatorWithDSId(1));
    Integer nyPort = vm1.invoke(() -> WANTestBase.createFirstRemoteLocator(2, lnPort));

    createCacheInVMs(nyPort, vm2);
    if (!isBatchRedistribution) {
      vm2.invoke(() -> WANTestBase.createReceiver());
    }

    createCacheInVMs(lnPort, vm4, vm5, vm6, vm7);

    final int batchSize = 10;

    boolean groupTransactionEvents = true;
    vm4.invoke(
        () -> WANTestBase.createSender("ln", 2, false, 100, batchSize, false, false, null, true,
            groupTransactionEvents));
    vm5.invoke(
        () -> WANTestBase.createSender("ln", 2, false, 100, batchSize, false, false, null, true,
            groupTransactionEvents));
    vm6.invoke(
        () -> WANTestBase.createSender("ln", 2, false, 100, batchSize, false, false, null, true,
            groupTransactionEvents));
    vm7.invoke(
        () -> WANTestBase.createSender("ln", 2, false, 100, batchSize, false, false, null, true,
            groupTransactionEvents));

    final String regionName = testName + "_RR";

    vm2.invoke(() -> WANTestBase.createReplicatedRegion(regionName, null, isOffHeap()));

    startSenderInVMs("ln", vm4, vm5, vm6, vm7);

    vm4.invoke(() -> WANTestBase.createReplicatedRegion(regionName, "ln", isOffHeap()));
    vm5.invoke(() -> WANTestBase.createReplicatedRegion(regionName, "ln", isOffHeap()));
    vm6.invoke(() -> WANTestBase.createReplicatedRegion(regionName, "ln", isOffHeap()));
    vm7.invoke(() -> WANTestBase.createReplicatedRegion(regionName, "ln", isOffHeap()));

    int clients = 2;
    int eventsPerTransaction = batchSize + 1;
    int entriesPerInvocation = eventsPerTransaction * 200;

    final List<Map<Object, Object>> data = new ArrayList<>(clients);
    for (int clientId = 0; clientId < clients; clientId++) {
      final Map<Object, Object> keyValues = new HashMap<>();
      for (int i = entriesPerInvocation * clientId; i < entriesPerInvocation
          * (clientId + 1); i++) {
        keyValues.put(i, i + "_Value");
      }
      data.add(keyValues);
    }

    int entries = entriesPerInvocation * clients;

    List<AsyncInvocation> invocations = new ArrayList<AsyncInvocation>(clients);
    for (int i = 0; i < clients; i++) {
      final int index = i;
      AsyncInvocation asyncInvocation =
          vm4.invokeAsync(() -> WANTestBase.doPutsInsideTransactions(regionName, data.get(index),
              eventsPerTransaction));
      invocations.add(asyncInvocation);
    }

    try {
      for (AsyncInvocation invocation : invocations) {
        invocation.await();
      }
    } catch (InterruptedException e) {
      e.printStackTrace();
    }

    if (isBatchRedistribution) {
      // wait for batches to be redistributed and then start the receiver
      vm4.invoke(() -> await()
          .until(() -> WANTestBase.getSenderStats("ln", -1).get(5) > 0));
      vm2.invoke(() -> WANTestBase.createReceiver());
    }

    vm2.invoke(() -> WANTestBase.validateRegionSize(regionName, entries));

    checkQueuesAreEmptyAndOnlyCompleteTransactionsAreReplicated(isBatchRedistribution);
  }

  @Test
  public void testReplicatedSerialPropagationWithBatchRedistWithoutGroupTransactionEventsSendsBatchesWithIncompleteTransactions() {
    Integer lnPort = vm0.invoke(() -> WANTestBase.createFirstLocatorWithDSId(1));
    Integer nyPort = vm1.invoke(() -> WANTestBase.createFirstRemoteLocator(2, lnPort));

    createCacheInVMs(nyPort, vm2);

    createCacheInVMs(lnPort, vm4, vm5, vm6, vm7);

    boolean groupTransactionEvents = false;
    vm4.invoke(
        () -> WANTestBase.createSender("ln", 2, false, 100, 10, false, false, null, true,
            groupTransactionEvents));
    vm5.invoke(
        () -> WANTestBase.createSender("ln", 2, false, 100, 10, false, false, null, true,
            groupTransactionEvents));
    vm6.invoke(
        () -> WANTestBase.createSender("ln", 2, false, 100, 10, false, false, null, true,
            groupTransactionEvents));
    vm7.invoke(
        () -> WANTestBase.createSender("ln", 2, false, 100, 10, false, false, null, true,
            groupTransactionEvents));

    vm2.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", null, isOffHeap()));

    startSenderInVMs("ln", vm4, vm5);

    vm4.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));
    vm5.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));
    vm6.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));
    vm7.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));

    final Map keyValues = new HashMap();
    int entries = 24;
    for (int i = 0; i < entries; i++) {
      keyValues.put(i, i + "_Value");
    }
    int eventsPerTransaction = 3;
    vm5.invoke(() -> WANTestBase.doPutsInsideTransactions(testName + "_RR", keyValues,
        eventsPerTransaction));

    // wait for batches to be redistributed and then start the receiver
    vm4.invoke(() -> await()
        .until(() -> WANTestBase.getSenderStats("ln", -1).get(5) > 0));

    vm2.invoke(() -> WANTestBase.createReceiver());

    vm2.invoke(() -> WANTestBase.validateRegionSize(testName + "_RR", entries));

    vm2.invoke(() -> WANTestBase.checkGatewayReceiverStats(3, entries, entries, true));

    vm4.invoke(() -> WANTestBase.checkQueueStats("ln", 0, entries, entries, entries));
    vm4.invoke(() -> WANTestBase.checkBatchStats("ln", 3, true, true));

    // wait until queue is empty
    vm5.invoke(() -> await()
        .until(() -> WANTestBase.getSenderStats("ln", -1).get(0) == 0));

    vm5.invoke(() -> WANTestBase.checkQueueStats("ln", 0, entries, 0, 0));
    vm5.invoke(() -> WANTestBase.checkBatchStats("ln", 0));
  }

  @Test
  public void testReplicatedSerialPropagationWithBatchRedistWithGroupTransactionEventsSendsBatchesWithCompleteTransactions()
      throws Exception {
    Integer lnPort = vm0.invoke(() -> WANTestBase.createFirstLocatorWithDSId(1));
    Integer nyPort = vm1.invoke(() -> WANTestBase.createFirstRemoteLocator(2, lnPort));

    createCacheInVMs(nyPort, vm2);

    createCacheInVMs(lnPort, vm4, vm5, vm6, vm7);

    boolean groupTransactionEvents = true;
    int batchSize = 10;
    vm4.invoke(
        () -> WANTestBase.createSender("ln", 2, false, 100, batchSize, false, false, null, true,
            groupTransactionEvents));
    vm5.invoke(
        () -> WANTestBase.createSender("ln", 2, false, 100, batchSize, false, false, null, true,
            groupTransactionEvents));
    vm6.invoke(
        () -> WANTestBase.createSender("ln", 2, false, 100, batchSize, false, false, null, true,
            groupTransactionEvents));
    vm7.invoke(
        () -> WANTestBase.createSender("ln", 2, false, 100, batchSize, false, false, null, true,
            groupTransactionEvents));

    vm2.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", null, isOffHeap()));

    startSenderInVMs("ln", vm4, vm5, vm6, vm7);

    vm4.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));
    vm5.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));
    vm6.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));
    vm7.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));

    final Map keyValues = new HashMap();
    int entries = 24;
    for (int i = 0; i < entries; i++) {
      keyValues.put(i, i + "_Value");
    }

    // 8 transactions of 3 events each are sent so that the first batch
    // events would initially contain the first 3 transactions complete and the first
    // event of the next transaction (10 entries).
    // As --group-transaction-events is configured in the senders, the remaining
    // event of the third transaction is added to the batch which makes
    // that the first batch is sent with 12 events. The same happens with the
    // second batch which will contain 12 events too.
    int eventsPerTransaction = 3;
    vm5.invoke(() -> WANTestBase.doPutsInsideTransactions(testName + "_RR", keyValues,
        eventsPerTransaction));

    // wait for batches to be redistributed and then start the receiver
    vm4.invoke(() -> await()
        .until(() -> WANTestBase.getSenderStats("ln", -1).get(5) > 0));

    vm2.invoke(() -> WANTestBase.createReceiver());

    vm2.invoke(() -> WANTestBase.validateRegionSize(testName + "_RR", entries));

    vm2.invoke(() -> WANTestBase.checkGatewayReceiverStats(2, entries, entries, true));

    vm4.invoke(() -> WANTestBase.checkQueueStats("ln", 0, entries, entries, entries));
    vm4.invoke(() -> WANTestBase.checkBatchStats("ln", 2, true, true));
    vm4.invoke(() -> WANTestBase.checkConflatedStats("ln", 0));

    // wait until queue is empty
    vm5.invoke(() -> await()
        .until(() -> WANTestBase.getSenderStats("ln", -1).get(0) == 0));

    vm5.invoke(() -> WANTestBase.checkQueueStats("ln", 0, entries, 0, 0));
    vm5.invoke(() -> WANTestBase.checkBatchStats("ln", 0, true));
    vm5.invoke(() -> WANTestBase.checkConflatedStats("ln", 0));
  }

  @Test
  public void testReplicatedSerialPropagationWithMultipleDispatchers() throws Exception {
    Integer lnPort = vm0.invoke(() -> WANTestBase.createFirstLocatorWithDSId(1));
    Integer nyPort = vm1.invoke(() -> WANTestBase.createFirstRemoteLocator(2, lnPort));

    createCacheInVMs(nyPort, vm2);
    vm2.invoke(() -> WANTestBase.createReceiver());

    createCacheInVMs(lnPort, vm4, vm5, vm6, vm7);

    vm4.invoke(() -> WANTestBase.createSenderWithMultipleDispatchers("ln", 2, false, 100, 10, false,
        false, null, true, 2, OrderPolicy.KEY));
    vm5.invoke(() -> WANTestBase.createSenderWithMultipleDispatchers("ln", 2, false, 100, 10, false,
        false, null, true, 2, OrderPolicy.KEY));

    vm2.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", null, isOffHeap()));

    startSenderInVMs("ln", vm4, vm5);

    vm4.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));
    vm5.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));
    vm6.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));
    vm7.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));

    vm5.invoke(() -> WANTestBase.doPuts(testName + "_RR", 1000));

    vm2.invoke(() -> WANTestBase.validateRegionSize(testName + "_RR", 1000));

    pause(2000);
    vm2.invoke(() -> WANTestBase.checkGatewayReceiverStats(100, 1000, 1000));

    vm4.invoke(() -> WANTestBase.checkQueueStats("ln", 0, 1000, 1000, 1000));
    vm4.invoke(() -> WANTestBase.checkBatchStats("ln", 100));

    vm5.invoke(() -> WANTestBase.checkQueueStats("ln", 0, 1000, 0, 0));
    vm5.invoke(() -> WANTestBase.checkBatchStats("ln", 0));

  }

  @Test
  public void testWANStatsTwoWanSites() throws Exception {

    Integer lnPort = createFirstLocatorWithDSId(1);
    Integer nyPort = vm0.invoke(() -> WANTestBase.createFirstRemoteLocator(2, lnPort));
    Integer tkPort = vm1.invoke(() -> WANTestBase.createFirstRemoteLocator(3, lnPort));

    createCacheInVMs(nyPort, vm2);
    vm2.invoke(() -> WANTestBase.createReceiver());
    createCacheInVMs(tkPort, vm3);
    vm3.invoke(() -> WANTestBase.createReceiver());

    createCacheInVMs(lnPort, vm4, vm5, vm6, vm7);

    vm4.invoke(
        () -> WANTestBase.createSender("lnSerial1", 2, false, 100, 10, false, false, null, true));
    vm5.invoke(
        () -> WANTestBase.createSender("lnSerial1", 2, false, 100, 10, false, false, null, true));

    vm4.invoke(
        () -> WANTestBase.createSender("lnSerial2", 3, false, 100, 10, false, false, null, true));
    vm5.invoke(
        () -> WANTestBase.createSender("lnSerial2", 3, false, 100, 10, false, false, null, true));

    vm2.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", null, isOffHeap()));
    vm3.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", null, isOffHeap()));

    startSenderInVMs("lnSerial1", vm4, vm5);
    startSenderInVMs("lnSerial2", vm4, vm5);

    vm4.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "lnSerial1,lnSerial2",
        isOffHeap()));
    vm5.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "lnSerial1,lnSerial2",
        isOffHeap()));
    vm6.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "lnSerial1,lnSerial2",
        isOffHeap()));
    vm7.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "lnSerial1,lnSerial2",
        isOffHeap()));

    vm4.invoke(() -> WANTestBase.doPuts(testName + "_RR", 1000));

    vm2.invoke(() -> WANTestBase.validateRegionSize(testName + "_RR", 1000));
    vm3.invoke(() -> WANTestBase.validateRegionSize(testName + "_RR", 1000));

    pause(2000);
    vm2.invoke(() -> WANTestBase.checkGatewayReceiverStats(100, 1000, 1000));
    vm3.invoke(() -> WANTestBase.checkGatewayReceiverStats(100, 1000, 1000));

    vm4.invoke(() -> WANTestBase.checkQueueStats("lnSerial1", 0, 1000, 1000, 1000));
    vm4.invoke(() -> WANTestBase.checkBatchStats("lnSerial1", 100));
    vm4.invoke(() -> WANTestBase.checkQueueStats("lnSerial2", 0, 1000, 1000, 1000));
    vm4.invoke(() -> WANTestBase.checkBatchStats("lnSerial2", 100));
    vm5.invoke(() -> WANTestBase.checkQueueStats("lnSerial1", 0, 1000, 0, 0));
    vm5.invoke(() -> WANTestBase.checkBatchStats("lnSerial1", 0));
    vm5.invoke(() -> WANTestBase.checkQueueStats("lnSerial2", 0, 1000, 0, 0));
    vm5.invoke(() -> WANTestBase.checkBatchStats("lnSerial2", 0));

  }

  @Test
  public void testReplicatedSerialPropagationHA() throws Exception {

    Integer lnPort = vm0.invoke(() -> WANTestBase.createFirstLocatorWithDSId(1));
    Integer nyPort = vm1.invoke(() -> WANTestBase.createFirstRemoteLocator(2, lnPort));

    vm2.invoke(() -> WANTestBase.createCache(nyPort));
    vm2.invoke(() -> WANTestBase.createReceiver());

    vm4.invoke(() -> WANTestBase.createCache(lnPort));
    vm5.invoke(() -> WANTestBase.createCache(lnPort));
    vm6.invoke(() -> WANTestBase.createCache(lnPort));
    vm7.invoke(() -> WANTestBase.createCache(lnPort));

    vm4.invoke(() -> WANTestBase.createSender("ln", 2, false, 100, 10, false, false, null, true));
    vm5.invoke(() -> WANTestBase.createSender("ln", 2, false, 100, 10, false, false, null, true));

    vm2.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", null, isOffHeap()));

    startSenderInVMs("ln", vm4, vm5);

    vm4.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));
    vm5.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));
    vm6.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));
    vm7.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));

    AsyncInvocation inv1 = vm5.invokeAsync(() -> WANTestBase.doPuts(testName + "_RR", 10000));
    pause(2000);
    AsyncInvocation inv2 = vm4.invokeAsync(() -> WANTestBase.killSender("ln"));
    Boolean isKilled = Boolean.FALSE;
    try {
      isKilled = (Boolean) inv2.getResult();
    } catch (Throwable e) {
      fail("Unexpected exception while killing a sender");
    }
    AsyncInvocation inv3 = null;
    if (!isKilled) {
      inv3 = vm5.invokeAsync(() -> WANTestBase.killSender("ln"));
      inv3.join();
    }
    inv1.join();
    inv2.join();

    vm2.invoke(() -> WANTestBase.validateRegionSize(testName + "_RR", 10000));

    vm2.invoke(() -> WANTestBase.checkGatewayReceiverStatsHA(1000, 10000, 10000));

    vm5.invoke(() -> WANTestBase.checkStats_Failover("ln", 10000));
  }

  @Test
  public void testReplicatedSerialPropagationHAWithGroupTransactionEvents() throws Exception {

    Integer lnPort = vm0.invoke(() -> WANTestBase.createFirstLocatorWithDSId(1));
    Integer nyPort = vm1.invoke(() -> WANTestBase.createFirstRemoteLocator(2, lnPort));

    vm2.invoke(() -> WANTestBase.createCache(nyPort));
    vm2.invoke(() -> WANTestBase.createReceiver());

    vm4.invoke(() -> WANTestBase.createCache(lnPort));
    vm5.invoke(() -> WANTestBase.createCache(lnPort));
    vm6.invoke(() -> WANTestBase.createCache(lnPort));
    vm7.invoke(() -> WANTestBase.createCache(lnPort));

    int batchSize = 9;
    boolean groupTransactionEvents = true;
    vm4.invoke(
        () -> WANTestBase.createSender("ln", 2, false, 100, batchSize, false, false, null, true,
            groupTransactionEvents));
    vm5.invoke(
        () -> WANTestBase.createSender("ln", 2, false, 100, batchSize, false, false, null, true,
            groupTransactionEvents));
    vm6.invoke(
        () -> WANTestBase.createSender("ln", 2, false, 100, batchSize, false, false, null, true,
            groupTransactionEvents));
    vm7.invoke(
        () -> WANTestBase.createSender("ln", 2, false, 100, batchSize, false, false, null, true,
            groupTransactionEvents));

    vm2.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", null, isOffHeap()));

    startSenderInVMs("ln", vm4, vm5, vm6, vm7);

    vm4.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));
    vm5.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));
    vm6.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));
    vm7.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR", "ln", isOffHeap()));

    AsyncInvocation inv1 =
        vm6.invokeAsync(() -> WANTestBase.doTxPutsWithRetryIfError(testName + "_RR", 2, 5000, 0));
    AsyncInvocation inv2 =
        vm7.invokeAsync(() -> WANTestBase.doTxPutsWithRetryIfError(testName + "_RR", 2, 5000, 1));

    vm2.invoke(() -> await()
        .untilAsserted(() -> assertEquals("Waiting for some batches to be received", true,
            getRegionSize(testName + "_RR") > 40)));

    AsyncInvocation inv3 = vm4.invokeAsync(() -> WANTestBase.killSender("ln"));
    Boolean isKilled = Boolean.FALSE;
    try {
      isKilled = (Boolean) inv3.getResult();
    } catch (Throwable e) {
      fail("Unexpected exception while killing a sender");
    }
    AsyncInvocation inv4;
    if (!isKilled) {
      inv4 = vm5.invokeAsync(() -> WANTestBase.killSender("ln"));
      inv4.join();
    }
    inv1.join();
    inv2.join();
    inv3.join();

    vm5.invoke(() -> WANTestBase.validateRegionSize(testName + "_RR", 20000));
    vm2.invoke(() -> WANTestBase.validateRegionSize(testName + "_RR", 20000));

    // batchesReceived is equal to numberOfEntries/(batchSize+1)
    // As transactions are 2 events long, for each batch it will always be necessary to
    // add one more entry to the 9 events batch in order to have complete transactions in the batch.
    int batchesReceived = (10000 + 10000) / (batchSize + 1);
    vm2.invoke(() -> WANTestBase.checkGatewayReceiverStatsHA(batchesReceived, 20000, 20000));

    vm5.invoke(() -> WANTestBase.checkStats_Failover("ln", 20000));
  }

  @Test
  public void testReplicatedSerialPropagationUnprocessedEvents() throws Exception {
    Integer lnPort = vm0.invoke(() -> WANTestBase.createFirstLocatorWithDSId(1));
    Integer nyPort = vm1.invoke(() -> WANTestBase.createFirstRemoteLocator(2, lnPort));

    // these are part of remote site
    createCacheInVMs(nyPort, vm2, vm3);
    createReceiverInVMs(vm2, vm3);

    // these are part of local site
    createCacheInVMs(lnPort, vm4, vm5, vm6, vm7);

    // senders are created on local site
    vm4.invoke(() -> WANTestBase.createSender("ln", 2, false, 100, 20, false, false, null, true));
    vm5.invoke(() -> WANTestBase.createSender("ln", 2, false, 100, 20, false, false, null, true));

    // create one RR (RR_1) on remote site
    vm2.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR_1", null, isOffHeap()));
    vm3.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR_1", null, isOffHeap()));

    // create another RR (RR_2) on remote site
    vm2.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR_2", null, isOffHeap()));
    vm3.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR_2", null, isOffHeap()));

    // start the senders on local site
    startSenderInVMs("ln", vm4, vm5);

    // create one RR (RR_1) on local site
    vm4.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR_1", "ln", isOffHeap()));
    vm5.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR_1", "ln", isOffHeap()));
    vm6.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR_1", "ln", isOffHeap()));
    vm7.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR_1", "ln", isOffHeap()));

    // create another RR (RR_2) on local site
    vm4.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR_2", "ln", isOffHeap()));
    vm5.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR_2", "ln", isOffHeap()));
    vm6.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR_2", "ln", isOffHeap()));
    vm7.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR_2", "ln", isOffHeap()));

    // start puts in RR_1 in another thread
    vm4.invoke(() -> WANTestBase.doPuts(testName + "_RR_1", 1000));
    // do puts in RR_2 in main thread
    vm4.invoke(() -> WANTestBase.doPuts(testName + "_RR_2", 500));
    // sleep for some time to let all the events propagate to remote site
    Thread.sleep(20);
    // vm4.invoke(() -> WANTestBase.verifyQueueSize( "ln", 0 ));
    vm2.invoke(() -> WANTestBase.validateRegionSize(testName + "_RR_1", 1000));
    vm3.invoke(() -> WANTestBase.validateRegionSize(testName + "_RR_2", 500));

    pause(2000);
    vm4.invoke(() -> WANTestBase.checkQueueStats("ln", 0, 1500, 1500, 1500));
    vm4.invoke(() -> WANTestBase.checkBatchStats("ln", 75));
    vm4.invoke(() -> WANTestBase.checkUnProcessedStats("ln", 0));


    vm5.invoke(() -> WANTestBase.checkQueueStats("ln", 0, 1500, 0, 0));
    vm5.invoke(() -> WANTestBase.checkBatchStats("ln", 0));
    vm5.invoke(() -> WANTestBase.checkUnProcessedStats("ln", 1500));
  }

  /**
   *
   * Not Disabled - see ticket #52118
   *
   * NOTE: The test failure is avoided by having a larger number of puts operation so that
   * WANTestBase.verifyRegionQueueNotEmpty("ln" )) is successful as there is a significant delay
   * during the high number of puts.
   *
   * In future if this failure reappears, the put operations must be increase or a better fix must
   * be found.
   *
   * 1 region and sender configured on local site and 1 region and a receiver configured on remote
   * site. Puts to the local region are in progress. Remote region is destroyed in the middle.
   *
   * Better fix : slowed down the receiver after every create event, So a huge number of puts is not
   * required.
   *
   *
   */
  @Test
  public void testReplicatedSerialPropagationWithRemoteRegionDestroy() throws Exception {
    int numEntries = 2000;
    Integer lnPort = vm0.invoke(() -> WANTestBase.createFirstLocatorWithDSId(1));
    Integer nyPort = vm1.invoke(() -> WANTestBase.createFirstRemoteLocator(2, lnPort));

    // these are part of remote site
    vm2.invoke(() -> WANTestBase.createCache(nyPort));

    // create one RR (RR_1) on remote site
    vm2.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR_1", null, isOffHeap()));


    vm2.invoke(() -> WANTestBase.createReceiver());

    // This slows down the receiver
    vm2.invoke(() -> addListenerToSleepAfterCreateEvent(1000, getUniqueName() + "_RR_1"));


    // these are part of local site
    vm4.invoke(() -> WANTestBase.createCache(lnPort));
    vm5.invoke(() -> WANTestBase.createCache(lnPort));
    vm6.invoke(() -> WANTestBase.createCache(lnPort));
    vm7.invoke(() -> WANTestBase.createCache(lnPort));

    // create one RR (RR_1) on local site
    vm4.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR_1", "ln", isOffHeap()));
    vm5.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR_1", "ln", isOffHeap()));
    vm6.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR_1", "ln", isOffHeap()));
    vm7.invoke(() -> WANTestBase.createReplicatedRegion(testName + "_RR_1", "ln", isOffHeap()));

    // senders are created on local site
    vm4.invoke(() -> WANTestBase.createSender("ln", 2, false, 100, 100, false, false, null, true));
    vm5.invoke(() -> WANTestBase.createSender("ln", 2, false, 100, 100, false, false, null, true));

    // start the senders on local site
    startSenderInVMs("ln", vm4, vm5);

    // start puts in RR_1 in another thread
    AsyncInvocation inv1 =
        vm4.invokeAsync(() -> WANTestBase.doPuts(testName + "_RR_1", numEntries));
    // destroy RR_1 in remote site
    vm2.invoke(() -> WANTestBase.destroyRegion(testName + "_RR_1", 5));

    try {
      inv1.join();
    } catch (InterruptedException e) {
      e.printStackTrace();
      fail();
    }

    // assuming some events might have been dispatched before the remote region was destroyed,
    // sender's region queue will have events less than 1000 but the queue will not be empty.
    // NOTE: this much verification might be sufficient in DUnit. Hydra will take care of
    // more in depth validations.
    vm4.invoke(() -> WANTestBase.verifyRegionQueueNotEmpty("ln"));

    // verify that all is well in local site. All the events should be present in local region
    vm4.invoke(() -> WANTestBase.validateRegionSize(testName + "_RR_1", numEntries));

    // like a latch to guarantee at least one exception returned
    vm4.invoke(() -> await()
        .untilAsserted(() -> WANTestBase.verifyQueueSize("ln", 0)));

    vm4.invoke(() -> WANTestBase.checkBatchStats("ln", true, true));

    vm5.invoke(() -> WANTestBase.checkUnProcessedStats("ln", numEntries));

    vm2.invoke(() -> WANTestBase.checkExceptionStats(1));

  }

  @Test
  public void testSerialPropagationWithFilter() {

    Integer lnPort = vm0.invoke(() -> WANTestBase.createFirstLocatorWithDSId(1));
    Integer nyPort = vm1.invoke(() -> WANTestBase.createFirstRemoteLocator(2, lnPort));

    createCacheInVMs(nyPort, vm2, vm3);
    createReceiverInVMs(vm2, vm3);

    createCacheInVMs(lnPort, vm4, vm5, vm6, vm7);

    vm4.invoke(() -> WANTestBase.createSender("ln", 2, false, 100, 10, false, false,
        new MyGatewayEventFilter(), true));
    vm5.invoke(() -> WANTestBase.createSender("ln", 2, false, 100, 10, false, false,
        new MyGatewayEventFilter(), true));

    vm4.invoke(() -> WANTestBase.createPartitionedRegion(testName, "ln", 1, 100, isOffHeap()));
    vm5.invoke(() -> WANTestBase.createPartitionedRegion(testName, "ln", 1, 100, isOffHeap()));
    vm6.invoke(() -> WANTestBase.createPartitionedRegion(testName, "ln", 1, 100, isOffHeap()));
    vm7.invoke(() -> WANTestBase.createPartitionedRegion(testName, "ln", 1, 100, isOffHeap()));

    startSenderInVMs("ln", vm4, vm5);

    vm2.invoke(() -> WANTestBase.createPartitionedRegion(testName, null, 1, 100, isOffHeap()));
    vm3.invoke(() -> WANTestBase.createPartitionedRegion(testName, null, 1, 100, isOffHeap()));

    vm4.invoke(() -> WANTestBase.doPuts(testName, 1000));

    vm2.invoke(() -> WANTestBase.validateRegionSize(testName, 800));

    pause(2000);
    vm4.invoke(() -> WANTestBase.checkQueueStats("ln", 0, 1000, 900, 800));
    vm4.invoke(() -> WANTestBase.checkEventFilteredStats("ln", 200));
    vm4.invoke(() -> WANTestBase.checkBatchStats("ln", 80));
    vm4.invoke(() -> WANTestBase.checkUnProcessedStats("ln", 0));


    vm5.invoke(() -> WANTestBase.checkQueueStats("ln", 0, 1000, 0, 0));
    vm5.invoke(() -> WANTestBase.checkBatchStats("ln", 0));
    vm5.invoke(() -> WANTestBase.checkUnProcessedStats("ln", 900));
  }

  @Test
  public void testSerialPropagationConflation() throws Exception {
    Integer lnPort = vm0.invoke(() -> WANTestBase.createFirstLocatorWithDSId(1));
    Integer nyPort = vm1.invoke(() -> WANTestBase.createFirstRemoteLocator(2, lnPort));

    createCacheInVMs(nyPort, vm2, vm3);
    createReceiverInVMs(vm2, vm3);

    createCacheInVMs(lnPort, vm4, vm5, vm6, vm7);

    vm4.invoke(() -> WANTestBase.createSender("ln", 2, false, 100, 10, true, false, null, true));

    vm4.invoke(() -> WANTestBase.createPartitionedRegion(testName, "ln", 0, 100, isOffHeap()));
    vm5.invoke(() -> WANTestBase.createPartitionedRegion(testName, "ln", 0, 100, isOffHeap()));
    vm6.invoke(() -> WANTestBase.createPartitionedRegion(testName, "ln", 0, 100, isOffHeap()));
    vm7.invoke(() -> WANTestBase.createPartitionedRegion(testName, "ln", 0, 100, isOffHeap()));

    vm4.invoke(() -> WANTestBase.startSender("ln"));

    vm4.invoke(() -> WANTestBase.pauseSender("ln"));

    vm2.invoke(() -> WANTestBase.createPartitionedRegion(testName, null, 1, 100, isOffHeap()));
    vm3.invoke(() -> WANTestBase.createPartitionedRegion(testName, null, 1, 100, isOffHeap()));

    final Map keyValues = new HashMap();
    final Map updateKeyValues = new HashMap();
    for (int i = 0; i < 1000; i++) {
      keyValues.put(i, i);
    }

    vm4.invoke(() -> WANTestBase.putGivenKeyValue(testName, keyValues));

    vm4.invoke(() -> WANTestBase.checkQueueSize("ln", keyValues.size()));
    for (int i = 0; i < 500; i++) {
      updateKeyValues.put(i, i + "_updated");
    }

    vm4.invoke(() -> WANTestBase.putGivenKeyValue(testName, updateKeyValues));

    vm4.invoke(() -> WANTestBase.checkQueueSize("ln", keyValues.size() + updateKeyValues.size()));

    vm2.invoke(() -> WANTestBase.validateRegionSize(testName, 0));

    vm4.invoke(() -> WANTestBase.putGivenKeyValue(testName, updateKeyValues));

    vm4.invoke(() -> WANTestBase.checkQueueSize("ln", keyValues.size() + updateKeyValues.size()));

    vm4.invoke(() -> WANTestBase.resumeSender("ln"));

    keyValues.putAll(updateKeyValues);
    vm2.invoke(() -> WANTestBase.validateRegionSize(testName, keyValues.size()));
    vm3.invoke(() -> WANTestBase.validateRegionSize(testName, keyValues.size()));

    vm2.invoke(() -> WANTestBase.validateRegionContents(testName, keyValues));
    vm3.invoke(() -> WANTestBase.validateRegionContents(testName, keyValues));

    pause(2000);
    vm4.invoke(() -> WANTestBase.checkQueueStats("ln", 0, 2000, 2000, 1500));
    vm4.invoke(() -> WANTestBase.checkConflatedStats("ln", 500));
  }

  private void checkQueuesAreEmptyAndOnlyCompleteTransactionsAreReplicated(
      boolean isBatchesRedistributed) {
    // Wait for sender queues to be empty
    List<Integer> v4List =
        vm4.invoke(() -> WANTestBase.getSenderStats("ln", 0));
    List<Integer> v5List =
        vm5.invoke(() -> WANTestBase.getSenderStats("ln", 0));
    List<Integer> v6List =
        vm6.invoke(() -> WANTestBase.getSenderStats("ln", 0));
    List<Integer> v7List =
        vm7.invoke(() -> WANTestBase.getSenderStats("ln", 0));

    // queue size must be 0
    assertThat(v4List.get(0) + v5List.get(0) + v6List.get(0) + v7List.get(0)).isEqualTo(0);

    // batches redistributed:
    int batchesRedistributed = v4List.get(5) + v5List.get(5) + v6List.get(5) + v7List.get(5);
    if (isBatchesRedistributed) {
      assertThat(batchesRedistributed).isGreaterThan(0);
    } else {
      assertThat(batchesRedistributed).isEqualTo(0);
    }

    // batches with incomplete transactions must be 0
    assertThat(v4List.get(13) + v5List.get(13) + v6List.get(13) + v7List.get(13)).isEqualTo(0);
  }
}
