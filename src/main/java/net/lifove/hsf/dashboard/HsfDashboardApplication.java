package net.lifove.hsf.dashboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan   // hsf.* 설정을 HsfProperties 로 바인딩
@EnableScheduling              // 6시간마다 동기화 (hsf.sync.enabled 로 on/off)
public class HsfDashboardApplication {

    public static void main(String[] args) {
        SpringApplication.run(HsfDashboardApplication.class, args);
    }
}
