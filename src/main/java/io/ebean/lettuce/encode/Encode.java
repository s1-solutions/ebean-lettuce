package io.ebean.lettuce.encode;

public interface Encode {

  byte[] encode(Object value);

  Object decode(byte[] data);
}
