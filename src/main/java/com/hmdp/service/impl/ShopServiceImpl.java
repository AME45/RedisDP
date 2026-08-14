package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public Result queryById(Long id) {
        //获取shop
        Shop shop = queryWithMutex(id);
        //判空
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    /**
     * 逻辑过期解决缓存击穿
     * @param id
     * @return
     */
    private Shop queryWithLogicalExpire(Long id){
        // 1.从redis查询缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断缓存是否存在
        if (StrUtil.isBlank(shopJson)) {
            // 不存在，直接返回
            return null;
        }
        //命中，需要把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(),Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            //未过期，直接返回店铺信息
            return shop;
        }
        //过期，进行缓存重建
        //缓存重建
        //获取互斥锁
        String localKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(localKey);
        //判断是否获取成功
        if (isLock) {
            shopJson = stringRedisTemplate.opsForValue().get(key);
            // 5.1判断缓存是否存在
            if (StrUtil.isBlank(shopJson)) {
                return null;
            }
            //成功，开启独立线程，进行缓存重建
            try {
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    this.saveShop2Redis(id,20L);});
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                unlock(localKey);
            }
        }
        // 10.返回
        return shop;
    }

    private void saveShop2Redis(Long id, Long expireTime) {
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id ,JSONUtil.toJsonStr(redisData));
    }

    /**
     * 互斥锁解决缓存击穿
     * @param id
     * @return
     */
    private Shop queryWithMutex(Long id){
        // 1.从redis查询缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断缓存是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        // 3.防止缓存穿透：判断命中的缓存是否为空值
        if (shopJson != null) {
            return null;
        }

        // 4.防止缓存击穿：实现缓存重建
        // 4.1获取互斥锁
        String localKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(localKey);
            //判断是否获取成功
            if (!isLock) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            // 5.再次查redis缓存
            shopJson = stringRedisTemplate.opsForValue().get(key);
            // 5.1判断缓存是否存在
            if (StrUtil.isNotBlank(shopJson)) {
                // 存在，直接返回
                shop = JSONUtil.toBean(shopJson, Shop.class);
                return shop;
            }

            // 6.若成功获取互斥锁，且仍然没有缓存则根据id查询数据库
            shop = getById(id);
            // 7.数据库中数据也不存在，返回错误
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 8.数据库中数据存在，写入redis缓存
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 9.释放互斥锁
            unlock(localKey);
        }
        // 10.返回
        return shop;
    }

    private boolean tryLock(String key){
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(b);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }


    /**
     * 更新店铺
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
