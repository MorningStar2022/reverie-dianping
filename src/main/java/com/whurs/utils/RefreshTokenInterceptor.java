package com.whurs.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.whurs.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 拦截所有请求，刷新token
 * 这样无论用户是否访问需要校验的页面，均会刷新token有效期
 */
@Slf4j
@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private Cache<String, UserDTO> caffeineLoginCache;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //获取请求头中的token
        String token=request.getHeader("authorization");
        if(StrUtil.isBlank(token)){
            return true;
        }
        //基于token从redis中获取当前用户信息
        String key = RedisConstants.LOGIN_USER_KEY + token;
        UserDTO userDTO= null;
        try {
            Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
            //判断用户是否存在
            if(userMap.isEmpty()){
                return true;
            }
            //查询到的用户转为userdto
            userDTO = BeanUtil.fillBeanWithMap(userMap,new UserDTO(),false);
        } catch (Exception e) {
            log.warn("Redis访问失败，尝试使用Caffeine缓存：{}", e.getMessage());
        }

        // Redis服务宕机时 fallback 到 Caffeine
        if (userDTO == null) {
            userDTO = caffeineLoginCache.getIfPresent(key);
            if (userDTO != null) {
                log.info("从Caffeine缓存中恢复用户信息，token={}", token);
            } else {
                log.info("用户信息丢失或key过期啦，对不起喵~");
                return true; // 进程缓存中没有用户信息(用户信息丢失或key过期)，放行
            }
        }
        //用户存在，保存到threadLocal
        UserHolder.saveUser(userDTO);
        //刷新token有效期
        //stringRedisTemplate.expire(key,RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
