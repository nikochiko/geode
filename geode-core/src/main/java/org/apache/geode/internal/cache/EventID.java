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
package org.apache.geode.internal.cache;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.Logger;

import org.apache.geode.DataSerializer;
import org.apache.geode.InternalGemFireException;
import org.apache.geode.annotations.Immutable;
import org.apache.geode.annotations.internal.MakeNotStatic;
import org.apache.geode.distributed.DistributedMember;
import org.apache.geode.distributed.DistributedSystem;
import org.apache.geode.distributed.internal.membership.InternalDistributedMember;
import org.apache.geode.internal.HeapDataOutputStream;
import org.apache.geode.internal.cache.ha.HARegionQueue;
import org.apache.geode.internal.cache.ha.ThreadIdentifier;
import org.apache.geode.internal.cache.tier.sockets.ClientProxyMembershipID;
import org.apache.geode.internal.serialization.ByteArrayDataInput;
import org.apache.geode.internal.serialization.DataSerializableFixedID;
import org.apache.geode.internal.serialization.DeserializationContext;
import org.apache.geode.internal.serialization.KnownVersion;
import org.apache.geode.internal.serialization.SerializationContext;
import org.apache.geode.internal.serialization.StaticSerialization;
import org.apache.geode.internal.util.Breadcrumbs;
import org.apache.geode.logging.internal.log4j.api.LogService;
import org.apache.geode.util.internal.GeodeGlossary;

/**
 * This class uniquely identifies any Region Operation like create, update destroy etc. It is
 * composed of three parts , namely :- 1) DistributedMembershipID 2) ThreadID 3) SequenceID This
 * helps in sequencing the events belonging to a unique producer.
 */
public class EventID implements DataSerializableFixedID, Serializable, Externalizable {
  private static final Logger logger = LogService.getLogger();

  /** turns on very verbose logging ove membership id bytes */
  private static final boolean LOG_ID_BYTES =
      Boolean.getBoolean(GeodeGlossary.GEMFIRE_PREFIX + "log-event-member-id-bytes");

  /**
   * Uniquely identifies the distributed member VM in which the Event is produced
   */
  private byte[] membershipID;

  /**
   * Uniquely identifies the thread producing the event
   */
  private long threadID;

  /**
   * Uniquely identifies individual event produced by a given thread
   */
  private long sequenceID;

  private int bucketID;

  private byte breadcrumbCounter = 0x0;

  public void incBreadcrumbCounter() {
    breadcrumbCounter++;
  }

  /** The versions in which this message was modified */
  @Immutable
  private static final KnownVersion[] dsfidVersions = new KnownVersion[0];


  private static final ThreadLocal<ThreadAndSequenceIDWrapper> threadIDLocal =
      ThreadLocal.withInitial(ThreadAndSequenceIDWrapper::new);

  private transient int hashCode = 0;

  /**
   * the distributed system associated with the static client_side_event_identity
   */
  @MakeNotStatic
  private static volatile DistributedSystem system = null;

  /**
   * the membership id of the distributed system in this client (if running in a client) that is
   * reflected in client_side_event_identity
   */
  @MakeNotStatic
  private static DistributedMember systemMemberId;

  /**
   * this form of client ID is used in event identifiers to reduce the size of the ID
   */
  @MakeNotStatic
  private static volatile byte[] client_side_event_identity = null;

  /**
   * An array containing the helper class objects which are used to create optimized byte array for
   * an eventID , which can be sent on the network
   */
  @Immutable
  static final List<AbstractEventIDByteArrayFiller> fillerArray =
      Collections.unmodifiableList(Arrays.asList(
          new ByteEventIDByteArrayFiller(), new ShortEventIDByteArrayFiller(),
          new IntegerEventIDByteArrayFiller(), new LongEventIDByteArrayFiller()));

  /**
   * Constructor used for creating EventID object at the actual source of creation of Event. The
   * thread identification & sequence ID of the event is done using ThreadLocal object. The
   * membershipId must be created with EventID.getMembershipId() and not the methods or byte array
   * stored in a ClientProxyMembershipID as those are heavyweight identifiers and they will cause
   * serialization and comparison problems when used in EventIDs
   */
  private EventID(final byte[] membershipID) {
    this.membershipID = membershipID;
    // If the DS is closed & restarted can we continue with the
    // existing Thread ID & Sequenec ID. Should not be an issue.
    // But we should not cache membershipID as for the same thread it can
    // differ. Hence it should be passed as parameter in the constructor
    ThreadAndSequenceIDWrapper wrapper = threadIDLocal.get();
    threadID = wrapper.threadID;
    sequenceID = wrapper.getAndIncrementSequenceID();
    bucketID = -1;
  }

