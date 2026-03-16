package com.xhs.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "feishu.bitable")
public class FeishuConfig {

    private String appId;
    private String appSecret;
    private String appToken;
    private String tableId;
}