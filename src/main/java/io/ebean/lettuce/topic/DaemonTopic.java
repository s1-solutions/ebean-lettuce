package io.ebean.lettuce.topic;

import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;

/**
 * Topic subscriber that has re-connect notification.
 */
public interface DaemonTopic {

  /**
   * Subscribe to the topic/channel (blocking).
   *
   * @param redisConnection The redis connection to subscribe (and block on).
   */
  void subscribe(StatefulRedisPubSubConnection<byte[], byte[]> redisConnection);

  /**
   * Notify that the topic subscription has been connected (or reconnected).
   */
  void notifyConnected();
}
