package com.whurs.service;

import com.whurs.dto.Result;
import com.whurs.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 优惠券秒杀下单
     * @param voucherId
     * @return
     */
    Result seckillVoucher(Long voucherId);
//同步秒杀的逻辑
//    Result createVoucherOrder(Long voucherId);

    /**
     * 异步秒杀时的逻辑
     */
    void createVoucherOrder(VoucherOrder voucherOrder);
}
