package code.ponfee.commons.util;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Map;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Period Refresh Redis Guava Cache
 *
 * @author Ponfee
 */
public class PeriodRefreshRedisGuavaCache<K, V> extends PeriodRefreshRedisCache<K, V> {

    private static final Logger LOG = LoggerFactory.getLogger(PeriodRefreshRedisGuavaCache.class);

    private final LoadingCache<K, Value<V>> cache;

    public PeriodRefreshRedisGuavaCache(RedisTemplate<K, V> redisTemplate, long refreshIntervalMillis) {
        this(redisTemplate, DAYS.toMillis(30), DAYS.toMillis(1), refreshIntervalMillis);
    }

    public PeriodRefreshRedisGuavaCache(RedisTemplate<K, V> redisTemplate, long expireTimeoutMillis,
                                        long renewIntervalMillis, long refreshIntervalMillis) {
        super(redisTemplate, expireTimeoutMillis, renewIntervalMillis);
        this.cache = CacheBuilder.newBuilder()
            .refreshAfterWrite(refreshIntervalMillis, MILLISECONDS)
            .build(new CacheLoader<K, Value<V>>() {
                @Override
                public Value<V> load(K key) {
                    V val = null;
                    try {
                        val = redisTemplate.opsForValue().get(key);
                        LOG.info("{} init {}", key, PeriodRefreshRedisGuavaCache.this.toString(val));
                        } catch (Throwable t) {
                            LOG.error("{} load occur error", key, t);
                    }
                    return new Value<>(val);
                }

                @Override
                public ListenableFuture<Value<V>> reload(K key, Value<V> value) {
                    requireNonNull(key);
                    requireNonNull(value);
                    try {
                        V val = redisTemplate.opsForValue().get(key);
                        value.current = val;
                        LOG.debug("{} refresh {}", key, PeriodRefreshRedisGuavaCache.this.toString(val));

                        // renew if necessary
                        long time = System.currentTimeMillis();
                        if (time > value.nextRenewTime && (val != null || redisTemplate.hasKey(key))) {
                            // key存在则续期(设置失效时间)
                            redisTemplate.expire(key, expireTimeoutMillis, MILLISECONDS);
                            value.nextRenewTime = nextRenewTime(time);
                            LOG.info("{} renew", key);
                        }
                        } catch (Throwable t) {
                            LOG.error("{} reload occur error", key, t);
                    }
                    return Futures.immediateFuture(value);
                }
            });
    }

    @Override
    public V get(K key) {
        return cache.getUnchecked(key).current;
    }

    @Override
    public V put(K key, V value) {
        redisTemplate.opsForValue().set(key, value, expireTimeoutMillis, MILLISECONDS);
        Value<V> wrapper = cache.getUnchecked(key);
        V old = wrapper.current;
        wrapper.current = value;
        LOG.info("{} update {}", key, toString(value));
        return old;
    }

    @Override
    public V remove(K key) {
        V old = get(key);
        redisTemplate.delete(key);
        cache.invalidate(key);
        LOG.info("{} delete", key);
        return old;
    }

    @Override
    public V refresh(K key) {
        cache.refresh(key);
        return get(key);
    }

    @Override
    public long size() {
        return cache.size();
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public boolean containsKey(K key) {
        return get(key) != null;
    }

    @Override
    public Map<K, V> asMap() {
        return cache.asMap()
            .entrySet()
            .stream()
            .filter(e -> e.getValue() != null && e.getValue().current != null)
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().current));
    }

    private static class Value<V> {
        private volatile V current;
        private volatile long nextRenewTime = 0L;

        public Value(V initValue) {
            this.current = initValue;
        }
    }

}
