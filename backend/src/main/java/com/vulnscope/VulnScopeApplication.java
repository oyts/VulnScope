package com.vulnscope;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@OpenAPIDefinition(info = @Info(title = "VulnScope API", version = "1.0.0", description = "Java开源依赖漏洞六维分析系统接口"))
public class VulnScopeApplication {
    public static void main(String[] args) {
        SpringApplication.run(VulnScopeApplication.class, args);
    }
}
