package io.ebean.lettuce;

import io.avaje.applog.AppLog;
import io.ebean.cache.ServerCache;
import io.ebean.cache.ServerCacheConfig;
import io.ebean.cache.ServerCacheOptions;
import io.ebean.cache.ServerCacheStatistics;
import io.ebean.meta.MetricVisitor;
import io.ebean.metric.CountMetric;
import io.ebean.metric.MetricFactory;
import io.ebean.metric.TimedMetric;
import io.ebean.metric.TimedMetricStats;
import io.ebean.lettuce.encode.Encode;
import io.ebean.lettuce.encode.EncodePrefixKey;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.*;

import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;

final class LettuceCache implements ServerCache {

    private static final System.Logger log = AppLog.getLogger(LettuceCache.class);

    private final StatefulRedisConnection<byte[], byte[]> connection;
    private final String cacheKey;
    private final EncodePrefixKey keyEncode;
    private final Encode valueEncode;
    private final SetArgs expiration;
    private final TimedMetric metricGet;
    private final TimedMetric metricGetAll;
    private final TimedMetric metricPut;
    private final TimedMetric metricPutAll;
    private final TimedMetric metricRemove;
    private final TimedMetric metricRemoveAll;
    private final TimedMetric metricClear;
    private final CountMetric hitCount;
    private final CountMetric missCount;

    public LettuceCache(StatefulRedisConnection<byte[], byte[]> connection, ServerCacheConfig config, Encode valueEncode) {
        this.connection = connection;
        this.cacheKey = config.getCacheKey();
        this.keyEncode = new EncodePrefixKey(config.getCacheKey());
        this.valueEncode = valueEncode;
        this.expiration = expiration(config);
        String namePrefix = "l2r." + config.getShortName();
        MetricFactory factory = MetricFactory.get();
        hitCount = factory.createCountMetric(namePrefix + ".hit");
        missCount = factory.createCountMetric(namePrefix + ".miss");
        metricGet = factory.createTimedMetric(namePrefix + ".get");
        metricGetAll = factory.createTimedMetric(namePrefix + ".getMany");
        metricPut = factory.createTimedMetric(namePrefix + ".put");
        metricPutAll = factory.createTimedMetric(namePrefix + ".putMany");
        metricRemove = factory.createTimedMetric(namePrefix + ".remove");
        metricRemoveAll = factory.createTimedMetric(namePrefix + ".removeMany");
        metricClear = factory.createTimedMetric(namePrefix + ".clear");
    }

    private SetArgs expiration(ServerCacheConfig config) {
        final ServerCacheOptions cacheOptions = config.getCacheOptions();
        if (cacheOptions != null) {
            final int maxSecsToLive = cacheOptions.getMaxSecsToLive();
            if (maxSecsToLive > 0) {
                return SetArgs.Builder.ex(maxSecsToLive);
            }
        }
        return null;
    }

    @Override
    public void visit(MetricVisitor visitor) {
        hitCount.visit(visitor);
        missCount.visit(visitor);
        metricGet.visit(visitor);
        metricGetAll.visit(visitor);
        metricPut.visit(visitor);
        metricPutAll.visit(visitor);
        metricRemove.visit(visitor);
        metricRemoveAll.visit(visitor);
        metricClear.visit(visitor);
    }

    private byte[] key(Object id) {
        return keyEncode.encode(id);
    }

    private byte[] value(Object data) {
        if (data == null) {
            return null;
        }
        return valueEncode.encode(data);
    }

    private Object valueDecode(byte[] data) {
        try {
            if (data == null) {
                return null;
            }
            return valueEncode.decode(data);
        } catch (Exception e) {
            log.log(ERROR, "Error decoding data, treated as cache miss", e);
            return null;
        }
    }

    private void errorOnRead(Exception e) {
        log.log(WARNING, "Error when reading redis cache", e);
    }

    private void errorOnWrite(Exception e) {
        log.log(WARNING, "Error when writing redis cache", e);
    }

    @Override
    public Map<Object, Object> getAll(Set<Object> keys) {
        if (keys.isEmpty()) {
            return Collections.emptyMap();
        }
        long start = System.nanoTime();
        Map<Object, Object> map = new LinkedHashMap<>();
        List<Object> keyList = new ArrayList<>(keys);

        try {
            RedisCommands<byte[], byte[]> commands = connection.sync();

            byte[][] keyBytes = keysAsBytes(keyList);

            List<KeyValue<byte[], byte[]>> valsAsBytes = commands.mget(keyBytes);

            for (int i = 0; i < keyList.size(); i++) {

                KeyValue<byte[], byte[]> value = valsAsBytes.get(i);

                if (value.hasValue()) {
                    Object val = valueDecode(value.getValue());
                    map.put(keyList.get(i), val);
                }
            }

            int hits = map.size();
            int miss = keys.size() - hits;
            if (hits > 0) {
                hitCount.add(hits);
            }
            if (miss > 0) {
                missCount.add(miss);
            }
            metricGetAll.addSinceNanos(start);
            return map;
        } catch (Exception e) {
            errorOnRead(e);
            return Collections.emptyMap();
        }
    }

