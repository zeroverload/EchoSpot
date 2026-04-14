package com.zeroverload;

import com.zeroverload.entity.Voucher;
import com.zeroverload.service.IVoucherService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.time.LocalDateTime;

@SpringBootTest
public class SeckillTest {

    @Resource
    private IVoucherService voucherService;

    // 添加200个库存的秒杀商品数据
    @Test
    void addSeckillVoucher() {
        // 创建秒杀优惠券
        Voucher voucher = new Voucher();
        voucher.setShopId(1L);
        voucher.setTitle("200个库存的秒杀商品");
        voucher.setSubTitle("限时秒杀，先到先得");
        voucher.setRules("秒杀规则：每人限抢1件\n有效期：2026年3月3日-2026年12月31日");
        voucher.setPayValue(500L); // 5元
        voucher.setActualValue(1000L); // 10元
        voucher.setType(1); // 1: 秒杀券
        voucher.setStatus(1); // 1: 上架
        voucher.setStock(200); // 200个库存
        voucher.setBeginTime(LocalDateTime.now()); // 立即开始
        voucher.setEndTime(LocalDateTime.of(2026, 12, 31, 23, 59, 59)); // 年底结束

        // 调用服务添加秒杀券
        voucherService.addSeckillVoucher(voucher);

        System.out.println("秒杀商品添加成功，ID: " + voucher.getId() + "，库存: 200");
    }
}