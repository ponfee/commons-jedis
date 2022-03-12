package code.ponfee.commons.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.DAYS;

/**
 * Period Refresh Redis Custom Cache
 *
 * @author Ponfee
 */
public class PeriodRefreshRedisCustomCache<K, V> extends PeriodRefreshRedisCache<K, V> {

    private static final Logger LOG = LoggerFactory.getLogger(PeriodRefreshRedisCustomCache.class);

    private final Operator operator;

    public PeriodRefreshRedisCustomCache(RedisTemplate<K, V> redisTemplate, long refreshIntervalMillis) {
        this(redisTemplate, DAYS.toMillis(30), DAYS.toMillis(1), refreshIntervalMillis);
    }

    public PeriodRefreshRedisCustomCache(RedisTemplate<K, V> redisTemplate, long expireTimeoutMillis,
                                         long renewIntervalMillis, long refreshIntervalMillis) {
        super(redisTemplate, expireTimeoutMillis, renewIntervalMillis);
        this.operator = new Operator(refreshIntervalMillis);
    }

    @Override
    public V get(K key) {
        return operator.get(key);
    }

    @Override
    public V put(K key, V value) {
        return operator.update(key, value);
    }

    @Override
    public V remove(K key) {
        return operator.delete(key);
    }

    @Override
    public V refresh(K key) {
        return operator.refresh(key);
    }

    @Override
    public long size() {
        return operator.cache.size();
    }

    @Override
    public boolean isEmpty() {
        return operator.cache.isEmpty();
    }

    @Override
    public boolean containsKey(K key) {
        return operator.cache.containsKey(key);
    }

    @Override
    public Map<K, V> asMap() {
        return operator.cache
            .entrySet()
            .stream()
            .filter(e -> e.getValue() != null & e.getValue().current != null)
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().current));
    }

    /**
     * Wrapped value
     *
     * @param <V> the value type
     */
    private static class Value<V> {
        private final Lock locker = new ReentrantLock();
        private volatile V current = null;
        private volatile long nextRefreshTime = 0L;
        private volatile long nextRenewTime = 0L;
    }

    private class Operator {

        private final Map<K, Value<V>> cache = new HashMap<>();

        /**
         * 刷新时间间隔(毫秒)
         */
        private final long refreshIntervalMillis;

        public Operator(long refreshIntervalMillis) {
            this.refreshIntervalMillis = Math.max(refreshIntervalMillis, 1000);
        }

        public V get(K key) {
            long now = System.currentTimeMillis();
            Value<V> value = value(key, now);
            if (now > value.nextRefreshTime && value.locker.tryLock()) {
                try {
                    refresh(key, value, now);
                } catch (Throwable t) {
                    LOG.error("{} get occur error", key, t);
                } finally {
                    value.locker.unlock();
                }
            }

            return value.current;
        }

        public V refresh(K key) {
            Value<V> value = value(key);
            value.locker.lock();
            try {
                refresh(key, value, System.currentTimeMillis());
            } finally {
                value.locker.unlock();
            }
            return value.current;
        }

        public V update(K key, V val) {
            Value<V> value = value(key);
            V old = value.current;
            value.locker.lock();
            try {
                redisTemplate.opsForValue().set(key, val, expireTimeoutMillis, TimeUnit.MILLISECONDS);
                value.current = val;
                LOG.info("{} update {}", key, PeriodRefreshRedisCustomCache.this.toString(val));
            } finally {
                value.locker.unlock();
            }
            return old;
        }

        public V delete(K key) {
            Value<V> value = value(key);
            V old = value.current;
            value.locker.lock();
            try {
                redisTemplate.delete(key);
                cache.remove(key);
                LOG.info("{} delete", key);
            } finally {
                value.locker.unlock();
            }
            return old;
        }

        // ---------------------------------------------------------------------private methods
        private void refresh(K key, Value<V> value, long time) {
            // refresh to local cache
            V val = redisTemplate.opsForValue().get(key);
            value.current = val;
            value.nextRefreshTime = nextRefreshTime(time);
            LOG.debug("{} refresh {}", key, PeriodRefreshRedisCustomCache.this.toString(val));

            // renew if necessary
            if (time > value.nextRenewTime && (val != null || redisTemplate.hasKey(key))) {
                // key存在则续期(设置失效时间)
                redisTemplate.expire(key, expireTimeoutMillis, TimeUnit.MILLISECONDS);
                value.nextRenewTime = nextRenewTime(time);
                LOG.info("{} renew", key);
            }
        }

        private Value<V> value(K key) {
            return value(key, System.currentTimeMillis());
        }

        private Value<V> value(K key, long time) {
            Value<V> value;
            if ((value = cache.get(key)) == null) {
                synchronized (cache) {
                    if ((value = cache.get(key)) == null) {
                        refresh(key, value = new Value<>(), time);
                        cache.put(key, value);
                        LOG.info("{} init {}", key, PeriodRefreshRedisCustomCache.this.toString(value.current));
                    }
                }
            }
            return value;
        }

        private long nextRefreshTime(long time) {
            // 加上一个刷新浮动时间(降低同时请求redis的概率)
            return time + refreshIntervalMillis + ThreadLocalRandom.current().nextInt(1000);
        }
    }

}
