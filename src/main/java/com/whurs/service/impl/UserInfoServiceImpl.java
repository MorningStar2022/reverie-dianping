package com.whurs.service.impl;

import com.whurs.entity.UserInfo;
import com.whurs.mapper.UserInfoMapper;
import com.whurs.service.IUserInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;


@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
