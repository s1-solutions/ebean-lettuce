# ebean-lettuce

An Ebean L2 cache implementation using Redis via the Lettuce client.

## Overview

This library provides a Redis-based L2 cache implementation for the Ebean ORM using the high-performance Lettuce Redis client. It supports distributed caching in clustered environments with features like:

- Near cache implementation for improved read performance
- L2 cache invalidation through Redis pub/sub
- Query cache invalidation
- Table modification notifications

## Requirements

- Java 21 or higher
- Ebean 15.11.0 or higher
- Redis server

## Installation

Add the dependency to your Maven project:

```xml
<dependency>
    <groupId>io.sinistral</groupId>
    <artifactId>ebean-lettuce</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Configuration

### Basic Configuration

Add Redis configuration to your `application.yaml` or `application.properties`:

```yaml
ebean:
  lettuce:
    server: localhost
    port: 6379
    # Optional settings
    # password: yourpassword
    # database: 0
    # timeout: 60
```

### Advanced Configuration

For more advanced configuration, create a `LettuceConfig` bean and register it with Ebean:

```java
LettuceConfig config = new LettuceConfig();
config.setServer("redis.example.com");
config.setPort(6379);

DatabaseConfig dbConfig = new DatabaseConfig();
dbConfig.setService(config);
```

Or provide your own Redis client instance:

```java
RedisClient redisClient = RedisClient.create("redis://localhost:6379");

DatabaseConfig dbConfig = new DatabaseConfig();
dbConfig.setService(redisClient);
```

## Features

### Near Cache

The library implements a near cache pattern to improve read performance for frequently accessed data. Near cache invalidation is handled automatically through Redis pub/sub.

### Cache Metrics

Metrics are collected for:
- Query cache operations
- Table modification events
- Near cache operations

### Logging

Configure logging levels for detailed insight:

```xml
<logger name="io.ebean.cache" level="TRACE"/>
<logger name="io.ebean.cache.QUERY" level="TRACE"/>
<logger name="io.ebean.cache.BEAN" level="TRACE"/>
<logger name="io.ebean.cache.COLL" level="TRACE"/>
<logger name="io.ebean.cache.NATKEY" level="TRACE"/>
```