    @Override
    public Object get(Object id) {
        long start = System.nanoTime();
        try {
            RedisCommands<byte[], byte[]> commands = connection.sync();
            Object val = valueDecode(commands.get(key(id)));
            if (val != null) {
                hitCount.increment();
            } else {
                missCount.increment();
            }
            metricGet.addSinceNanos(start);
            return val;
        } catch (Exception e) {
            errorOnRead(e);
            return null;
        }
    }

    @Override
    public void put(Object id, Object value) {
        long start = System.nanoTime();
        try {
            RedisCommands<byte[], byte[]> commands = connection.sync();
            if (expiration == null) {
                commands.set(key(id), value(value));
            } else {
                commands.set(key(id), value(value), expiration);
            }
            metricPut.addSinceNanos(start);
        } catch (Exception e) {
            errorOnWrite(e);
        }
    }

    @Override
    public void putAll(Map<Object, Object> keyValues) {
        long start = System.nanoTime();
        try {
            RedisCommands<byte[], byte[]> commands = connection.sync();
            commands.multi();
            try {
                for (Map.Entry<Object, Object> entry : keyValues.entrySet()) {
                    if (expiration == null) {
                        commands.set(key(entry.getKey()), value(entry.getValue()));
                    } else {
                        commands.set(key(entry.getKey()), value(entry.getValue()), expiration);
                    }
                }
                commands.exec();
            } catch (Exception e) {
                commands.discard();
                throw e;
            }
            metricPutAll.addSinceNanos(start);
        } catch (Exception e) {
            errorOnWrite(e);
        }
    }

    @Override
    public void remove(Object id) {
        long start = System.nanoTime();
        try {
            RedisCommands<byte[], byte[]> commands = connection.sync();
            commands.del(key(id));
            metricRemove.addSinceNanos(start);
        } catch (Exception e) {
            errorOnWrite(e);
        }
    }

    @Override
    public void removeAll(Set<Object> keys) {
        long start = System.nanoTime();
        try {
            RedisCommands<byte[], byte[]> commands = connection.sync();
            byte[][] keysArray = keysAsBytes(keys);
            commands.del(keysArray);
            metricRemoveAll.addSinceNanos(start);
        } catch (Exception e) {
            errorOnWrite(e);
        }
    }

    @Override
    public void clear() {
        long start = System.nanoTime();
        try {
            RedisCommands<byte[], byte[]> commands = connection.sync();
            ScanArgs scanArgs = ScanArgs.Builder.matches(cacheKey + ":*");
            ScanCursor cursor = ScanCursor.INITIAL;

            while (!cursor.isFinished()) {
                KeyScanCursor<byte[]> scanCursor = commands.scan(cursor, scanArgs);
                cursor = scanCursor;
                List<byte[]> keys = scanCursor.getKeys();

                if (!keys.isEmpty()) {
                    commands.del(keys.toArray(new byte[0][]));
                }
            }
            metricClear.addSinceNanos(start);
        } catch (Exception e) {
            errorOnWrite(e);
        }
    }

    private byte[][] keysAsBytes(Collection<Object> keys) {
        byte[][] raw = new byte[keys.size()][];
        int pos = 0;
        for (Object id : keys) {
            raw[pos++] = key(id);
        }
        return raw;
    }

    /**
     * Return the count of get hits.
     */
    public long getHitCount() {
        return hitCount.get(false);
    }

    /**
     * Return the count of get misses.
     */
    public long getMissCount() {
        return missCount.get(false);
    }

    @Override
    public ServerCacheStatistics statistics(boolean reset) {
        ServerCacheStatistics cacheStats = new ServerCacheStatistics();
        cacheStats.setCacheName(cacheKey);
        cacheStats.setHitCount(hitCount.get(reset));
        cacheStats.setMissCount(missCount.get(reset));
        cacheStats.setPutCount(count(metricPut.collect(reset)));
        cacheStats.setRemoveCount(count(metricRemove.collect(reset)));
        cacheStats.setClearCount(count(metricClear.collect(reset)));
        return cacheStats;
    }

    private long count(TimedMetricStats stats) {
        return stats == null ? 0 : stats.count();
    }
}
