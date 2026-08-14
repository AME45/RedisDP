package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,timeUnit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public  <R,ID>R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, String mutexKeyPrefix, Function<ID,R> dbFallback, Long time, TimeUnit timeUnit){
        // 1.从redis查询缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断缓存是否存在
        if (StrUtil.isNotBlank(json)) {
            // 存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        // 3.防止缓存穿透：判断命中的缓存是否为空值
        if (json != null) {
            return null;
        }

        // 4.防止缓存击穿：实现缓存重建
        // 4.1获取互斥锁
        String localKey = mutexKeyPrefix + id;
        R r = null;
        try {
            boolean isLock = tryLock(localKey);
            //判断是否获取成功
            if (!isLock) {
                Thread.sleep(50);
                return queryWithPassThrough(keyPrefix,id,type,mutexKeyPrefix,dbFallback,time,timeUnit);
            }

            // 5.再次查redis缓存
            json = stringRedisTemplate.opsForValue().get(key);
            // 5.1判断缓存是否存在
            if (StrUtil.isNotBlank(json)) {
                // 存在，直接返回
                r = JSONUtil.toBean(json, type);
                return r;
            }

            // 6.若成功获取互斥锁，且仍然没有缓存则根据id查询数据库
            r = dbFallback.apply(id);
            // 7.数据库中数据也不存在，返回错误
            if (r == null) {
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 8.数据库中数据存在，写入redis缓存
            this.set(key,r,time,timeUnit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 9.释放互斥锁
            unlock(localKey);
        }
        // 10.返回
        return r;
    }

    private boolean tryLock(String key){
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(b);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}
