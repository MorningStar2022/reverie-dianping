package com.whurs.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.whurs.dto.Result;
import com.whurs.entity.SeckillVoucher;
import com.whurs.entity.Voucher;
import com.whurs.entity.VoucherOrder;
import com.whurs.mapper.VoucherOrderMapper;
import com.whurs.service.ISeckillVoucherService;
import com.whurs.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.whurs.service.IVoucherService;
import com.whurs.utils.RedisConnectionCheck;
import com.whurs.utils.RedisIdWorker;
import com.whurs.utils.SimpleRedisLock;
import com.whurs.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
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
import java.util.concurrent.*;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RedisConnectionCheck redisConnectionCheck;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT=new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private static final ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();
    private IVoucherOrderService proxy;
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable{
        private String queueName="stream.orders";
        @Override
        public void run() {
            while(true){
                try {
                    if(!redisConnectionCheck.isRedisAvailable()){
                        break;
                    }
                    //获取消息队列中的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed()));
                    //判断获取是否成功
                    if(list==null||list.isEmpty()){
                        //获取失败，继续下一次循环
                        continue;
                    }
                    //解析订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //创建订单
                    handleVoucherOrder(voucherOrder);
                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.info("处理订单异常"+e);
                    handlePendingList();
                }
            }
        }
        /*private void handlePendingList() {
            while(true){
                try {
                    //获取pendinglist队列中的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0")));
                    //判断获取是否成功
                    if(list==null||list.isEmpty()){
                        //没有异常消息，结束循环
                        break;
                    }
                    //解析订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    log.info("订单信息:"+value.toString());
                    //创建订单
                    handleVoucherOrder(voucherOrder);
                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.info("处理pendingList异常"+e);
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }*/

        //修改版
        private void handlePendingList() {
            while (true) {
                try {
                    // 获取pending-list中的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0")));

                    // 判断是否获取到消息
                    if (list == null || list.isEmpty()) {
                        log.info("pendingList已处理完毕");
                        break;
                    }

                    // 解析订单记录
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    log.info("订单信息: {}", value);

                    // 判断消息体是否为空
                    if (value == null || value.isEmpty()) {
                        log.warn("读取到空订单记录，主动ACK并跳过，消息ID: {}", record.getId());
                        stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                        continue;
                    }

                    // 尝试映射对象
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    if (voucherOrder.getUserId() == null || voucherOrder.getVoucherId() == null) {
                        log.warn("订单字段缺失，无法处理，ACK后跳过，消息ID: {}, 内容: {}", record.getId(), value);
                        stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                        continue;
                    }

                    // 正常处理订单
                    handleVoucherOrder(voucherOrder);

                    // ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());

                } catch (Exception e) {
                    log.error("处理pendingList异常", e);
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        log.warn("handlePendingList 被中断");
                        break;
                    }
                }
            }
        }

    }



    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 尝试获取锁
        boolean isLock = lock.tryLock();
        if(!isLock){
            //获取锁失败
            log.info("不允许重复下单");
            return;
        }
        try {
            //无重复订单，则创建订单
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //释放锁
            lock.unlock();
        }
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 5.一人一单
        // 5.1用户id
        //可以不用判断，lua脚本中已经判断
        Long userId = voucherOrder.getUserId();
        Long voucherId= voucherOrder.getVoucherId();
        // 5.2根据用户id和优惠券id查询是否有订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if(count>0){
            log.info("用户已下过单");
            return;
        }

        // 6.扣减库存
        boolean success = seckillVoucherService.update().setSql("stock=stock-1")//set stock=stock-1
                .eq("voucher_id", voucherId)//where voucher_id=voucherId
                .gt("stock",0)//and stock>0,提高乐观锁的成功率
                .update();
        if(!success){
            //扣减失败
            log.info("库存不足");
            return;
        }
        // 8. 插入订单记录
        save(voucherOrder);
    }

     //优惠券秒杀下单（异步）
    //测试同步下单与异步下单改进前后的响应时间与吞吐量
    @Override
    public Result seckillVoucher(Long voucherId) {
        if (redisConnectionCheck.isRedisAvailable()) {
            //TODO 似乎没判断秒杀时间是否满足条件
            //获取用户
            Long userId = UserHolder.getUser().getId();
            long orderId = redisIdWorker.nextId("order");
            // 执行lua脚本
            Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),
                    voucherId.toString(), userId.toString(),String.valueOf(orderId));
            // 判断结果是否为0
            int r = result.intValue();
            log.info("r={}",r);
            // 不为0，说明没有购买资格
            if(r!=0){
                return Result.fail(r==1?"库存不足":"不能重复下单");
            }
            proxy = (IVoucherOrderService) AopContext.currentProxy();
            return Result.ok(orderId);
        }
        return Result.ok();
    }

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        // 执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),
                voucherId.toString(), userId.toString());
        // 判断结果是否为0
        int r = result.intValue();
        // 不为0，说明没有购买资格
        if(r!=0){
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }
        // 为0，把下单信息保存到阻塞队列
        // 创建订单
        VoucherOrder voucherOrder=new VoucherOrder();
        // 订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 用户id
        voucherOrder.setUserId(userId);
        // 优惠券id
        voucherOrder.setVoucherId(voucherId);
        //将订单放入阻塞队列
        orderTasks.add(voucherOrder);
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }*/

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀时间是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀未开始");
        }
        // 3.判断秒杀时间是否结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已结束");
        }
        // 4.判断库存是否充足
        if(voucher.getStock()<1){
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();
        // 创建锁对象
        //SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
        RLock lock=redissonClient.getLock("order:" + userId);
        // 尝试获取锁
        boolean isLock = lock.tryLock();
        if(!isLock){
            //获取锁失败
            return Result.fail("不允许重复下单");
        }
        try {
            //防止事务失效，获取当前对象的代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            //无重复订单，则创建订单
            return proxy.createVoucherOrder(voucherId);
        } finally {
            //释放锁
            lock.unlock();
        }


    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 5.一人一单
        // 5.1用户id
        Long userId = UserHolder.getUser().getId();
        // 5.2根据用户id和优惠券id查询是否有订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if(count>0){
            return Result.fail("用户已下过单");
        }

        // 6.扣减库存
        boolean success = seckillVoucherService.update().setSql("stock=stock-1")//set stock=stock-1
                .eq("voucher_id", voucherId)//where voucher_id=voucherId
                .gt("stock",0)//and stock>0,提高乐观锁的成功率
                .update();
        if(!success){
            //扣减失败
            return Result.fail("库存不足");
        }
        // 7.创建订单
        VoucherOrder voucherOrder=new VoucherOrder();
        // 7.1订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 7.2用户id
        voucherOrder.setUserId(userId);
        // 7.3优惠券id
        voucherOrder.setVoucherId(voucherId);
        // 8. 插入订单记录
        save(voucherOrder);
        // 9.返回订单id
        return Result.ok(orderId);
    }*/



    //基于阻塞队列实现异步下单
    /*private BlockingQueue<VoucherOrder> orderTasks=new ArrayBlockingQueue<>(1024*1024);
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while(true){
                try {
                    //获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.info("处理订单异常"+e);
                }
            }
        }
    }*/
}
