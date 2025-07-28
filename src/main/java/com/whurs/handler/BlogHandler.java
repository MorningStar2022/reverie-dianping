package com.whurs.handler;
import cn.hutool.http.HttpUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.whurs.entity.Blog;
import com.whurs.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.javatool.canal.client.annotation.CanalTable;
import top.javatool.canal.client.handler.EntryHandler;

import javax.annotation.Resource;
import java.util.HashMap;
@Slf4j
@CanalTable("tb_blog")
@Component
public class BlogHandler implements EntryHandler<Blog> {
    @Resource
    private RedisHandler redisHandler;
    @Resource
    Cache<String, Object> caffeineObjectCache;


    @Override
    public void insert(Blog blog) {
        String key = RedisConstants.BLOG_KEY + blog.getId();
        // 写数据到JVM进程缓存
        caffeineObjectCache.put(key, blog);
        // 写数据到redis
        redisHandler.saveBlog(blog);
        redisHandler.deleteHot();
        //清除nginx本地缓存
        for (int i = 1; i <=10 ; i++) {
            clearNginxLocalCache(RedisConstants.BLOG_HOT_KEY, (long) i);
            caffeineObjectCache.invalidate(RedisConstants.BLOG_HOT_KEY+(long) i);
        }

    }


    @Override
    public void update(Blog before, Blog after) {
        String key = RedisConstants.BLOG_KEY + after.getId();
        // 写数据到JVM进程缓存
        caffeineObjectCache.put(key, after);
        // 写数据到redis
        redisHandler.saveBlog(after);
        redisHandler.deleteHot();
        //清除nginx本地缓存
        for (int i = 1; i <=10 ; i++) {
            clearNginxLocalCache(RedisConstants.BLOG_HOT_KEY, (long) i);
            caffeineObjectCache.invalidate(RedisConstants.BLOG_HOT_KEY+(long) i);
        }
    }

    @Override
    public void delete(Blog blog) {
        String key = RedisConstants.BLOG_KEY + blog.getId();
        // 删除数据到JVM进程缓存
        caffeineObjectCache.invalidate(key);
        // 删除数据到redis
        redisHandler.deleteById(blog.getId());
        redisHandler.deleteHot();
        //清除nginx本地缓存
        for (int i = 1; i <=10 ; i++) {
            clearNginxLocalCache(RedisConstants.BLOG_HOT_KEY, (long) i);
            caffeineObjectCache.invalidate(RedisConstants.BLOG_HOT_KEY+(long) i);
        }
    }

    public void clearNginxLocalCache(String keyPrefix,Long id) {
        try {
            HashMap<String, Object> params = new HashMap<>();
            params.put("key", keyPrefix+ id); //  Lua中的key
            String post = HttpUtil.post("http://localhost:8088/cache/delete", params);
            ;log.info("发送成功"+post);

        } catch (Exception e) {
            System.err.println("Nginx 缓存清除失败: " + e.getMessage());
        }
    }
}