  /**
   * constructor for creating an event ID originating in the local cache
   *
   * @param sys the local distributed system
   */
  public EventID(DistributedSystem sys) {
    this(initializeAndGetDSEventIdentity(sys));
  }

  public static byte[] getMembershipId(DistributedSystem sys) {
    return EventID.initializeAndGetDSEventIdentity(sys);
  }

  public static void unsetDS() {
    system = null;
  }

  /**
   * Convert a ClientProxyMembershipID distributed member ID array into one usable by EventIDs
   *
   * @param client the client's ID
   * @return a byte array that may be used in EventID formation
   */
  public static byte[] getMembershipId(ClientProxyMembershipID client) {
    try {
      HeapDataOutputStream hdos = new HeapDataOutputStream(256, client.getClientVersion());
      ((InternalDistributedMember) client.getDistributedMember()).writeEssentialData(hdos);
      return hdos.toByteArray();
    } catch (IOException ioe) {
      throw new InternalGemFireException(
          "Unable to serialize identity",
          ioe);
    }
  }

  /**
   * Returns the thread id used by the calling thread for its event ids
   *
   * @since GemFire 5.7
   */
  public static long getThreadId() {
    ThreadAndSequenceIDWrapper wrapper = threadIDLocal.get();
    return wrapper.threadID;
  }

  /**
   * Returns the next reservable sequence id used by the calling thread for its event ids. Note that
   * the returned id is not yet reserved by the calling thread.
   *
   * @since GemFire 5.7
   */
  public static long getSequenceId() {
    ThreadAndSequenceIDWrapper wrapper = threadIDLocal.get();
    return wrapper.sequenceID;
  }

  /**
   * Reserves and returns a sequence id for the calling thread to be used for an event id.
   *
   * @since GemFire 5.7
   */
  public static long reserveSequenceId() {
    ThreadAndSequenceIDWrapper wrapper = threadIDLocal.get();
    return wrapper.getAndIncrementSequenceID();
  }

  public void reserveSequenceId(int count) {
    ThreadAndSequenceIDWrapper wrapper = threadIDLocal.get();
    wrapper.reserveSequenceID(count);
  }

  /**
   * Constructor used for creating an EventID with specified sequenceID for putAll
   */
  public EventID(EventID eventId, int offset) {
    assert (eventId != null);
    membershipID = eventId.getMembershipID();
    threadID = eventId.getThreadID();
    sequenceID = eventId.getSequenceID() + offset;
    bucketID = -1;
  }

  /**
   * Constructor which explicitly sets all the fields and by-passes auto-generation of thread and
   * seq. ids. This is used by the QRM thread and by ServerConnection threads that have cached their
   * client ID byte arrays. It is also used by many unit test methods to create fake event
   * identifiers.
   *
   * @param memId - membership id for this entry - must be created by EventID.getMembershipID()
   * @param threadId - thread id for this entry
   * @param seqId - sequence id for this entry
   */
  public EventID(byte[] memId, long threadId, long seqId) {
    membershipID = memId;
    threadID = threadId;
    sequenceID = seqId;
    bucketID = -1;
  }

  public EventID(byte[] memId, long threadId, long seqId, int bucketId) {
    membershipID = memId;
    threadID = threadId;
    sequenceID = seqId;
    bucketID = bucketId;
  }

  /** support for migrating across threads for Hydra */
  @SuppressWarnings("unused") // for Hydra
  public static Object getThreadLocalDataForHydra() {
    Object result = threadIDLocal.get();
    threadIDLocal.set(null);
    return result;
  }

  /** support for migrating across threads for Hydra */
  @SuppressWarnings("unused") // for Hydra
  public static void setThreadLocalDataForHydra(Object wrapper) {
    if (!(wrapper instanceof ThreadAndSequenceIDWrapper)) {
      throw new IllegalArgumentException(
          "Expected a ThreadAndSequenceIdWrapper but received " + wrapper);
    }
    threadIDLocal.set((ThreadAndSequenceIDWrapper) wrapper);
  }

  /**
   * Default constructor used for deserialization of EventID object
   *
   */
  public EventID() {}

  public long getThreadID() {
    return threadID;
  }

