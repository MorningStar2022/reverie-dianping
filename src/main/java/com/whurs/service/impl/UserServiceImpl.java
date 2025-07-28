package com.whurs.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.whurs.dto.LoginFormDTO;
import com.whurs.dto.Result;
import com.whurs.dto.UserDTO;
import com.whurs.entity.User;
import com.whurs.mapper.UserMapper;
import com.whurs.service.IUserService;
import com.whurs.utils.RedisConstants;
import com.whurs.utils.RegexUtils;
import com.whurs.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.whurs.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private Cache<String, String> caffeineCodeCache;
    @Resource
    private Cache<String, UserDTO> caffeineLoginCache;

    /**
     * 发送验证码
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号是否正确
        if(RegexUtils.isPhoneInvalid(phone)){
            //不正确，提示错误信息
            return Result.fail("手机号不正确");
        }

        //正确，生成验证码，并保存到redis，设置有效期2min
        String code = RandomUtil.randomNumbers(6);
        String key = RedisConstants.LOGIN_CODE_KEY + phone;
        try {
            stringRedisTemplate.opsForValue().set(key,code,
                    RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.info("Redis服务出错，使用进程缓存,"+e);
        }
        caffeineCodeCache.put(key,code);
        //模拟发送验证码
        log.debug("发送短信验证码成功，验证码{}",code);
        //返回结果
        return Result.ok();

    }

    /**
     * 用户短信登录
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //校验手机号是否正确
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            //不正确，提示错误信息
            return Result.fail("手机号不正确");
        }
        //从redis中获取验证码并校验
        String code = loginForm.getCode();
        String cacheCode = null;
        String key = RedisConstants.LOGIN_CODE_KEY + phone;
        try {
            cacheCode = stringRedisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.info("Redis服务出错，使用进程缓存,"+e);
            cacheCode=caffeineCodeCache.getIfPresent(key);
        }
        if(cacheCode==null||!cacheCode.equals(code)){
            return Result.fail("验证码错误");
        }
        //判断该手机号有没有注册过，查询数据库
        User user = query().eq("phone", phone).one();
        //若无该用户，自动注册
        if(user==null){
            user=createUserWithPhone(phone);
        }
        //保存用户信息到redis
        //随机生成token作为登录令牌
        String token = UUID.randomUUID().toString(true).toString();
        UserDTO userDTO=BeanUtil.copyProperties(user,UserDTO.class);
        //user对象转为hash存储
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((fieldName,fieldValue)->
                    fieldValue.toString()
                ));
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        try {
            stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
            //设置token过期时间
            stringRedisTemplate.expire(tokenKey,RedisConstants.LOGIN_USER_TTL,TimeUnit.SECONDS);
        } catch (Exception e) {
            log.info("Redis服务出错，使用进程缓存,"+e);
        }
        caffeineLoginCache.put(tokenKey,userDTO);

        //返回token给前端
        return Result.ok(token);
    }

    /**
     * 用户签到
     * @return
     */
    @Override
    public Result sign() {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //获取当前时间
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.USER_SIGN_KEY +userId.toString()+ keySuffix;
        //获取当前时间是该月第几天
        int dayOfMonth = now.getDayOfMonth();
        //写入redis 使用bitmap完成签到
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();
    }

    /**
     * 统计用户连续签到次数
     * @return
     */
    @Override
    public Result signCount() {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //获取当前时间
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.USER_SIGN_KEY +userId.toString()+ keySuffix;
        //获取当前时间是该月第几天
        int dayOfMonth = now.getDayOfMonth();
        //取出该月当天为止所有签到记录，返回是十进制
        List<Long> bitField = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0));
        if(bitField==null||bitField.isEmpty()){
            return Result.ok(0);
        }
        Long num = bitField.get(0);
        if(num==null||num==0){
            return Result.ok(0);
        }
        int count=0;
        while(true){
            if((num&1)==0){
                break;
            }else {
                count++;
            }
            num>>>=1;
        }
        return Result.ok(count);
    }

    @Override
    public Result logout(HttpServletRequest request, HttpSession session) {
        session.invalidate();
        String token = request.getHeader("authorization");
        // 可选：把 token 加入 Redis 黑名单，或删除 Redis 中的登录状态
        if (token != null) {
            stringRedisTemplate.delete(RedisConstants.LOGIN_USER_KEY + token);
        }
        return Result.ok("登出成功");
    }

    /**
     * 根据手机号创建新用户
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone) {
        User user=new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
