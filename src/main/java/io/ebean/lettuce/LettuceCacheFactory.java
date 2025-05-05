package io.ebean.lettuce;

import io.avaje.applog.AppLog;
import io.ebean.BackgroundExecutor;
import io.ebean.cache.*;
import io.ebean.DatabaseBuilder;
import io.ebean.meta.MetricVisitor;
import io.ebean.metric.MetricFactory;
import io.ebean.metric.TimedMetric;
import io.ebean.lettuce.encode.EncodeBeanData;
import io.ebean.lettuce.encode.EncodeManyIdsData;
import io.ebean.lettuce.encode.EncodeSerializable;
import io.ebean.lettuce.topic.DaemonTopic;
import io.ebeaninternal.server.cache.DefaultServerCache;
import io.ebeaninternal.server.cache.DefaultServerCacheConfig;
import io.ebeaninternal.server.cache.DefaultServerQueryCache;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.pubsub.RedisPubSubListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.System.Logger.Level.*;
import static java.util.Arrays.asList;

final class LettuceCacheFactory implements ServerCacheFactory {


    private static final System.Logger log = AppLog.getLogger(LettuceCacheFactory.class);

    private static final System.Logger queryLogger = AppLog.getLogger("io.ebean.cache.QUERY");
    private static final System.Logger logger = AppLog.getLogger("io.ebean.cache.CACHE");
    private static final System.Logger tableModLogger = AppLog.getLogger("io.ebean.cache.TABLEMODS");

    private static final int MSG_NEARCACHE_CLEAR = 1;
    private static final int MSG_NEARCACHE_KEYS = 2;
    private static final int MSG_NEARCACHE_KEY = 3;

    /**
     * Channel for standard L2 cache messages.
     */
    private static final String CHANNEL_L2 = "ebean.l2cache";

    /**
     * Channel specifically for near cache invalidation messages.
     */
    private static final String CHANNEL_NEAR = "ebean.l2near";

    private static final byte[] CHANNEL_L2_BYTES = encodeMessage(CHANNEL_L2);
    private static final byte[] CHANNEL_NEAR_BYTES = encodeMessage(CHANNEL_NEAR);

    private final ConcurrentHashMap<String, RQueryCache> queryCaches = new ConcurrentHashMap<>();
    private final Map<String, NearCacheInvalidate> nearCacheMap = new ConcurrentHashMap<>();
    private final EncodeManyIdsData encodeManyIdsData = new EncodeManyIdsData();
    private final EncodeBeanData encodeBeanData = new EncodeBeanData();
    private final EncodeSerializable encodeSerializable = new EncodeSerializable();
    private final BackgroundExecutor executor;

    private final RedisClient redisClient;
    private final StatefulRedisConnection<byte[], byte[]> redisConnection;

    private final NearCacheNotify nearCacheNotify;
    private final TimedMetric metricOutNearCache;
    private final TimedMetric metricOutTableMod;
    private final TimedMetric metricOutQueryCache;
    private final TimedMetric metricInNearCache;
    private final TimedMetric metricInTableMod;
    private final TimedMetric metricInQueryCache;
    private final String serverId = ModId.id();
    private final ReentrantLock lock = new ReentrantLock();
    private final StatefulRedisPubSubConnection<byte[], byte[]> redisPubSubConnection;
    private ServerCacheNotify listener;

