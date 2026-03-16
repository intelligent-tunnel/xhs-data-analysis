package com.xhs.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xhs.config.XhsConfig;
import com.xhs.model.TokenInfo;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    private final XhsConfig xhsConfig;
    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private static final String TOKEN_FILE = "tokens.json";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    /** appId -> TokenInfo */
    private final ConcurrentHashMap<Integer, TokenInfo> tokenMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        loadTokensFromFile();
        tokenMap.forEach((appId, token) ->
                log.info("加载 token: appId={}, advertiserId={}", appId, token.getAdvertiserId()));
        if (tokenMap.isEmpty()) {
            log.warn("未找到已保存的 token，请完成 OAuth 授权");
        }
    }

    /**
     * 用 auth_code 换取 token
     */
    public TokenInfo fetchToken(Integer appId, String authCode) throws IOException {
        XhsConfig.AccountConfig account = xhsConfig.getAccountByAppId(appId);

        Map<String, Object> body = new HashMap<>();
        body.put("app_id", account.getAppId());
        body.put("secret", account.getSecret());
        body.put("auth_code", authCode);

        String json = objectMapper.writeValueAsString(body);
        log.info("请求获取 token, appId={}", appId);

        Request request = new Request.Builder()
                .url(xhsConfig.getTokenUrl())
                .post(RequestBody.create(json, JSON_TYPE))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = null;
            if (response.body() != null) {
                responseBody = response.body().string();
            }
            log.info("获取 token 响应 (appId={}): {}", appId, responseBody);

            JsonNode root = objectMapper.readTree(responseBody);
            if (root.path("code").asInt() != 0) {
                throw new IOException("获取 token 失败: " + root.path("msg").asText());
            }

            TokenInfo tokenInfo = objectMapper.treeToValue(root.path("data"), TokenInfo.class);
            tokenInfo.setObtainedAt(LocalDateTime.now());
            tokenInfo.setAppId(appId);
            tokenMap.put(appId, tokenInfo);
            saveTokensToFile();

            log.info("获取 token 成功, appId={}, advertiserId={}", appId, tokenInfo.getAdvertiserId());
            return tokenInfo;
        }
    }

    /**
     * 刷新指定账号的 token
     */
    public TokenInfo refreshToken(Integer appId) throws IOException {
        TokenInfo current = tokenMap.get(appId);
        if (current == null || current.getRefreshToken() == null) {
            throw new IllegalStateException("appId=" + appId + " 无可用的 refresh_token，请先完成授权");
        }

        XhsConfig.AccountConfig account = xhsConfig.getAccountByAppId(appId);

        Map<String, Object> body = new HashMap<>();
        body.put("app_id", account.getAppId());
        body.put("secret", account.getSecret());
        body.put("refresh_token", current.getRefreshToken());

        String json = objectMapper.writeValueAsString(body);
        log.info("刷新 token, appId={}", appId);

        Request request = new Request.Builder()
                .url(xhsConfig.getRefreshUrl())
                .post(RequestBody.create(json, JSON_TYPE))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();
            log.info("刷新 token 响应 (appId={}): {}", appId, responseBody);

            JsonNode root = objectMapper.readTree(responseBody);
            if (root.path("code").asInt() != 0) {
                throw new IOException("刷新 token 失败: " + root.path("msg").asText());
            }

            TokenInfo tokenInfo = objectMapper.treeToValue(root.path("data"), TokenInfo.class);
            tokenInfo.setObtainedAt(LocalDateTime.now());
            tokenInfo.setAppId(appId);
            tokenMap.put(appId, tokenInfo);
            saveTokensToFile();

            log.info("刷新 token 成功, appId={}", appId);
            return tokenInfo;
        }
    }

    /**
     * 定时刷新所有账号的 token（每 20 小时）
     */
    @Scheduled(fixedRate = 20 * 60 * 60 * 1000, initialDelay = 60 * 1000)
    public void scheduledRefreshAll() {
        tokenMap.keySet().forEach(appId -> {
            try {
                log.info("定时刷新 token, appId={}", appId);
                refreshToken(appId);
            } catch (Exception e) {
                log.error("定时刷新 token 失败, appId={}", appId, e);
            }
        });
    }

    /**
     * 获取指定账号的 access_token
     */
    public String getAccessToken(Integer appId) {
        TokenInfo token = tokenMap.get(appId);
        if (token == null) {
            throw new IllegalStateException("appId=" + appId + " 未授权");
        }
        return token.getAccessToken();
    }

    public TokenInfo getToken(Integer appId) {
        return tokenMap.get(appId);
    }

    public Map<Integer, TokenInfo> getAllTokens() {
        return new HashMap<>(tokenMap);
    }

    private void saveTokensToFile() {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(TOKEN_FILE), tokenMap);
            log.info("tokens 已保存到 {}", TOKEN_FILE);
        } catch (IOException e) {
            log.error("保存 tokens 文件失败", e);
        }
    }

    private void loadTokensFromFile() {
        File file = new File(TOKEN_FILE);
        if (!file.exists()) {
            return;
        }
        try {
            Map<Integer, TokenInfo> loaded = objectMapper.readValue(file,
                    new TypeReference<>() {
                    });
            tokenMap.putAll(loaded);
        } catch (IOException e) {
            log.error("读取 tokens 文件失败", e);
        }
    }
}
