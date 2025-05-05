package io.ebean.lettuce;

import java.util.Set;

/**
 * Near cache invalidation.
 */
public interface NearCacheInvalidate {

  /**
   * Invalidate from near cache the given keys.
   */
  void invalidateKeys(Set<Object> keySet);

  /**
   * Invalidate from near cache the given key.
   */
  void invalidateKey(Object id);

  /**
   * Clear the near cache.
   */
  void invalidateClear();
}
