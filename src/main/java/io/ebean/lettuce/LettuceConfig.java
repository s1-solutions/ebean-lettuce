package io.ebean.lettuce;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.support.ConnectionPoolSupport;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.time.Duration;
import java.util.Properties;

/**
 * Deployment configuration for redis.
 */
public class LettuceConfig {

    private String server = "localhost";
    private int port = 6379;
    private int maxTotal = 200;
    private int maxIdle = 200;
    private int minIdle = 1;
    private long maxWaitMillis = -1L;
    private boolean blockWhenExhausted = true;
    private int timeout = 2000;
    private String username;
    private String password;
    private boolean ssl;



      public RedisClient createClient() {

        RedisURI uri = RedisURI.builder()
                .withHost(server)
                .withPort(port)
                .withSsl(ssl)
                .withTimeout(Duration.ofMillis(timeout))
                .build();

          ClientResources clientResources = ClientResources.builder()
                          .ioThreadPoolSize(4)
                                  .computationThreadPoolSize(4)
                  .build();

        return RedisClient.create(clientResources,uri);
    }

    public GenericObjectPool<StatefulRedisConnection<byte[], byte[]>> createLettucePool() {
        GenericObjectPoolConfig<StatefulRedisConnection<byte[], byte[]>> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(maxTotal);
        poolConfig.setMaxIdle(maxIdle);
        poolConfig.setMinIdle(minIdle);
        poolConfig.setMaxWait(Duration.ofMillis(maxWaitMillis));
        poolConfig.setBlockWhenExhausted(blockWhenExhausted);

        RedisURI uri = RedisURI.builder()
                .withHost(server)
                .withPort(port)
                .withSsl(ssl)
                .withTimeout(Duration.ofMillis(timeout))
                .withAuthentication(username, password.toCharArray())
                .build();

        RedisClient client = RedisClient.create(uri);

        return ConnectionPoolSupport.createGenericObjectPool(
                () -> client.connect(io.lettuce.core.codec.ByteArrayCodec.INSTANCE),
                poolConfig);
    }

    public String getServer() {
        return server;
    }

    public void setServer(String server) {
        this.server = server;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getMaxTotal() {
        return maxTotal;
    }

    public void setMaxTotal(int maxTotal) {
        this.maxTotal = maxTotal;
    }

    public int getMaxIdle() {
        return maxIdle;
    }

    public void setMaxIdle(int maxIdle) {
        this.maxIdle = maxIdle;
    }

    public int getMinIdle() {
        return minIdle;
    }

    public void setMinIdle(int minIdle) {
        this.minIdle = minIdle;
    }

    public long getMaxWaitMillis() {
        return maxWaitMillis;
    }

    public void setMaxWaitMillis(long maxWaitMillis) {
        this.maxWaitMillis = maxWaitMillis;
    }

    public boolean isBlockWhenExhausted() {
        return blockWhenExhausted;
    }

    public void setBlockWhenExhausted(boolean blockWhenExhausted) {
        this.blockWhenExhausted = blockWhenExhausted;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isSsl() {
        return ssl;
    }

    public void setSsl(boolean ssl) {
        this.ssl = ssl;
    }

    public void loadProperties(Properties properties) {
        Reader reader = new Reader(properties);
        this.server = reader.get("ebean.lettuce.server", server);
        this.port = reader.getInt("ebean.lettuce.port", port);
        this.ssl = reader.getBool("ebean.lettuce.ssl", ssl);
        this.minIdle = reader.getInt("ebean.lettuce.minIdle", minIdle);
        this.maxIdle = reader.getInt("ebean.lettuce.maxIdle", maxIdle);
        this.maxTotal = reader.getInt("ebean.lettuce.maxTotal", maxTotal);
        this.maxWaitMillis = reader.getLong("ebean.lettuce.maxWaitMillis", maxWaitMillis);
        this.timeout = reader.getInt("ebean.lettuce.timeout", timeout);
        this.username = reader.get("ebean.lettuce.username", username);
        this.password = reader.get("ebean.lettuce.password", password);
        this.blockWhenExhausted = reader.getBool("ebean.lettuce.blockWhenExhausted", blockWhenExhausted);
    }

    private static class Reader {

        private final Properties properties;

        Reader(Properties properties) {
            this.properties = (properties != null) ? properties : new Properties();
        }

        String get(String key, String defaultVal) {
            return System.getProperty(key, properties.getProperty(key, defaultVal));
        }

        int getInt(String key, int defaultVal) {
            final String val = get(key, null);
            return val != null ? Integer.parseInt(val.trim()) : defaultVal;
        }

        long getLong(String key, long defaultVal) {
            final String val = get(key, null);
            return val != null ? Integer.parseInt(val.trim()) : defaultVal;
        }

        boolean getBool(String key, boolean defaultVal) {
            final String val = get(key, null);
            return val != null ? Boolean.parseBoolean(val.trim()) : defaultVal;
        }
    }

}