  public void setThreadID(long threadID) {
    this.threadID = threadID;
  }

  public byte[] getMembershipID() {
    return membershipID;
  }

  public int getBucketID() {
    return bucketID;
  }

  /**
   * starting in v6.5 this method returns a somewhat crippled Identifier. It is missing any durable
   * attributes and roles information but contains all other info about the member.
   *
   * @return the member that initiated this event
   */
  public InternalDistributedMember getDistributedMember() {
    return getDistributedMember(KnownVersion.CURRENT);
  }

  /**
   * deserialize the memberID bytes using the given version. The correct thing to do would be to
   * have EventID carry the version ordinal of the serialized memberID, or to have it be part of the
   * memberID bytes and use that version to deserialize the bytes. Clients prior to 1.1.0 need to
   * have UUID bytes in the memberID. Newer clients don't require this.
   */
  public InternalDistributedMember getDistributedMember(KnownVersion targetVersion) {
    KnownVersion disVersion = null;
    if (targetVersion.isOlderThan(KnownVersion.GEODE_1_1_0)) {
      // clients expect to receive UUID bytes, which are only
      // read if the stream's version is 1.0.0-incubating
      disVersion = KnownVersion.GFE_90;
    }
    InternalDistributedMember result = null;
    try (ByteArrayDataInput dis = new ByteArrayDataInput(membershipID, disVersion)) {
      result = InternalDistributedMember.readEssentialData(dis);
    } catch (IOException e) {
      // nothing can be done about this
    } catch (ClassNotFoundException e) {
      // ditto
    }
    return result;
  }

  public long getSequenceID() {
    return sequenceID;
  }

  /**
   * Returns a byte[] whose contents are calculated by calling
   * {@link #getOptimizedByteArrayForEventID}
   *
   * @since GemFire 5.7
   */
  public byte[] calcBytes() {
    return getOptimizedByteArrayForEventID(getThreadID(), getSequenceID());
  }

  @Override
  public int getDSFID() {
    return EVENT_ID;
  }

  @Override
  public void toData(DataOutput dop,
      SerializationContext context) throws IOException {
    KnownVersion version = StaticSerialization.getVersionForDataStream(dop);
    // if we are sending to old clients we need to reserialize the ID
    // using the client's version to ensure it gets the proper on-wire form
    // of the identifier
    // See GEODE-3072
    if (membershipID != null && version.isOlderThan(KnownVersion.GEODE_1_1_0)) {
      InternalDistributedMember member = getDistributedMember(KnownVersion.GFE_90);
      // reserialize with the client's version so that we write the UUID
      // bytes
      HeapDataOutputStream hdos = new HeapDataOutputStream(KnownVersion.GFE_90);
      member.writeEssentialData(hdos);
      DataSerializer.writeByteArray(hdos.toByteArray(), dop);
    } else {
      DataSerializer.writeByteArray(membershipID, dop);
    }
    DataSerializer.writeByteArray(getOptimizedByteArrayForEventID(threadID, sequenceID),
        dop);
    dop.writeInt(bucketID);
    dop.writeByte(breadcrumbCounter);
  }

  @Override
  public void fromData(DataInput di,
      DeserializationContext context) throws IOException, ClassNotFoundException {
    membershipID = DataSerializer.readByteArray(di);
    ByteBuffer eventIdParts = ByteBuffer.wrap(DataSerializer.readByteArray(di));
    threadID = readEventIdPartsFromOptimizedByteArray(eventIdParts);
    sequenceID = readEventIdPartsFromOptimizedByteArray(eventIdParts);
    bucketID = di.readInt();
    breadcrumbCounter = di.readByte();
  }

  @Override
  public void writeExternal(ObjectOutput out) throws IOException {
    DataSerializer.writeByteArray(membershipID, out);
    DataSerializer.writeByteArray(getOptimizedByteArrayForEventID(threadID, sequenceID),
        out);
    out.writeInt(bucketID);
  }

  @Override
  public void readExternal(ObjectInput in) throws IOException {
    membershipID = DataSerializer.readByteArray(in);
    ByteBuffer eventIdParts = ByteBuffer.wrap(DataSerializer.readByteArray(in));
    threadID = readEventIdPartsFromOptimizedByteArray(eventIdParts);
    sequenceID = readEventIdPartsFromOptimizedByteArray(eventIdParts);
    bucketID = in.readInt();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    EventID other = (EventID) obj;
    if (sequenceID != other.sequenceID) {
      return false;
    }
    if (threadID != other.threadID) {
      return false;
    }
    return equalMembershipIds(membershipID, other.membershipID);
  }

