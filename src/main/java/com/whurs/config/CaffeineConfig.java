package com.whurs.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.whurs.dto.UserDTO;
import com.whurs.entity.Blog;
import com.whurs.entity.ShopType;
import com.whurs.utils.RedisConstants;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
public class CaffeineConfig {
    @Bean
    public Cache<String, UserDTO> caffeineLoginCache() {
        return Caffeine.newBuilder()
                .initialCapacity(100)
                .maximumSize(10_000)
                .expireAfterWrite(RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS)
                .build();
    }

    @Bean
    public Cache<String, String> caffeineCodeCache() {
        return Caffeine.newBuilder()
                .initialCapacity(100)
                .maximumSize(10_000)
                .expireAfterWrite(RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES)
                .build();
    }

    @Bean
    public Cache<String, List<ShopType>> caffeineShopTypeCache() {
        return Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .build();
    }

    @Bean
    public Cache<String, List<Blog>> caffeineHotBlogCache() {
        return Caffeine.newBuilder()
                .initialCapacity(10)
                .maximumSize(100)
                .expireAfterWrite(RedisConstants.BLOG_HOT_TTL, TimeUnit.MINUTES)
                .build();
    }
    @Bean
    public Cache<String, Blog> caffeineBlogCache() {
        return Caffeine.newBuilder()
                .initialCapacity(100)
                .maximumSize(10_000)
                .expireAfterWrite(RedisConstants.BLOG_TTL, TimeUnit.MINUTES)
                .build();
    }

    @Bean
    public Cache<String, Object> caffeineObjectCache() {
        return Caffeine.newBuilder()
                .initialCapacity(100)
                .maximumSize(10_000)
                .expireAfterWrite(RedisConstants.BLOG_TTL, TimeUnit.MINUTES)
                .build();
    }


}
