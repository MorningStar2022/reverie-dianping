package com.whurs.handler;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.github.benmanes.caffeine.cache.Cache;
import com.whurs.entity.Blog;
import com.whurs.service.IBlogService;
import com.whurs.utils.RedisConstants;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

@Component
public class RedisHandler implements InitializingBean {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IBlogService blogService;
    @Resource
    BlogHandler blogHandler;
    @Resource
    Cache<String, Object> caffeineObjectCache;
    //private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void afterPropertiesSet() {
        for (int i = 1; i <=10 ; i++) {
            blogHandler.clearNginxLocalCache(RedisConstants.BLOG_HOT_KEY, (long) i);
            caffeineObjectCache.invalidate(RedisConstants.BLOG_HOT_KEY+(long) i);
        }
        blogHandler.clearNginxLocalCache(RedisConstants.CACHE_SHOPTYPE_KEY, null);
        caffeineObjectCache.invalidate(RedisConstants.CACHE_SHOPTYPE_KEY);
        // 初始化缓存
        // 1.查询商品信息
        List<Blog> blogList = blogService.list();
        // 2.放入缓存
        for (Blog blog : blogList) {
            // 2.1.item序列化为JSON
            String json = JSONUtil.toJsonStr(blog);
            // 2.2.存入redis
            stringRedisTemplate.opsForValue().set(RedisConstants.BLOG_KEY + blog.getId(), json);
        }


    }

    public void saveBlog(Blog blog) {
        try {
            String key = RedisConstants.BLOG_KEY + blog.getId();
            String json = JSONUtil.toJsonStr(blog);
            stringRedisTemplate.opsForValue().set(key, json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void deleteById(Long id) {
        stringRedisTemplate.delete(RedisConstants.BLOG_KEY + id);
    }

    //blog数据变化时，同步删除blog:hot
    public void deleteHot() {
        ScanOptions options = ScanOptions.scanOptions()
                .count(10)
                .match(RedisConstants.BLOG_HOT_KEY+"*")
                .build();
        Cursor<byte[]> cursor = stringRedisTemplate.executeWithStickyConnection(
                connection -> connection.scan(options)
        );

        while (cursor.hasNext()) {
            byte[] next = cursor.next();
            String key = new String(next);
            stringRedisTemplate.delete(key);
        }
    }
}