  /** 1.0.0 client IDs contain a UUID and member-weight byte that are all zero */
  private static final int NULL_90_MEMBER_DATA_LENGTH = 17;

  /** minimum length of an ID array */
  private static final int MINIMUM_ID_LENGTH = 19;

  /**
   * check to see if membership ID byte arrays are equal
   */
  public static boolean equalMembershipIds(byte[] m1, byte[] m2) {
    int sizeDifference = Math.abs(m1.length - m2.length);
    if (sizeDifference != 0 && sizeDifference != NULL_90_MEMBER_DATA_LENGTH) {
      return false;
    }
    for (int i = 0; i < m1.length; i++) {
      if (i >= m2.length) {
        return nullUUIDCheck(m1, i);
      }
      if (m1[i] != m2[i]) {
        return false;
      }
    }
    if (m1.length != m2.length) {
      return nullUUIDCheck(m2, m1.length);
    }
    return true;
  }

  /**
   * v1.0.0 memberIDs in EventIDs may have trailing bytes that should be ignored
   */
  private static boolean nullUUIDCheck(byte[] memberID, int position) {
    if (position < 0) {
      return false;
    }
    if (memberID.length - position != NULL_90_MEMBER_DATA_LENGTH) {
      return false;
    }
    for (int i = position; i < memberID.length; i++) {
      if (memberID[i] != 0) {
        return false;
      }
    }
    return true;
  }

  /**
   * form the hashcode for the memberID byte array
   */
  public static int hashCodeMemberId(byte[] memberID) {
    if (memberID.length < (NULL_90_MEMBER_DATA_LENGTH + MINIMUM_ID_LENGTH)
        || !nullUUIDCheck(memberID, memberID.length - NULL_90_MEMBER_DATA_LENGTH)) {
      return Arrays.hashCode(memberID);
    }
    byte[] newID = new byte[memberID.length - NULL_90_MEMBER_DATA_LENGTH];
    System.arraycopy(memberID, 0, newID, 0, newID.length);
    return Arrays.hashCode(newID);
  }

  public int hashCode() {
    if (hashCode == 0) {
      final int prime = 31;
      int result = 1;
      result = prime * result + hashCodeMemberId(membershipID);
      result = prime * result + (int) (sequenceID ^ (sequenceID >>> 32));
      result = prime * result + (int) (threadID ^ (threadID >>> 32));
      hashCode = result;
    }
    return hashCode;
  }

  String getShortClassName() {
    String cname = getClass().getName();
    return cname.substring(getClass().getPackage().getName().length() + 1);
  }

  public String expensiveToString() {
    Object mbr;
    try (ByteArrayDataInput in = new ByteArrayDataInput(membershipID)) {
      mbr = InternalDistributedMember.readEssentialData(in);
    } catch (Exception e) {
      mbr = membershipID; // punt and use the bytes
    }
    return "EventID[" + mbr + ";threadID=" + ThreadIdentifier.toDisplayString(threadID)
        + ";sequenceID=" + sequenceID + (Breadcrumbs.ENABLED ? ";bcrumb=" + breadcrumbCounter : "")
        + (bucketID >= 0 ? (";bucketID=" + bucketID) : "") + "]";
  }

  @Override
  public String toString() {
    if (logger.isDebugEnabled()) {
      return expensiveToString();
    } else {
      return cheapToString();
    }
  }


  public String cheapToString() {
    final StringBuilder buf = new StringBuilder();
    buf.append(getShortClassName());
    if (LOG_ID_BYTES) {
      buf.append("[membershipID=");
      for (int i = 0; i < membershipID.length; i++) {
        buf.append(membershipID[i]);
        if (i < membershipID.length - 1) {
          buf.append(',');
        }
      }
      buf.append(";");
    } else {
      buf.append("[id=").append(membershipID.length).append(" bytes;");
    }
    // buf.append(this.membershipID.toString());
    buf.append("threadID=");
    buf.append(ThreadIdentifier.toDisplayString(threadID));
    buf.append(";sequenceID=");
    buf.append(sequenceID);

    if (Breadcrumbs.ENABLED) {
      buf.append(";bcrumb=").append(breadcrumbCounter);
    }

    if (bucketID >= 0) {
      buf.append(";bucketId=");
      buf.append(bucketID);
    }
    buf.append("]");
    return buf.toString();
  }

