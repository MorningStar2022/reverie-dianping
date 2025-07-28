package com.whurs.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.whurs.dto.LoginFormDTO;
import com.whurs.dto.Result;
import com.whurs.entity.User;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;


public interface IUserService extends IService<User> {

    /**
     * 发送验证码
     * @param phone
     * @param session
     * @return
     */
    Result sendCode(String phone, HttpSession session);

    /**
     * 用户短信登录
     * @param loginForm
     * @param session
     * @return
     */
    Result login(LoginFormDTO loginForm, HttpSession session);

    /**
     * 用户签到
     * @return
     */
    Result sign();

    /**
     * 统计用户连续签到次数
     * @return
     */
    Result signCount();

    Result logout(HttpServletRequest request, HttpSession session);

}
