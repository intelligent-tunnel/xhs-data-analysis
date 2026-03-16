package com.xhs.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "xhs")
public class XhsConfig {

    private String redirectUri;
    private String authUrl;
    private String tokenUrl;
    private String refreshUrl;
    private List<AccountConfig> accounts;

    @Data
    public static class AccountConfig {
        private Integer appId;
        private String secret;
        private String name;
        private List<Long> advertiserIds;
    }

    /**
     * 根据 appId 查找账号配置
     */
    public AccountConfig getAccountByAppId(Integer appId) {
        return accounts.stream()
                .filter(a -> a.getAppId().equals(appId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到 appId=" + appId + " 的账号配置"));
    }
}