  private static byte[] initializeAndGetDSEventIdentity(DistributedSystem sys) {
    if (sys == null) {
      // DistributedSystem is required now before handshaking -Kirk
      throw new IllegalStateException(
          "Attempting to handshake with CacheServer before creating DistributedSystem and Cache.");
    }
    if (EventID.system != sys) {
      // DS already exists... make sure it's for current DS connection
      EventID.systemMemberId = sys.getDistributedMember();
      try (HeapDataOutputStream hdos = new HeapDataOutputStream(256, KnownVersion.CURRENT)) {
        ((InternalDistributedMember) EventID.systemMemberId).writeEssentialData(hdos);
        client_side_event_identity = hdos.toByteArray();
      } catch (IOException ioe) {
        throw new InternalGemFireException(
            "Unable to serialize identity",
            ioe);
      }
      if (((InternalDistributedMember) EventID.systemMemberId).getMembershipPort() != 0) {
        EventID.system = sys;
      }
    }
    return EventID.client_side_event_identity;

  }

  /**
   * Returns the number of bytes needed to store the given value. This calculation is needed to
   * create the optimized byte-array for an eventId, which will be sent across the network
   *
   * @param id - the long value ( threadId or sequenceId in this context)
   * @return - the number of byte taken by this value
   */
  public static int getByteSizeForValue(long id) {
    final int length;

    // compare threadId to find its range
    if (id <= Byte.MAX_VALUE) {
      length = 1;
    } else if (id <= Short.MAX_VALUE) {
      length = 2;
    } else if (id <= Integer.MAX_VALUE) {
      length = 4;
    } else {
      length = 8;
    }

    return length;
  }

  /**
   * Gets the optimized byte-array representation of an eventId which can be sent across the
   * network.
   * For client to server messages, the membership id part of event-id is not need to be sent with
   * each event. Also, the threadId and sequenceId need not be sent as long if their value is small.
   * This function returns the optimal byte-array for the eventId.
   * <p>
   * This is public for unit testing purposes only
   *
   * @param threadId - the long value of threadId
   * @param sequenceId - the long value of sequenceId
   * @return - the optimized byte-array representing the eventId
   */
  public static byte[] getOptimizedByteArrayForEventID(long threadId, long sequenceId) {

    int threadIdLength = getByteSizeForValue(threadId);
    int threadIdIndex = (threadIdLength == 1) ? 0 : ((threadIdLength / 4) + 1);

    int sequenceIdLength = getByteSizeForValue(sequenceId);
    int sequenceIdIndex = (sequenceIdLength == 1) ? 0 : ((sequenceIdLength / 4) + 1);

    int byteBufferLength = 2 + threadIdLength + sequenceIdLength;
    ByteBuffer buffer = ByteBuffer.allocate(byteBufferLength);
    fillerArray.get(threadIdIndex).fill(buffer, threadId);
    fillerArray.get(sequenceIdIndex).fill(buffer, sequenceId);
    return buffer.array();

  }

  /**
   * Reads the optimized byte-array representation of an eventId and returns the long value of
   * threadId or sequenceId. The first invocation of this method on bytebuffer returns the threadId
   * and the second returns the sequenceId.
   *
   * @param buffer - the byte-buffer wrapping the optimized byte-array for the eventId
   * @return - long value of threadId or sequenceId
   */
  public static long readEventIdPartsFromOptimizedByteArray(ByteBuffer buffer) {
    byte byteType = buffer.get();
    return fillerArray.get(byteType).read(buffer);
  }

  /**
   * Abstract helper class used to create optimized byte-array for the eventid, which will be sent
   * across the network.
   *
   */
  protected abstract static class AbstractEventIDByteArrayFiller {
    /**
     * This method adds to the byte-buffer, the token indicating the type of the passed 'id'
     * (threadId or sequenceId) and the optimal byte array representing the id depending on the
     * value of the 'id'.
     *
     * @param buffer - the byte buffer wrapping the byte array for the event id
     * @param id - the long value of threadId or sequenceId
     */
    public abstract void fill(ByteBuffer buffer, long id);

    /**
     * Reads the given buffer and returns the value as long.
     *
     * @param buffer - the byte-buffer containing the eventId parts which needs to be read
     * @return - the long value of id (threadId or sequenceId).
     */
    public abstract long read(ByteBuffer buffer);
  }

