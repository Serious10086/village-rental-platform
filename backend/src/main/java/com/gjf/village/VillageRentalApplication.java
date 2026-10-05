package com.gjf.village;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 乡村闲置资源租售平台 —— 启动类。
 *
 * @author 高浚峰
 */
@SpringBootApplication
@MapperScan("com.gjf.village.mapper")
@EnableScheduling   // "到期提醒"定时任务需要
public class VillageRentalApplication {

    public static void main(String[] args) {
        SpringApplication.run(VillageRentalApplication.class, args);
    }
}
