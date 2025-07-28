package com.whurs.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.TypeReference;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Cache;
import com.whurs.dto.Result;
import com.whurs.dto.ScrollResult;
import com.whurs.dto.UserDTO;
import com.whurs.entity.*;
import com.whurs.mapper.BlogMapper;
import com.whurs.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.whurs.service.IFollowService;
import com.whurs.service.IUserService;
import com.whurs.utils.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


@Service
@Slf4j
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;
    @Resource
    Cache<String, List<Blog>> caffeineHotBlogCache;
    @Resource
    Cache<String, Blog> caffeineBlogCache;
    @Resource
    private CacheClient cacheClient;
    @Resource
    private RedisConnectionCheck redisConnectionCheck;
    @Resource
    private Cache<String, Object> caffeineObjectCache;

    /**
     * 根据博客id查询对应博客的具体信息
     * @param id
     * @return
     */
    @Override
    public Result queryBlogById(Long id) {
        //实现缓存同步
        Blog blog = cacheClient.queryWithPassThrough(
                RedisConstants.BLOG_KEY,
                id,
                new TypeReference<Blog>() {},
                blogId -> {
                    Blog bg = getById(blogId);
                    if (bg == null) {
                        return null;
                    }
                    queryBlogUser(bg);
                    isBlogLiked(bg);
                    return bg;
                },
                RedisConstants.BLOG_TTL,
                TimeUnit.MINUTES
        );
        return blog == null ? Result.fail("博客不存在") : Result.ok(blog);
        /*//查询博客
        String key = RedisConstants.BLOG_KEY+id;
        Blog blog=null;
        try {
            //尝试查询redis
            String blogStr = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isNotBlank(blogStr)){
                blog = JSONUtil.toBean(blogStr, Blog.class);
            }
        } catch (Exception e) {
            log.error("Redis 访问异常，fallback 到 Caffeine 进程缓存：{}", e.getMessage());
        }
        //查询redis缓存失败，尝试查询进程缓存
        if(blog==null){
            blog = caffeineBlogCache.getIfPresent(key);
        }
        //进程缓存查询失败，尝试查询数据库
        if(blog==null){
            blog = getById(id);
            //查询用户
            queryBlogUser(blog);
            //查询博客是否被点赞
            isBlogLiked(blog);
            try {
                //写入二级缓存redis
                stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(blog)
                        ,RedisConstants.BLOG_TTL,TimeUnit.MINUTES);
            } catch (Exception e) {
                log.info("Redis服务出错，使用进程缓存,"+e);
            }
            //写入三级缓存
            caffeineBlogCache.put(key,blog);

        }
        return Result.ok(blog);*/
    }

    /**
     * 判断博客是否被当前用户点赞
     * @param blog
     */
    private void isBlogLiked(Blog blog) {
        UserDTO userDTO = UserHolder.getUser();
        log.info("user="+userDTO);
        if(userDTO==null){
            blog.setIsLike(false);
            //用户未登录，无需查询是否点赞
            return;
        }
        Long userId = userDTO.getId();
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        // 2.判断当前博客是否被该用户点赞
        Double score = null;
        if(redisConnectionCheck.isRedisAvailable()){
            score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        }

        blog.setIsLike(score!=null);
    }

    /**
     * 查询热点博客
     * @param current
     * @return
     */
    @Override
    public Result queryHotBlog(Integer current) {
        //实现缓存同步
        List<Blog> records = cacheClient.queryWithPassThrough(RedisConstants.BLOG_HOT_KEY,
                current, new TypeReference<List<Blog>>() {},
                cur -> {
                    List<Blog> bgs = query()
                            .orderByDesc("liked")
                            .page(new Page<>(cur, SystemConstants.MAX_PAGE_SIZE)).getRecords();
                    bgs.forEach(bg -> {
                        this.queryBlogUser(bg);
                        this.isBlogLiked(bg);
                    });
                    return bgs;
                }
                , RedisConstants.BLOG_HOT_TTL, TimeUnit.MINUTES);
        return Result.ok(records);
        /*String hotKey = RedisConstants.BLOG_HOT_KEY+current;
        try {
            // 查询redis缓存
            String blogListJson = stringRedisTemplate.opsForValue().get(hotKey);
            if(StrUtil.isNotBlank(blogListJson)){
                List<Blog> records = JSONUtil.toList(blogListJson, Blog.class);
                return Result.ok(records);
            }
        } catch (Exception e) {
            log.error("Redis 访问异常，fallback 到 Caffeine 进程缓存：{}", e.getMessage());
        }
        //查询进程缓存
        List<Blog> records = caffeineHotBlogCache.getIfPresent(hotKey);
        if(records==null||records.isEmpty()){
            // 查询数据库
            Page<Blog> page = query()
                    .orderByDesc("liked")
                    .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
            // 获取当前页数据
            records = page.getRecords();
            // 查询用户
            records.forEach(blog -> {
                this.queryBlogUser(blog);
                this.isBlogLiked(blog);
            });
            try {
                stringRedisTemplate.opsForValue().set(hotKey,JSONUtil.toJsonStr(records),
                        RedisConstants.BLOG_HOT_TTL, TimeUnit.MINUTES);
            } catch (Exception e) {
                log.info("Redis服务出错，使用进程缓存,"+e);
            }
            caffeineHotBlogCache.put(hotKey,records);
        }
        return Result.ok(records);*/
    }

    /**
     * 修改某条博客点赞数量
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        if(redisConnectionCheck.isRedisAvailable()){
            // 1.获取当前用户id
            Long userId = UserHolder.getUser().getId();
            if(userId==null){
                return Result.fail("用户未登录，不能点赞");
            }
            String key = RedisConstants.BLOG_LIKED_KEY + id;
            // 2.判断当前博客是否被该用户点赞
            Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
            if(score==null){
                // 2.1未点赞，则可以点赞，点赞数+1
                boolean isSuccess = update().setSql("liked=liked+1").eq("id", id).update();
                if(isSuccess){
                    //将该用户id加入redis
                    stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
                    //stringRedisTemplate.expire(key,RedisConstants.BLOG_TTL,TimeUnit.MINUTES);
                }
            }else{
                // 2.2已点赞，则不可以点赞，再次请求会将点赞数-1
                boolean isSuccess = update().setSql("liked=liked-1").eq("id", id).update();
                if(isSuccess){
                    //将该用户id从redis删除
                    stringRedisTemplate.opsForZSet().remove(key,userId.toString());
                }
            }
        }

        return Result.ok();
    }

    /**
     * 查询点赞top5，时间顺序
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        //查询最先点赞的前5名用户
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Set<String> userLikes = null;
        try {
            userLikes = stringRedisTemplate.opsForZSet().range(key, 0, 4);
            if(userLikes!=null){
                caffeineObjectCache.put(key,userLikes);
            }
        } catch (Exception e) {
            log.error("Redis 访问异常，fallback 到 Caffeine 进程缓存：{}", e.getMessage());
            userLikes= (Set<String>) caffeineObjectCache.getIfPresent(key);
        }
        //未查询到对应用户列表
        if(userLikes==null||userLikes.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //解析用户id
        List<Long> ids = userLikes.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());
        String idsStr = StrUtil.join(",", ids);
        //根据用户id查询用户信息
        List<User> users = userService
                .query()
                .in("id",ids)
                .last("order by field(id,"+idsStr+")").list();
        //封装UserDTO
        List<UserDTO> userDTOS = users.stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    /**
     * 保存博客并推送给所有粉丝
     * @param blog
     * @return
     */
    @Override
    public Result saveBlog(Blog blog) {
        if (new RedisConnectionCheck().isRedisAvailable()) {
            // 获取登录用户
            UserDTO user = UserHolder.getUser();
            Long userId = user.getId();
            blog.setUserId(userId);
            // 保存探店博文
            boolean isSuccess = save(blog);
            if(!isSuccess){
                return Result.fail("博客保存失败");
            }
            //查询当前用户的所有粉丝
            List<Follow> follows = followService.query().eq("follow_user_id", userId).list();
            //推送博客给所有粉丝
            for (Follow follow : follows) {
                String key = RedisConstants.FEED_KEY + follow.getUserId();
                stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
            }
        }
        return Result.ok(blog.getId());
    }

    /**
     * 滚动分页查询当前用户关注的人的消息
     * @param max
     * @param offset
     * @return
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        if (redisConnectionCheck.isRedisAvailable()) {
            //获取当前用户id
            Long userId = UserHolder.getUser().getId();
            //在收件箱中查询对应消息
            String key = RedisConstants.FEED_KEY + userId;
            Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                    .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
            //非空判断
            if(typedTuples==null||typedTuples.isEmpty()){
                return Result.ok();
            }
            //解析数据：blogId、minTime、offset
            List<Long> ids=new ArrayList<>(typedTuples.size());
            long minTime=0;
            int os=1;
            for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
                //获取blogId
                ids.add(Long.valueOf(typedTuple.getValue()));
                //获取最小时间
                long time=typedTuple.getScore().longValue();
                if(time==minTime){
                    os++;
                }else {
                    minTime=time;
                    os=1;
                }

            }
            //根据blogId查询blog
            String idsStr = StrUtil.join(",", ids);
            List<Blog> blogs = query().in("id", ids).last("order by field(id," + idsStr + ")").list();
            for (Blog blog : blogs) {
                queryBlogUser(blog);
                isBlogLiked(blog);
            }
            //封装并返回
            ScrollResult sr=new ScrollResult();
            sr.setList(blogs);
            sr.setMinTime(minTime);
            sr.setOffset(os);
            return Result.ok(sr);
        }
        return Result.fail("服务暂不可用");
    }

    @Override
    public Result queryMyBlog(Integer current) {
        // 获取登录用户
        Long userId = UserHolder.getUser().getId();
        return queryBlogByUserId(current,userId);
    }

    @Override
    public Result queryBlogByUserId(Integer current, Long id) {
        if(id==null){
            return Result.fail("id错误");
        }
        List<Blog> records=cacheClient.queryWithPassThrough(RedisConstants.BLOG_KEY+":"+id, current,
                new TypeReference<List<Blog>>() {},
                cur -> query()
                        .eq("user_id", id)
                        .page(new Page<>(cur, SystemConstants.MAX_PAGE_SIZE))
                        .getRecords()
                , RedisConstants.BLOG_TTL, TimeUnit.MINUTES);
        return Result.ok(records);
    }

    /**
     * 查询博客对应的用户信息
     * @param blog
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
