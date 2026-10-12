package org.sunbird.search.util;

import org.sunbird.common.Platform;
import org.sunbird.telemetry.logger.TelemetryManager;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SearchCache {

    private static final boolean ENABLE = Platform.config.hasPath(SearchConstants.SEARCH_REDIS_ENABLE) && Platform.config.getBoolean(SearchConstants.SEARCH_REDIS_ENABLE);
    private static final String HOST = Platform.config.hasPath(SearchConstants.SEARCH_REDIS_HOST) ? Platform.config.getString(SearchConstants.SEARCH_REDIS_HOST) : SearchConstants.SEARCH_REDIS_HOST_DEFAULT;
    private static final int PORT = Platform.config.hasPath(SearchConstants.SEARCH_REDIS_PORT) ? Platform.config.getInt(SearchConstants.SEARCH_REDIS_PORT) : SearchConstants.SEARCH_REDIS_PORT_DEFAULT;
    private static final int DB_INDEX = Platform.config.hasPath(SearchConstants.SEARCH_REDIS_DB_INDEX) ? Platform.config.getInt(SearchConstants.SEARCH_REDIS_DB_INDEX) : SearchConstants.SEARCH_REDIS_DB_INDEX_DEFAULT;
    private static final int TTL = Platform.config.hasPath(SearchConstants.SEARCH_REDIS_TTL) ? Platform.config.getInt(SearchConstants.SEARCH_REDIS_TTL) : SearchConstants.SEARCH_REDIS_TTL_DEFAULT;
    private static final int MAX_CACHEABLE_LIMIT = Platform.config.hasPath(SearchConstants.SEARCH_REDIS_MAX_CACHEABLE_LIMIT) ? Platform.config.getInt(SearchConstants.SEARCH_REDIS_MAX_CACHEABLE_LIMIT) : SearchConstants.SEARCH_REDIS_MAX_CACHEABLE_LIMIT_DEFAULT;
    private static final JedisPool jedisPool = ENABLE ? new JedisPool(getPoolConfig(), HOST, PORT) : null;
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    public static boolean isEnable() {
        return ENABLE;
    }

    public static boolean isCacheable(int limit) {
        return limit <= MAX_CACHEABLE_LIMIT;
    }

    public static String get(String key) {
        Jedis jedis = null;
        try {
            jedis = getConnection();
            return jedis.get(key);
        } catch (Exception e) {
            TelemetryManager.error("Error while fetching search cache for key: " + key, e);
            return null;
        } finally {
            returnConnection(jedis);
        }
    }

    public static void setAsync(String key, String value) {
        executor.submit(() -> {
            Jedis jedis = null;
            try {
                jedis = getConnection();
                jedis.setex(key, TTL, value);
            } catch (Exception e) {
                TelemetryManager.error("Error while saving search cache for key: " + key, e);
            } finally {
                returnConnection(jedis);
            }
        });
    }

    // Bigger pool and a short wait, so an exhausted pool fails fast (request falls back to ES) instead of blocking request threads.
    private static JedisPoolConfig getPoolConfig() {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(SearchConstants.SEARCH_REDIS_POOL_MAX_TOTAL);
        config.setMaxWaitMillis(SearchConstants.SEARCH_REDIS_POOL_MAX_WAIT_MS);
        return config;
    }

    private static Jedis getConnection() {
        Jedis jedis = jedisPool.getResource();
        if (DB_INDEX > 0) jedis.select(DB_INDEX);
        return jedis;
    }

    private static void returnConnection(Jedis jedis) {
        if (jedis != null) jedisPool.returnResource(jedis);
    }
}