    LettuceCacheFactory(DatabaseBuilder.Settings config, BackgroundExecutor executor) {
        this.executor = executor;
        this.nearCacheNotify = new DNearCacheNotify();
        MetricFactory factory = MetricFactory.get();
        this.metricOutTableMod = factory.createTimedMetric("l2a.outTableMod");
        this.metricOutQueryCache = factory.createTimedMetric("l2a.outQueryCache");
        this.metricOutNearCache = factory.createTimedMetric("l2a.outNearKeys");
        this.metricInTableMod = factory.createTimedMetric("l2a.inTableMod");
        this.metricInQueryCache = factory.createTimedMetric("l2a.inQueryCache");
        this.metricInNearCache = factory.createTimedMetric("l2a.inNearKeys");
        if (config.isDisableL2Cache()) {
            this.redisClient = null;
            this.redisConnection = null;
            this.redisPubSubConnection = null;
        } else {
            this.redisClient = getRedisClient(config);
            this.redisConnection = redisClient.connect(new ByteArrayCodec());
            this.redisPubSubConnection = redisClient.connectPubSub(new ByteArrayCodec());
//            setupSubscriber();
            new CacheDaemonTopic().subscribe(redisPubSubConnection);
        }
    }
//
//    private void setupSubscriber() {
//        StatefulRedisPubSubConnection<String, String> pubSubConnection = redisClient.connectPubSub();
//        pubSubConnection.addListener(new CacheChannelListener());
//        RedisPubSubCommands<String, String> sync = pubSubConnection.sync();
//        sync.subscribe(CHANNEL_L2, CHANNEL_NEAR);
//        logger.log(INFO, "Established connection to Redis");
//    }

    private RedisClient getRedisClient(DatabaseBuilder.Settings config) {
        RedisClient redisClient = config.getServiceObject(RedisClient.class);
        if (redisClient != null) {
            return redisClient;
        }
        LettuceConfig lettuceConfig = config.getServiceObject(LettuceConfig.class);
        if (lettuceConfig == null) {
            lettuceConfig = new LettuceConfig();
        }
        lettuceConfig.loadProperties(config.getProperties());
        log.log(INFO, "using l2cache redis host {0}:{1}", lettuceConfig.getServer(), lettuceConfig.getPort());
        return lettuceConfig.createClient();
    }

//    /**
//     * Return the JedisPool to use (only 1 at this stage).
//     */
//    private JedisPool getJedisPool(DatabaseBuilder.Settings config) {
//
//        JedisPool jedisPool = config.getServiceObject(JedisPool.class);
//        if (jedisPool != null) {
//            return jedisPool;
//        }
//        RedisConfig redisConfig = config.getServiceObject(RedisConfig.class);
//        if (redisConfig == null) {
//            redisConfig = new RedisConfig();
//        }
//        redisConfig.loadProperties(config.getProperties());
//        log.log(INFO, "using l2cache redis host {0}:{1}", redisConfig.getServer(), redisConfig.getPort());
//        return redisConfig.createPool();
//    }

    @Override
    public void visit(MetricVisitor visitor) {
        metricOutQueryCache.visit(visitor);
        metricOutTableMod.visit(visitor);
        metricOutNearCache.visit(visitor);
        metricInTableMod.visit(visitor);
        metricInQueryCache.visit(visitor);
        metricInNearCache.visit(visitor);
    }

    @Override
    public ServerCache createCache(ServerCacheConfig config) {
        if (config.isQueryCache()) {
            return createQueryCache(config);
        }
        return createNormalCache(config);
    }

    private ServerCache createNormalCache(ServerCacheConfig config) {
        LettuceCache lettuceCache = createRedisCache(config);
        boolean nearCache = config.getCacheOptions().isNearCache();
        if (!nearCache) {
            return config.tenantAware(lettuceCache);
        }

        String cacheKey = config.getCacheKey();
        DefaultServerCache near = new DefaultServerCache(new DefaultServerCacheConfig(config));
        near.periodicTrim(executor);
        DuelCache duelCache = new DuelCache(near, lettuceCache, cacheKey, nearCacheNotify);
        nearCacheMap.put(cacheKey, duelCache);
        return config.tenantAware(duelCache);
    }

    private LettuceCache createRedisCache(ServerCacheConfig config) {
        switch (config.getType()) {
            case NATURAL_KEY:
                return new LettuceCache(redisConnection, config, encodeSerializable);
            case BEAN:
                return new LettuceCache(redisConnection, config, encodeBeanData);
            case COLLECTION_IDS:
                return new LettuceCache(redisConnection, config, encodeManyIdsData);
            default:
                throw new IllegalArgumentException("Unexpected cache type? " + config.getType());
        }
    }

