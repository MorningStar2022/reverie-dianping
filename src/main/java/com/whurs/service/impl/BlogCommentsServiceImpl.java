package com.whurs.service.impl;

import com.whurs.entity.BlogComments;
import com.whurs.mapper.BlogCommentsMapper;
import com.whurs.service.IBlogCommentsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;


@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

}
