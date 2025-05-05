package io.ebean.lettuce;

import io.ebean.BackgroundExecutor;
import io.ebean.cache.ServerCacheFactory;
import io.ebean.cache.ServerCachePlugin;
import io.ebean.DatabaseBuilder;

public class LettuceCachePlugin implements ServerCachePlugin {

  /**
   * Create the ServerCacheFactory implementation.
   */
  @Override
  public ServerCacheFactory create(DatabaseBuilder config, BackgroundExecutor executor) {
    return new LettuceCacheFactory(config.settings(), executor);
  }
}
