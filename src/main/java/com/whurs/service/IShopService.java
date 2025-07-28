package com.whurs.service;

import com.whurs.dto.Result;
import com.whurs.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IShopService extends IService<Shop> {

    /**
     * 根据id查询店铺
     * @param id
     * @return
     */
    Result queryById(Long id);

    /**
     * 更新店铺信息
     * @param shop
     * @return
     */
    Result update(Shop shop);

    void saveShop2Redis(Long id,Long expireTime) throws InterruptedException;

    Result queryByShopType(Integer typeId, Integer current, Double x, Double y);
}
