package code.ponfee.commons.jedis;

import code.ponfee.commons.util.Dates;
import code.ponfee.commons.util.PeriodRefreshRedisCustomCache;
import code.ponfee.commons.util.PeriodRefreshRedisGuavaCache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.apache.commons.lang3.ObjectUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import javax.annotation.Resource;
import java.util.Date;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = {"classpath:spring-sentinel-redis.xml"})
public class RedisTemplateTest {

    @Resource
    private RedisTemplate<String, String> redisTemplate;

    @Before
    public void setup() {
    }

    @After
    public void teardown() {
    }

    @Test
    public void test1() {
        action(new PeriodRefreshRedisCustomCache<>(redisTemplate, 10000)::get);
    }

    @Test
    public void test2() {
        action(new PeriodRefreshRedisGuavaCache<>(redisTemplate, 10000)::get);
    }

    @Test
    public void test3() {
        LoadingCache<String, String> cache = CacheBuilder.newBuilder()
                .refreshAfterWrite(10, TimeUnit.SECONDS)
                .build(new CacheLoader<String, String>() {
                    @Override
                    public String load(String key) {
                        System.out.println("\n\n" + Thread.currentThread().getId() + "  " + Dates.format(new Date(), "HH:mm:ss") + "------------------------ load start");
                        String val = null;
                        try {
                            val = redisTemplate.opsForValue().get(key);
                            //redisTemplate.expire(key, 365L, TimeUnit.DAYS);
                            Thread.sleep(3000);
                        } catch (Exception e) {
                            //e.printStackTrace();
                        }
                        System.out.println(Thread.currentThread().getId() + "  " + Dates.format(new Date(), "HH:mm:ss") + "------------------------ load end\n\n");
                        return val == null ? "x" : val;
                    }

                    @Override
                    public ListenableFuture<String> reload(String key, String oldValue) throws Exception {
                        System.out.println("==========reload");
                        checkNotNull(key);
                        checkNotNull(oldValue);

                        String val = null;
                        try {
                            if (ThreadLocalRandom.current().nextInt(100) < 40) {
                                System.err.println("-----------------------error");
                                int i = 1/0;
                            }
                            val = redisTemplate.opsForValue().get(key);
                        } catch (Exception e) {
                            //e.printStackTrace();
                        }
                        return Futures.immediateFuture(ObjectUtils.defaultIfNull(val, "x"));
                    }
                });

        cache.refresh("abc");
        cache.refresh("abc");
        System.out.println("-----get start");
        cache.getUnchecked("abc");
        System.out.println("-----get end");
        cache.refresh("abc");
        cache.refresh("abc");

        System.out.println("\n\n--------\n\n");
        action(cache::getUnchecked);
    }

    private void action(Function<String, String> mapper) {
        String key = "test:key";
        AtomicBoolean flag = new AtomicBoolean(true);
        Thread[] threads = new Thread[2];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                while (flag.get()) {
                    try {
                        Thread.sleep(200 + ThreadLocalRandom.current().nextInt(2000));
                        String val = mapper.apply(key);
                        System.out.println(Thread.currentThread().getId() + "  " + Dates.format(new Date(), "HH:mm:ss") + "  [" + val + "]");
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
        }
        try {
            for (Thread thread : threads) {
                thread.start();
            }
            Thread.sleep(1000000);
            flag.set(false);
            for (Thread thread : threads) {
                thread.join();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
