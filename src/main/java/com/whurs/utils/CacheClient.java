package com.whurs.utils;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.whurs.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    Cache<String, Object> caffeineObjectCache;
    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);

    /**
     * 将任意类型java对象序列化为json，并存储到string类型的key中，可以设置TTL过期时间
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        try {
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
        } catch (Exception e) {
            log.info("Redis服务出错，使用进程缓存,"+e);
        }
    }

    /**
     * 将任意类型java对象序列化为json，并存储到string类型的key中，可以设置逻辑过期时间，用于处理缓存击穿问题
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入Redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    /**
     * 根据指定的key查询缓存，并反序列化为指定类型java对象，利用缓存空值的方法解决缓存穿透问题
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallBack
     * @param time
     * @param unit
     * @param <R>
     * @param <ID>
     * @return
     */
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, TypeReference<R> type, Function<ID,R> dbFallBack, Long time, TimeUnit unit){
        //判断redis缓存中是否有该店铺
        String key = keyPrefix + id;
        String json = null;
        try {
            json = stringRedisTemplate.opsForValue().get(key);
            //若有，直接返回
            if(StrUtil.isNotBlank(json)){

                return JSONUtil.toBean(json,type,false);
            }
        } catch (Exception e) {
            log.error("Redis 访问异常，fallback 到 Caffeine 进程缓存：{}", e.getMessage());
        }
        //判断命中的是否是空值
        if(json!=null){
            return null;
        }
        R obj = (R) caffeineObjectCache.getIfPresent(key);
        if(obj==null){
            //若没有，查询mysql数据库，根据id查询
            R r = dbFallBack.apply(id);
            //若没有，返回错误信息
            if(r==null){
                //缓存空值，减少缓存穿透
                this.set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
                caffeineObjectCache.put(key,"");
                return null;
            }
            //若有，写入缓存，并返回
            this.set(key,r,time,unit);
            caffeineObjectCache.put(key,r);
            return r;
        }

        return obj;
    }



    /**
     * 根据指定的key查询缓存，并反序列化为指定类型java对象，利用逻辑过期的方法解决缓存击穿问题
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallBack
     * @param time
     * @param unit
     * @param <R>
     * @param <ID>
     * @return
     */
    public <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallBack,Long time, TimeUnit unit){
        //1.判断redis缓存中是否有该店铺
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.不存在，返回空
        if(StrUtil.isBlank(json)){
            return null;
        }
        //3.命中，将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        //4.判断是否过期
        //4.1未过期，直接返回店铺信息
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }
        //4.2已过期，需要缓存重建
        //5.缓存重建
        //5.1获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = trylock(lockKey);
        //5.2判断互斥锁是否获取成功
        if (isLock) {
            //5.3成功。则开启新线程
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //向数据库查询数据
                    R r0= dbFallBack.apply(id);
                    this.setWithLogicalExpire(key,r0,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //5.4释放锁
                    unlock(lockKey);
                }
            });
        }

        //5.4返回过期的店铺信息
        return r;
    }

    /**
     * 互斥锁
     * 获取锁
     * @param key
     * @return
     */
    private boolean trylock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 互斥锁
     * 释放锁
     * @param key
     */
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }


    /**
     * 同时解决缓存穿透与缓存击穿
     */
    public <R, ID> R queryWithPassThroughAndLogicalExpire(
            String keyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallBack,
            Long time, TimeUnit unit,
            boolean useLogicalExpire) {

        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 1. 空字符串，返回 null（穿透处理）
        if ("".equals(json)) {
            return null;
        }

        // 2. 有数据
        if (StrUtil.isNotBlank(json)) {
            if (useLogicalExpire) {
                // 尝试解析为逻辑过期结构
                try {
                    RedisData redisData = JSONUtil.toBean(json, RedisData.class);
                    LocalDateTime expireTime = redisData.getExpireTime();
                    R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
                    if (expireTime.isAfter(LocalDateTime.now())) {
                        return r;
                    }

                    // 已过期，尝试异步重建缓存
                    String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
                    boolean isLock = trylock(lockKey);
                    if (isLock) {
                        CACHE_REBUILD_EXECUTOR.submit(() -> {
                            try {
                                // 双重检查：重建前再次获取缓存，可能别的线程已经更新
                                String newJson = stringRedisTemplate.opsForValue().get(key);
                                if (StrUtil.isNotBlank(newJson)) {
                                    RedisData newRedisData = JSONUtil.toBean(newJson, RedisData.class);
                                    if (newRedisData.getExpireTime().isAfter(LocalDateTime.now())) {
                                        // 已经被其他线程更新了，直接放弃重建
                                        return;
                                    }
                                }
                                // 重新查询数据库并缓存
                                R freshData = dbFallBack.apply(id);
                                this.setWithLogicalExpire(key, freshData, time, unit);
                            } catch (Exception e) {
                                log.error("缓存重建异常", e);
                            } finally {
                                unlock(lockKey);
                            }
                        });
                    }
                    // 返回旧数据
                    return r;

                } catch (Exception e) {
                    // fallback: 数据不是逻辑结构，转普通对象
                    return JSONUtil.toBean(json, type);
                }
            } else {
                // 直接返回普通对象（不考虑逻辑过期）
                return JSONUtil.toBean(json, type);
            }
        }

        // 3. 缓存未命中 → 查询数据库
        R r = dbFallBack.apply(id);
        if (r == null) {
            // 缓存空值，防止穿透
            this.set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 4. 写入缓存
        if (useLogicalExpire) {
            this.setWithLogicalExpire(key, r, time, unit);
        } else {
            this.set(key, r, time, unit);
        }

        return r;
    }


}
