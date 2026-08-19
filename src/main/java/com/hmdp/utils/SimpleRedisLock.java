package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;


import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static{
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }



    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }



    @Override
    public boolean trylock(long timeoutSec) {
        String value = ID_PREFIX + Thread.currentThread().getId();
        String lockKey = KEY_PREFIX + name;
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, value, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }



    @Override
    public void unlock() {
        String lockKey = KEY_PREFIX + name;
//        //获取当前线程id
        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        //从redis中查锁的线程id
//        String threadId2 = stringRedisTemplate.opsForValue().get(lockKey);
//        //判断线程id是否一致来判断该锁是否属于当前线程，若是，则可以释放锁
//        if (threadId.equals(threadId2)){
//            stringRedisTemplate.delete(lockKey);

        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(lockKey),
                threadId
                );

    }
}
