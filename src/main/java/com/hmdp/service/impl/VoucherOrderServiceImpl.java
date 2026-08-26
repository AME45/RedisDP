package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.SimpleRedisLock;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.BeanUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

//加载lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("secKill2.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }


    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            String queueName = "streams.order";
            while (true) {
                try {
                    //获取消息队列中的订单信息XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        continue;
                    }
                    //解析消息中的订单消息
                    MapRecord<String, Object, Object> entries = list.get(0);
                    Map<Object, Object> value = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    //ACK确认 SACK stream.order g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",entries.getId());
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    handlePendingList();
                }
            }

        }
    }

    private void handlePendingList() {
        String queueName = "streams.order";
        while (true) {
            try {
                //获取消息队列中的订单信息XREADGROUP GROUP g1 c1 COUNT 1 STREAMS streams.order 0
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(queueName, ReadOffset.from("0"))
                );
                //判断消息获取是否成功
                if (list == null || list.isEmpty()) {
                    break;
                }
                //解析消息中的订单消息
                MapRecord<String, Object, Object> entries = list.get(0);
                Map<Object, Object> value = entries.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                //如果获取成功，可以下单
                handleVoucherOrder(voucherOrder);
                //ACK确认 SACK stream.order g1 id
                stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",entries.getId());
            } catch (Exception e) {
                log.error("处理订单异常",e);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
            }
        }
    }

//    //阻塞队列
//    private BlockingQueue<VoucherOrder> orderTasks = new LinkedBlockingQueue<>(1024 * 1024);
//    private class VoucherOrderHandler implements Runnable {
//
//        @Override
//        public void run() {
//            try {
//                VoucherOrder voucherOrder = orderTasks.take();
//
//                handleVoucherOrder(voucherOrder);
//
//            } catch (Exception e) {
//                log.error("处理订单异常",e);
//            }
//        }
//    }

    private IVoucherOrderService proxy;
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //java内置锁
//        synchronized (userId.toString().intern()){
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }

        //redis锁
//        SimpleRedisLock lock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
//        boolean isLock = lock.trylock(1200);

        //Redisson锁
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        RLock lock = redissonClient.getLock("lock:order" + userId);
        boolean isLock = lock.tryLock();

        if (!isLock) {
            log.error("一人只能下一单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    //原代码隐藏

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1.查询优惠券
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        // 2.判断秒杀是否开始
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始");
//        }
//        // 3.判断秒杀是否结束
//        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束");
//        }
//        //4.判断库存是否充足
//        if (seckillVoucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//
//        Long userId = UserHolder.getUser().getId();
//
//        //java内置锁
////        synchronized (userId.toString().intern()){
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }
//
//        //redis锁
////        SimpleRedisLock lock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
////        boolean isLock = lock.trylock(1200);
//
//        //Redisson锁
//        RLock lock = redissonClient.getLock("lock:order" + userId);
//        boolean isLock = lock.tryLock();
//
//        if (!isLock) {
//            return Result.fail("一人只能下一单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
//
//    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //获取订单id
        long orderId = redisIdWorker.nextId("order");


        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );

        int res = result.intValue();
        if (res != 0) {
            //不为0，库存不足或不能重复下单
            return Result.fail(res == 1 ? "库存不足" : "不能重复下单");
        }
//
//        //为0则执行业务
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
//        //放入阻塞队列
//        orderTasks.add(voucherOrder);

        proxy = (IVoucherOrderService) AopContext.currentProxy();

        return Result.ok();
    }
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //获取用户id
//        Long userId = UserHolder.getUser().getId();
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(),
//                userId.toString()
//        );
//
//        int res = result.intValue();
//        if (res != 0) {
//            //不为0，库存不足或不能重复下单
//            return Result.fail(res == 1 ? "库存不足" : "不能重复下单");
//        }
//
//        //为0则执行业务
//        long orderId = redisIdWorker.nextId("order");
//
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
//        //放入阻塞队列
//        orderTasks.add(voucherOrder);
//
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//
//        return Result.ok();
//    }


    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 一人一单
        long userId = voucherOrder.getId();
        Long voucherId = voucherOrder.getVoucherId();

        int count = query().eq("userId", userId).eq("voucherId", voucherId).count();
        if (count > 0) {
            log.error("重复下单");
            return;
        }
        // 5.扣减库存
        boolean isSuccess = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!isSuccess) {
            log.error("库存不足");
            return;
        }

        //保存
        save(voucherOrder);
    }
}