    private ServerCache createQueryCache(ServerCacheConfig config) {
        lock.lock();
        try {
            RQueryCache cache = queryCaches.get(config.getCacheKey());
            if (cache == null) {
                logger.log(DEBUG, "create query cache [{0}]", config.getCacheKey());
                cache = new RQueryCache(new DefaultServerCacheConfig(config));
                cache.periodicTrim(executor);
                queryCaches.put(config.getCacheKey(), cache);
            }
            return config.tenantAware(cache);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public ServerCacheNotify createCacheNotify(ServerCacheNotify listener) {
        this.listener = listener;
        return new RServerCacheNotify();
    }

    private void sendQueryCacheInvalidation(String name) {
        long nanos = System.nanoTime();

        try {

//            if (logger.isLoggable(DEBUG)) {
//                logger.log(DEBUG, "sendQueryCacheInvalidation {0}", name);
//            }

            RedisPubSubAsyncCommands<byte[], byte[]> commands = redisPubSubConnection.async();

            commands.publish(CHANNEL_L2_BYTES, encodeMessage(serverId + ":queryCache:" + name));

        } finally {
            metricOutQueryCache.addSinceNanos(nanos);
        }
    }

    private void sendTableMod(String formattedMsg) {

        long nanos = System.nanoTime();

        try {

//            if (logger.isLoggable(DEBUG)) {
//                logger.log(DEBUG, "sendTableMod {0}", formattedMsg);
//            }

            RedisPubSubAsyncCommands<byte[], byte[]> commands = redisPubSubConnection.async();

            commands.publish(CHANNEL_L2_BYTES, encodeMessage(serverId + ":tableMod:" + formattedMsg));

        } finally {
            metricOutTableMod.addSinceNanos(nanos);
        }
    }

    /**
     * Query cache implementation using Redis channel for message notifications.
     */
    private class RQueryCache extends DefaultServerQueryCache {

        RQueryCache(DefaultServerCacheConfig config) {
            super(config);
        }

        @Override
        public void clear() {
            super.clear();
            sendQueryCacheInvalidation(name);
        }

        /**
         * Process the invalidation message coming from the cluster.
         */
        private void invalidate() {
            queryLogger.log(DEBUG, "   CLEAR {0}(*) - cluster invalidate", name);
            super.clear();
        }
    }

    /**
     * Publish table modifications using Redis channel (to other cluster members)
     */
    private class RServerCacheNotify implements ServerCacheNotify {

        @Override
        public void notify(ServerCacheNotification tableModifications) {
            Set<String> dependentTables = tableModifications.getDependentTables();
            if (dependentTables != null && !dependentTables.isEmpty()) {
                StringBuilder msg = new StringBuilder(50);
                for (String table : dependentTables) {
                    msg.append(table).append(',');
                }
                String formattedMsg = msg.toString();
                if (tableModLogger.isLoggable(DEBUG)) {
                    tableModLogger.log(DEBUG, "Publish TableMods - {0}", formattedMsg);
                }
                sendTableMod(formattedMsg);
            }
        }
    }

    /**
     * Clear the query cache if we have it.
     */
    private void queryCacheInvalidate(String key) {
        long nanos = System.nanoTime();
        try {
//            if (logger.isLoggable(DEBUG)) {
//                logger.log(DEBUG, "queryCacheInvalidate {0}", key);
//            }
            RQueryCache queryCache = queryCaches.get(key);
            if (queryCache != null) {
                queryCache.invalidate();
            }
        } finally {
            metricInQueryCache.addSinceNanos(nanos);
        }
    }

    /**
     * Process a remote dependent table modify event.
     */
    private void processTableNotify(String rawMessage) {
        long nanos = System.nanoTime();
        try {
//            if (logger.isLoggable(DEBUG)) {
//                logger.log(DEBUG, "processTableNotify {0}", rawMessage);
//            }
            Set<String> tables = new HashSet<>(asList(rawMessage.split(",")));
            listener.notify(new ServerCacheNotification(tables));
        } finally {
            metricInTableMod.addSinceNanos(nanos);
        }
    }

    /**
     * Near cache notification using a specific Redis channel (CHANNEL_NEAR).
     */
    private class DNearCacheNotify implements NearCacheNotify {

        @Override
        public void invalidateKeys(String cacheKey, Set<Object> keySet) {
            try {
                sendMessage(messageInvalidateKeys(cacheKey, keySet));
            } catch (Exception e) {
                logger.log(ERROR, "failed to transmit invalidateKeys() message", e);
            }
        }

        @Override
        public void invalidateKey(String cacheKey, Object id) {
            try {
                sendMessage(messageInvalidateKey(cacheKey, id));
            } catch (Exception e) {
                logger.log(ERROR, "failed to transmit invalidateKeys() message", e);
            }
        }

        @Override
        public void invalidateClear(String cacheKey) {
            try {
                sendMessage(messageInvalidateClear(cacheKey));
            } catch (Exception e) {
                logger.log(ERROR, "failed to transmit invalidateKeys() message", e);
            }
        }

        private void sendMessage(byte[] message) {
            long nanos = System.nanoTime();
            try {

                if (redisPubSubConnection != null) {
                    RedisPubSubAsyncCommands<byte[], byte[]> commands = redisPubSubConnection.async();

                    commands.publish(CHANNEL_NEAR_BYTES, message);
                }

            } finally {
                metricOutNearCache.addSinceNanos(nanos);
            }
        }

        private byte[] messageInvalidateKeys(String cacheKey, Set<Object> keySet) throws IOException {
            ByteArrayOutputStream ba = new ByteArrayOutputStream(100);
            ObjectOutputStream os = new ObjectOutputStream(ba);
            os.writeUTF(serverId);
            os.writeInt(MSG_NEARCACHE_KEYS);
            os.writeUTF(cacheKey);
            os.writeInt(keySet.size());
            for (Object key : keySet) {
                os.writeObject(key);
            }
            os.flush();
            os.close();
            return ba.toByteArray();
        }

        private byte[] messageInvalidateKey(String cacheKey, Object id) throws IOException {
            ByteArrayOutputStream ba = new ByteArrayOutputStream(100);
            ObjectOutputStream os = new ObjectOutputStream(ba);
            os.writeUTF(serverId);
            os.writeInt(MSG_NEARCACHE_KEY);
            os.writeUTF(cacheKey);
            os.writeObject(id);
            os.flush();
            os.close();
            return ba.toByteArray();
        }

        private byte[] messageInvalidateClear(String cacheKey) throws IOException {
            ByteArrayOutputStream ba = new ByteArrayOutputStream(100);
            ObjectOutputStream os = new ObjectOutputStream(ba);
            os.writeUTF(serverId);
            os.writeInt(MSG_NEARCACHE_CLEAR);
            os.writeUTF(cacheKey);
            os.flush();
            os.close();
            return ba.toByteArray();
        }
    }

    /**
     * Redis channel listener that supports reconnection etc.
     */
    private class CacheDaemonTopic implements DaemonTopic, RedisPubSubListener<byte[], byte[]> {

        @Override
        public void subscribe(StatefulRedisPubSubConnection<byte[], byte[]> pubSubConnection) {

            pubSubConnection.addListener(this);

            RedisPubSubCommands<byte[], byte[]> commands = pubSubConnection.sync();
            commands.subscribe(CHANNEL_L2_BYTES, CHANNEL_NEAR_BYTES);
            logger.log(INFO, "subscribed to Redis");

        }

        @Override
        public void notifyConnected() {
            logger.log(INFO, "notifyConnected");
        }


        private void processNearCacheMessage(byte[] message) {
            long nanos = System.nanoTime();
            int msgType = 0;
            String cacheKey = null;
            try {
                ObjectInputStream oi = new ObjectInputStream(new ByteArrayInputStream(message));
                String sourceServerId = oi.readUTF();
                if (sourceServerId.equals(serverId)) {
                    // ignore this message as we are the server that sent it
                    return;
                }
                msgType = oi.readInt();
                cacheKey = oi.readUTF();
                if (logger.isLoggable(DEBUG)) {
                    logger.log(DEBUG, "processNearCacheMessage serverId:{0} type:{1} cacheKey:{2}", sourceServerId, msgType, cacheKey);
                }

                switch (msgType) {
                    case MSG_NEARCACHE_CLEAR:
                        nearCacheInvalidateClear(cacheKey);
                        break;

                    case MSG_NEARCACHE_KEY:
                        Object key = oi.readObject();
                        nearCacheInvalidateKey(cacheKey, key);
                        break;

                    case MSG_NEARCACHE_KEYS:
                        int count = oi.readInt();
                        Set<Object> keys = new LinkedHashSet<>();
                        for (int i = 0; i < count; i++) {
                            keys.add(oi.readObject());
                        }
                        nearCacheInvalidateKeys(cacheKey, keys);
                        break;

                    default:
                        throw new IllegalStateException("Unexpected message type ? " + msgType);
                }

            } catch (Exception e) {
                logger.log(ERROR, "failed to decode near cache message [" + encodeMessage(message) + "] for cache:" + cacheKey, e);
                if (cacheKey != null) {
                    nearCacheInvalidateClear(cacheKey);
                }
            } finally {
                if (msgType != 0) {
                    metricInNearCache.addSinceNanos(nanos);
                }
            }
        }

        private void processL2Message(String message) {
            try {
                String[] split = message.split(":");
                if (serverId.equals(split[0])) {
                    // ignore this message as we are the server that sent it
                    return;
                }
                switch (split[1]) {
                    case "tableMod":
                        processTableNotify(split[2]);
                        break;
                    case "queryCache":
                        queryCacheInvalidate(split[2]);
                        break;
                    default:
                        logger.log(ERROR, "Unknown L2 message type[{0}] on redis channel - message[{1}] ", split[0], message);
                }
            } catch (Exception e) {
                logger.log(ERROR, "Error handling L2 message[" + message + "]", e);
            }
        }

        @Override
        public void message(byte[] channel, byte[] message) {

            String channelName = encodeMessage(channel);
            if (channelName.equals(CHANNEL_L2)) {
                processL2Message(encodeMessage(message));
            } else {
                processNearCacheMessage(message);
            }

        }

        @Override
        public void message(byte[] pattern, byte[] channel, byte[] message) {

        }

        @Override
        public void subscribed(byte[] channel, long count) {

//                if (logger.isLoggable(DEBUG)) {
//                    logger.log(DEBUG, "subscribed to channel {0}", encodeMessage(channel));
//                }
        }

        @Override
        public void psubscribed(byte[] pattern, long count) {

        }

        @Override
        public void unsubscribed(byte[] channel, long count) {

            if (logger.isLoggable(DEBUG)) {
                logger.log(DEBUG, "unsubscribed from channel {0}", encodeMessage(channel));
            }

        }

        @Override
        public void punsubscribed(byte[] pattern, long count) {

        }
    }

    private static String encodeMessage(byte[] message) {
        return new String(message, StandardCharsets.UTF_8);
    }

    private static byte[] encodeMessage(String message) {
        return message.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Invalidate key for a local near cache.
     */
    private void nearCacheInvalidateKey(String cacheKey, Object key) {
        NearCacheInvalidate invalidate = nearCacheMap.get(cacheKey);
        if (invalidate == null) {
            warnNearCacheNotFound(cacheKey);
        } else {
            invalidate.invalidateKey(key);
        }
    }

    /**
     * Invalidate keys for a local near cache.
     */
    private void nearCacheInvalidateKeys(String cacheKey, Set<Object> keys) {
        NearCacheInvalidate invalidate = nearCacheMap.get(cacheKey);
        if (invalidate == null) {
            warnNearCacheNotFound(cacheKey);
        } else {
            invalidate.invalidateKeys(keys);
        }
    }

    /**
     * Invalidate clear for a local near cache.
     */
    private void nearCacheInvalidateClear(String cacheKey) {
        NearCacheInvalidate invalidate = nearCacheMap.get(cacheKey);
        if (invalidate == null) {
            warnNearCacheNotFound(cacheKey);
        } else {
            invalidate.invalidateClear();
        }
    }

    private void warnNearCacheNotFound(String cacheKey) {
        logger.log(WARNING, "No near cache found for cacheKey [" + cacheKey + "] yet - probably on startup");
    }

}
