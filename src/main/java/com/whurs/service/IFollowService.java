package com.whurs.service;

import com.whurs.dto.Result;
import com.whurs.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IFollowService extends IService<Follow> {

    /**
     * 关注、取关用户
     * @param followUserId
     * @param isFollow
     * @return
     */
    Result follow(Long followUserId, Boolean isFollow);

    /**
     * 判断当前用户是否关注了某个用户
     * @param followUserId
     * @return
     */
    Result isFollow(Long followUserId);

    /**
     * 与目标用户的共同关注
     * @param id
     * @return
     */
    Result followCommons(Long id);
}
