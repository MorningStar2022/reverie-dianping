package com.whurs.service;

import com.whurs.dto.Result;
import com.whurs.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IBlogService extends IService<Blog> {

    /**
     * 根据博客id查询对应的博客，展示用户信息
     * @param id
     * @return
     */
    Result queryBlogById(Long id);

    /**
     * 查询热门博客
     * @param current
     * @return
     */
    Result queryHotBlog(Integer current);

    /**
     * 修改某条博客点赞数量
     * @param id
     * @return
     */
    Result likeBlog(Long id);

    /**
     * 查询点赞top5，时间顺序
     * @param id
     * @return
     */
    Result queryBlogLikes(Long id);
    /**
     * 保存博客并推送给所有粉丝
     * @param blog
     * @return
     */
    Result saveBlog(Blog blog);

    /**
     * 滚动分页查询
     * @param max
     * @param offset
     * @return
     */
    Result queryBlogOfFollow(Long max, Integer offset);

    Result queryMyBlog(Integer current);


    Result queryBlogByUserId(Integer current, Long id);
}
