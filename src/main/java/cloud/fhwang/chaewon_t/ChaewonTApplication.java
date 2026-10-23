package cloud.fhwang.chaewon_t;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling // 임시 파일 정리(TempWorkspace.deleteExpired)
public class ChaewonTApplication {

	public static void main(String[] args) {
		SpringApplication.run(ChaewonTApplication.class, args);
	}

}
