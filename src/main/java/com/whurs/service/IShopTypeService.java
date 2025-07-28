package com.whurs.service;

import com.whurs.dto.Result;
import com.whurs.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IShopTypeService extends IService<ShopType> {

    /**
     * 根据类型查询店铺
     * @return
     */
    Result queryTypeList();

}
