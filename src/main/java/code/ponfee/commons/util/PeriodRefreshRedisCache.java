package code.ponfee.commons.util;

import code.ponfee.commons.json.Jsons;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Map;

/**
 * Abstract Period Refresh Redis Cache
 *
 * @author Ponfee
 */
public abstract class PeriodRefreshRedisCache<K, V> {

    protected final RedisTemplate<K, V> redisTemplate;
    protected final long expireTimeoutMillis;
    protected final long renewIntervalMillis;

    protected PeriodRefreshRedisCache(RedisTemplate<K, V> redisTemplate,
                                      long expireTimeoutMillis,
                                      long renewIntervalMillis) {
        this.redisTemplate = redisTemplate;
        this.expireTimeoutMillis = expireTimeoutMillis;
        this.renewIntervalMillis = renewIntervalMillis;
    }

    /**
     * Returns the current value for key
     *
     * @param key the key
     * @return current value
     */
    public abstract V get(K key);

    /**
     * Put a new value and return old value for key
     *
     * @param key   the key
     * @param value the new value
     * @return old value
     */
    public abstract V put(K key, V value);

    /**
     * Remove the key and return value
     *
     * @param key the key
     * @return the value for the removing key
     */
    public abstract V remove(K key);

    /**
     * Refresh newly value and return it
     *
     * @param key the key
     * @return newly value
     */
    public abstract V refresh(K key);

    /**
     * Returns the number of key-value mappings in this cache
     *
     * @return the number of key-value mappings in this cache
     */
    public abstract long size();

    /**
     * Returns {@code true} if this cache contains no key-value mappings
     *
     * @return {@code true} if this cache contains no key-value mappings
     */
    public abstract boolean isEmpty();

    /**
     * Returns {@code true} if this cache contains the key
     *
     * @param key the key
     * @return {@code true} if this cache contains the key
     */
    public abstract boolean containsKey(K key);

    /**
     * Returns the map
     *
     * @return map
     */
    public abstract Map<K, V> asMap();

    @Override
    public String toString() {
        return Jsons.toJson(asMap());
    }

    // ------------------------------------------------------protected methods
    protected final long nextRenewTime(long time) {
        return time + renewIntervalMillis;
    }

    protected final String toString(Object value) {
        return value == null ? "null" : "'" + value + "'";
    }

}
