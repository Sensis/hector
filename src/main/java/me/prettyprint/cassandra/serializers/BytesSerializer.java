package me.prettyprint.cassandra.serializers;

import java.nio.ByteBuffer;

import me.prettyprint.hector.api.Serializer;


/**
 * The BytesExtractor is a simple identity function. It supports the Extractor interface and
 * implements the fromBytes and toBytes as simple identity functions.
 *
 * @author Ran Tavory
 *
 */
public final class BytesSerializer extends AbstractSerializer<ByteBuffer> implements Serializer<ByteBuffer>{

  private static BytesSerializer instance = new BytesSerializer();

  public static BytesSerializer get() {
    return instance;
  }

  @Override
  public ByteBuffer fromByteBuffer(ByteBuffer bytes) {
    return bytes;
  }

  @Override
  public ByteBuffer toByteBuffer(ByteBuffer obj) {
    return obj;
  }
}
