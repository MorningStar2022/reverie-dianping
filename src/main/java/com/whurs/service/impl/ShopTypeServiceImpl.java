package com.whurs.service.impl;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.whurs.dto.Result;
import com.whurs.entity.Shop;
import com.whurs.entity.ShopType;
import com.whurs.mapper.ShopTypeMapper;
import com.whurs.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.whurs.utils.CacheClient;
import com.whurs.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;


@Service
@Slf4j
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private Cache<String, List<ShopType>> caffeineShopTypeCache;
    @Resource
    private CacheClient cacheClient;

    /**
     * 根据类型查询店铺
     * @return
     */
    /*@Override
    public Result queryTypeList() {
        //从redis中读取店铺类型缓存
        String key= RedisConstants.CACHE_SHOPTYPE_KEY;
        String shopTypeJson=stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopTypeJson)){

            List<ShopType> shopTypes = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(shopTypes);
        }
        List<ShopType> typeList = query().orderByAsc("sort").list();
        if (typeList == null) {
            return Result.fail("店铺类型不存在");
        }
        //将店铺类型写入缓存
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(typeList));
        return Result.ok(typeList);
    }*/

    @Override
    public Result queryTypeList() {
        String key = RedisConstants.CACHE_SHOPTYPE_KEY;
        List<ShopType> typeList = null;

        // 1. 优先从 Redis 获取缓存
        try {
            String shopTypeJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopTypeJson)) {
                //存在缓存，直接返回
                typeList = JSONUtil.toList(shopTypeJson, ShopType.class);
                return Result.ok(typeList);
            }
        } catch (Exception e) {
            log.error("Redis 访问异常，fallback 到 Caffeine 进程缓存：{}", e.getMessage());
        }

        // 2. Redis 获取失败 或未命中，尝试从 Caffeine 获取
        typeList = caffeineShopTypeCache.getIfPresent(key);
        if (typeList != null) {
            log.info("从 Caffeine 中读取到 ShopType 列表,"+typeList);
            return Result.ok(typeList);
        }

        // 3. 缓存都未命中，从数据库查询
        typeList = query().orderByAsc("sort").list();
        if (typeList == null) {
            return Result.fail("店铺类型不存在");
        }

        // 4. 写入 Redis & Caffeine
        try {
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(typeList));
        } catch (Exception e) {
            log.warn("写入 Redis 失败：{}", e.getMessage());
        }
        caffeineShopTypeCache.put(key, typeList);

        return Result.ok(typeList);
    }


}
