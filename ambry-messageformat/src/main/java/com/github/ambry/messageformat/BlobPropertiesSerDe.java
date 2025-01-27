/**
 * Copyright 2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.github.ambry.messageformat;

import com.github.ambry.account.Account;
import com.github.ambry.account.Container;
import com.github.ambry.utils.Utils;
import io.netty.buffer.ByteBuf;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;


/**
 * Serializes and deserializes BlobProperties
 */
public class BlobPropertiesSerDe {

  static final short VERSION_1 = 1;
  static final short VERSION_2 = 2;
  static final short VERSION_3 = 3;
  static final short VERSION_4 = 4;
  private static final int VERSION_FIELD_SIZE_IN_BYTES = Short.BYTES;
  private static final int TTL_FIELD_SIZE_IN_BYTES = Long.BYTES;
  private static final int PRIVATE_FIELD_SIZE_IN_BYTES = Byte.BYTES;
  private static final int CREATION_TIME_FIELD_SIZE_IN_BYTES = Long.BYTES;
  private static final int BLOB_SIZE_FIELD_SIZE_IN_BYTES = Long.BYTES;
  private static final int ENCRYPTED_FIELD_SIZE_IN_BYTES = Byte.BYTES;

  public static int getBlobPropertiesSerDeSize(BlobProperties properties) {
    return VERSION_FIELD_SIZE_IN_BYTES + TTL_FIELD_SIZE_IN_BYTES + PRIVATE_FIELD_SIZE_IN_BYTES
        + CREATION_TIME_FIELD_SIZE_IN_BYTES + BLOB_SIZE_FIELD_SIZE_IN_BYTES + Utils.getIntStringLength(
        properties.getContentType()) + Utils.getIntStringLength(properties.getOwnerId()) + Utils.getIntStringLength(
        properties.getServiceId()) + Short.BYTES + Short.BYTES + ENCRYPTED_FIELD_SIZE_IN_BYTES
        + Utils.getIntStringLength(properties.getContentEncoding()) + Utils.getIntStringLength(
        properties.getFilename());
  }

  public static BlobProperties getBlobPropertiesFromStream(DataInputStream stream) throws IOException {
    short version = stream.readShort();
    if (version < VERSION_1 || version > VERSION_4) {
      throw new IllegalArgumentException("stream has unknown blob property version " + version);
    }
    long ttl = stream.readLong();
    boolean isPrivate = stream.readByte() == 1;
    long creationTime = stream.readLong();
    long blobSize = stream.readLong();
    String contentType = Utils.readIntString(stream);
    String ownerId = Utils.readIntString(stream);
    String serviceId = Utils.readIntString(stream);
    short accountId = version > VERSION_1 ? stream.readShort() : Account.UNKNOWN_ACCOUNT_ID;
    short containerId = version > VERSION_1 ? stream.readShort() : Container.UNKNOWN_CONTAINER_ID;
    boolean isEncrypted = version > VERSION_2 && stream.readByte() == (byte) 1;
    String contentEncoding = version > VERSION_3 ? Utils.readNullableIntString(stream) : null;
    String filename = version > VERSION_3 ? Utils.readNullableIntString(stream) : null;
    return new BlobProperties(blobSize, serviceId, ownerId, contentType, isPrivate, ttl, creationTime, accountId,
        containerId, isEncrypted, null, contentEncoding, filename, null);
  }

  /**
   * Serialize {@link BlobProperties} to buffer in the {@link #VERSION_4}
   * @param outputBuffer the {@link ByteBuffer} to which {@link BlobProperties} needs to be serialized
   * @param properties the {@link BlobProperties} that needs to be serialized
   */
  public static void serializeBlobProperties(ByteBuffer outputBuffer, BlobProperties properties) {
    if (outputBuffer.remaining() < getBlobPropertiesSerDeSize(properties)) {
      throw new IllegalArgumentException("Outut buffer does not have sufficient space to serialize blob properties");
    }
    outputBuffer.putShort(VERSION_4);
    outputBuffer.putLong(properties.getTimeToLiveInSeconds());
    outputBuffer.put(properties.isPrivate() ? (byte) 1 : (byte) 0);
    outputBuffer.putLong(properties.getCreationTimeInMs());
    outputBuffer.putLong(properties.getBlobSize());
    Utils.serializeNullableString(outputBuffer, properties.getContentType());
    Utils.serializeNullableString(outputBuffer, properties.getOwnerId());
    Utils.serializeNullableString(outputBuffer, properties.getServiceId());
    outputBuffer.putShort(properties.getAccountId());
    outputBuffer.putShort(properties.getContainerId());
    outputBuffer.put(properties.isEncrypted() ? (byte) 1 : (byte) 0);
    Utils.serializeNullableString(outputBuffer, properties.getContentEncoding());
    Utils.serializeNullableString(outputBuffer, properties.getFilename());
  }

  /**
   * Serialize {@link BlobProperties} to {@link ByteBuf} in the {@link #VERSION_4}
   * @param outputBuf the {@link ByteBuf} to which {@link BlobProperties} needs to be serialized
   * @param properties the {@link BlobProperties} that needs to be serialized
   */
  public static void serializeBlobProperties(ByteBuf outputBuf, BlobProperties properties) {
    if (!outputBuf.isWritable(getBlobPropertiesSerDeSize(properties))) {
      throw new IllegalArgumentException("Output buffer does not have sufficient space to serialize blob properties");
    }
    outputBuf.writeShort(VERSION_4);
    outputBuf.writeLong(properties.getTimeToLiveInSeconds());
    outputBuf.writeByte(properties.isPrivate() ? (byte) 1 : (byte) 0);
    outputBuf.writeLong(properties.getCreationTimeInMs());
    outputBuf.writeLong(properties.getBlobSize());
    Utils.serializeNullableString(outputBuf, properties.getContentType());
    Utils.serializeNullableString(outputBuf, properties.getOwnerId());
    Utils.serializeNullableString(outputBuf, properties.getServiceId());
    outputBuf.writeShort(properties.getAccountId());
    outputBuf.writeShort(properties.getContainerId());
    outputBuf.writeByte(properties.isEncrypted() ? (byte) 1 : (byte) 0);
    Utils.serializeNullableString(outputBuf, properties.getContentEncoding());
    Utils.serializeNullableString(outputBuf, properties.getFilename());
  }
}
