package com.whurs;

import cn.hutool.json.JSONUtil;
import com.whurs.dto.LoginFormDTO;
import com.whurs.dto.Result;
import com.whurs.dto.UserDTO;
import com.whurs.entity.Shop;
import com.whurs.service.IShopService;
import com.whurs.service.IUserService;
import com.whurs.service.impl.ShopServiceImpl;
import com.whurs.utils.CacheClient;
import com.whurs.utils.RedisConstants;
import com.whurs.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SpringBootTest
class ReverieDianpingApplicationTests {

    @Resource
    private IShopService shopService;
    @Resource
    private CacheClient cacheClient;
    @Resource
    private RedisIdWorker redisIdWorker;
    private ExecutorService executors=Executors.newFixedThreadPool(500);
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    @Test
    void testSaveShop() throws Exception {
//        shopService.saveShop2Redis(1L,10L);
        Shop shop=shopService.getById(1L);
        cacheClient.setWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY+1L, shop,10L, TimeUnit.SECONDS);
    }
    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch=new CountDownLatch(300);
        Runnable task=()->{
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id="+id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i <300; i++) {
            executors.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time="+(end-begin));
    }
    
    @Test
    void loadGeoShopData(){
        //查询所有店铺
        List<Shop> shops = shopService.list();
        //按照类型分组
        Map<Long, List<Shop>> shopMap = shops.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        for (Map.Entry<Long, List<Shop>> entry : shopMap.entrySet()) {
            Long typeId = entry.getKey();
            List<Shop> value = entry.getValue();
            String key="shop:geo:"+typeId;
            List<RedisGeoCommands.GeoLocation<String>> locations=new ArrayList<>(value.size());
            for (Shop shop : value) {
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),
                        new Point(shop.getX(),shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key,locations);
        }
    }

    @Test
    void testHyperLogLog(){
        String[] users=new String[1000];
        int j=0;
        for (int i=0;i<1000000;++i){
            j=i%1000;
            users[j]="user_"+i;
            if(j==999){
                stringRedisTemplate.opsForHyperLogLog().add("user",users);
            }
        }
        Long size = stringRedisTemplate.opsForHyperLogLog().size("user");
        System.out.println(size);
    }

    /**
     * 测试扫描所有key并找出bigKey
     */
    @Test
    void testBigKey(){
        int MAX_STR_LEN=100;
        int MAX_HASH_LEN=1024*10;
        ScanOptions options = ScanOptions.scanOptions()
                .count(10)
                .build();
        Cursor<byte[]> cursor = stringRedisTemplate.executeWithStickyConnection(
                connection -> connection.scan(options)
        );
        long size = 0;
        int maxSize=0;
        while (cursor.hasNext()) {
            byte[] next = cursor.next();
            String key = new String(next);

            String type = stringRedisTemplate.type(key).code();
            switch(type) {
                case "string":
                    size = stringRedisTemplate.opsForValue().get(key).length();
                    maxSize=MAX_STR_LEN;
                    break;
                case "list":
                    size = stringRedisTemplate.opsForList().size(key);
                    maxSize=MAX_HASH_LEN;
                    break;
                case "hash":
                    size = stringRedisTemplate.opsForHash().size(key);
                    maxSize=MAX_HASH_LEN;
                    break;
                case "set":
                    size = stringRedisTemplate.opsForSet().size(key);
                    maxSize=MAX_HASH_LEN;
                    break;
                case "zset":
                    size = stringRedisTemplate.opsForZSet().size(key);
                    maxSize=MAX_HASH_LEN;
                    break;
                // ...
            }
            if (size > maxSize) {
                System.out.println("Big key: "+type+":" + key);
            }
        }

    }




    private static final int USER_COUNT = 10000;
    private static final String FILE_PATH = "D:\\code\\java\\reverie-dianping\\src\\main\\resources\\tokens_10000.txt";

    private static final String[] PREFIXES = {
            "130", "131", "132", "133", "134", "135", "136", "137", "138", "139",
            "150", "151", "152", "153", "155", "156", "157", "158", "159",
            "166",
            "170", "171", "173", "175", "176", "177", "178",
            "180", "181", "182", "183", "184", "185", "186", "187", "188", "189",
            "198", "199",
            "145", "147", "149"
    };

    private final Random random = new Random();

    /**
     * 生成10万用户的token用于压测
     * @throws Exception
     */
    @Test
    public void generateTokensTest() throws Exception {
        List<String> tokenList = new ArrayList<>(USER_COUNT);
        Set<String> phoneSet = new HashSet<>(USER_COUNT);

        for (int i = 0; i < USER_COUNT; i++) {
            String phone;
            do {
                phone = generateValidPhone();
            } while (!phoneSet.add(phone));

            // 写入验证码
            stringRedisTemplate.opsForValue()
                    .set(RedisConstants.LOGIN_CODE_KEY + phone, "123456", 2, TimeUnit.MINUTES);

            LoginFormDTO form = new LoginFormDTO();
            form.setPhone(phone);
            form.setCode("123456");

            Result result = userService.login(form, null);
            if (result.getSuccess()) {
                tokenList.add((String) result.getData());
            } else {
                throw new RuntimeException("登录失败: phone=" + phone + ", msg=" + result.getErrorMsg());
            }
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(FILE_PATH))) {
            for (String token : tokenList) {
                writer.write(token);
                writer.newLine();
            }
        }

        System.out.println("✅ " + tokenList.size() + " tokens saved to " + FILE_PATH);
    }

    private String generateValidPhone() {
        String prefix = PREFIXES[random.nextInt(PREFIXES.length)];
        int suffix = 10000000 + random.nextInt(90000000);
        return prefix + suffix;
    }

}