  protected static class ByteEventIDByteArrayFiller extends AbstractEventIDByteArrayFiller {
    /**
     * The token to indicate that given id ( threadId or sequenceId) is of type <code>Byte</code>
     */
    private static final byte EVENTID_BYTE = 0;

    /**
     * Writes the given 'id' to the given buffer as 'byte' preceded by a token indicating that it
     * is written as 'byte' type.
     *
     * @param buffer - the buffer in which id is to be written
     * @param id - the threadId or sequenceId to be written
     */
    @Override
    public void fill(ByteBuffer buffer, long id) {
      buffer.put(EVENTID_BYTE);
      buffer.put((byte) id);
    }

    /**
     * Reads the byte value of id from the buffer and returns it as long.
     *
     * @param buffer - the buffer from which 'id'(threadId or sequenceId) is to be read
     * @return - the value of the id as long
     *
     */
    @Override
    public long read(ByteBuffer buffer) {
      return buffer.get();
    }

  }

  protected static class ShortEventIDByteArrayFiller extends AbstractEventIDByteArrayFiller {
    /**
     * The token to indicate that given id ( threadId or sequenceId) is of type <code>Short</code>
     */
    private static final byte EVENTID_SHORT = 1;

    /**
     * Writes the given 'id' to the given buffer as 'short' preceded by a token indicating that it
     * is written as 'short' type.
     *
     * @param buffer - the buffer in which id is to be written
     * @param id - the threadId or sequenceId to be written
     */
    @Override
    public void fill(ByteBuffer buffer, long id) {
      buffer.put(EVENTID_SHORT);
      buffer.putShort((short) id);
    }

    /**
     * Reads the short value of id from the buffer and returns it as long.
     *
     * @param buffer - the buffer from which 'id'(threadId or sequenceId) is to be read
     * @return - the value of the id as long
     *
     */
    @Override
    public long read(ByteBuffer buffer) {
      return buffer.getShort();
    }
  }

  protected static class IntegerEventIDByteArrayFiller extends AbstractEventIDByteArrayFiller {
    /**
     * The token to indicate that given id ( threadId or sequenceId) is of type <code>Integer</code>
     */
    private static final byte EVENTID_INT = 2;

    /**
     * Writes the given 'id' to the given buffer as 'int' preceded by a token indicating that it is
     * written as 'int' type.
     *
     * @param buffer - the buffer in which id is to be written
     * @param id - the threadId or sequenceId to be written
     */
    @Override
    public void fill(ByteBuffer buffer, long id) {
      buffer.put(EVENTID_INT);
      buffer.putInt((int) id);
    }

    /**
     * Reads the int value of id from the buffer and returns it as long.
     *
     * @param buffer - the buffer from which 'id'(threadId or sequenceId) is to be read
     * @return - the value of the id as long
     *
     */
    @Override
    public long read(ByteBuffer buffer) {
      return buffer.getInt();
    }
  }

  protected static class LongEventIDByteArrayFiller extends AbstractEventIDByteArrayFiller {
    /**
     * The token to indicate that given id ( threadId or sequenceId) is of type <code>Long</code>
     */
    private static final byte EVENTID_LONG = 3;

    /**
     * Writes the given 'id' to the given buffer as 'long' preceded by a token indicating that it
     * is written as 'long' type.
     *
     * @param buffer - the buffer in which id is to be written
     * @param id - the threadId or sequenceId to be written
     */
    @Override
    public void fill(ByteBuffer buffer, long id) {
      buffer.put(EVENTID_LONG);
      buffer.putLong(id);
    }

    /**
     * Reads the long value of id from the buffer and returns it.
     *
     * @param buffer - the buffer from which 'id'(threadId or sequenceId) is to be read
     * @return - the value of the id as long
     *
     */
    @Override
    public long read(ByteBuffer buffer) {
      return buffer.getLong();
    }
  }

  static class ThreadAndSequenceIDWrapper {
    final long threadID;

    long sequenceID = (HARegionQueue.INIT_OF_SEQUENCEID + 1);

    @MakeNotStatic
    private static final AtomicLong atmLong = new AtomicLong(0);

    ThreadAndSequenceIDWrapper() {
      threadID = atmLong.incrementAndGet();
    }

    long getAndIncrementSequenceID() {
      return sequenceID++;
    }

    void reserveSequenceID(int size) {
      sequenceID += size;
    }
  }

  @Override
  public KnownVersion[] getSerializationVersions() {
    return dsfidVersions;
  }
}
