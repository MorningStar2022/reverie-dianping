package com.whurs.service.impl;

import cn.hutool.core.lang.TypeReference;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.whurs.dto.Result;
import com.whurs.entity.Shop;
import com.whurs.entity.Voucher;
import com.whurs.mapper.VoucherMapper;
import com.whurs.entity.SeckillVoucher;
import com.whurs.service.ISeckillVoucherService;
import com.whurs.service.IVoucherService;
import com.whurs.utils.CacheClient;
import com.whurs.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;


@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    /**
     * 查询某店铺优惠券
     * @param shopId
     * @return
     */
    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        //List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        List<Voucher> vouchers = cacheClient.queryWithPassThrough(RedisConstants.CACHE_VOUCHER_KEY, shopId,
                new TypeReference<List<Voucher>>() {},
                id -> getBaseMapper().queryVoucherOfShop(id), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 返回结果
        return Result.ok(vouchers);
    }

    /**
     * 新增秒杀券
     * @param voucher
     */
    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存秒杀优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);
        //异步秒杀时需要将秒杀券的库存信息到redis中
        //保存秒杀券的库存信息到redis中
        stringRedisTemplate.opsForValue().set(RedisConstants.SECKILL_STOCK_KEY+voucher.getId(),
                voucher.getStock().toString());
    }
}
