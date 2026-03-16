package com.xhs.controller;

import com.xhs.config.XhsConfig;
import com.xhs.model.TokenInfo;
import com.xhs.service.TokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@RestController
@RequiredArgsConstructor
public class OAuthController {

    private final XhsConfig xhsConfig;
    private final TokenService tokenService;

    /**
     * 首页 - 显示所有账号的授权链接和状态
     */
    @GetMapping("/")
    public Map<String, Object> index() {
        Map<String, Object> result = new HashMap<>();

        String scope = "[\"report_service\",\"ad_query\",\"account_manage\",\"ad_manage\"]";
        List<Map<String, Object>> accountList = new ArrayList<>();

        for (XhsConfig.AccountConfig account : xhsConfig.getAccounts()) {
            Map<String, Object> info = new HashMap<>();
            info.put("name", account.getName());
            info.put("appId", account.getAppId());

            // state 里带上 appId，回调时用于识别账号
            String state = "appId_" + account.getAppId();
            String authUrl = String.format("%s?appId=%d&scope=%s&redirectUri=%s&state=%s",
                    xhsConfig.getAuthUrl(),
                    account.getAppId(),
                    scope,
                    URLEncoder.encode(xhsConfig.getRedirectUri(), StandardCharsets.UTF_8),
                    state);
            info.put("authUrl", authUrl);

            TokenInfo token = tokenService.getToken(account.getAppId());
            if (token != null) {
                info.put("status", "已授权");
                info.put("advertiserId", token.getAdvertiserId());
                info.put("obtainedAt", token.getObtainedAt());
            } else {
                info.put("status", "未授权");
            }
            accountList.add(info);
        }

        result.put("accounts", accountList);
        return result;
    }

    /**
     * OAuth 回调 - 通过 state 中的 appId 识别是哪个账号
     */
    @GetMapping("/callback")
    public Map<String, Object> callback(
            @RequestParam("auth_code") String authCode,
            @RequestParam(value = "state", required = false) String state) {

        log.info("收到 OAuth 回调, auth_code={}, state={}", authCode, state);

        // 从 state 中解析 appId
        Integer appId = parseAppIdFromState(state);
        if (appId == null) {
            return Map.of("success", false, "message", "无法从 state 中识别账号，state=" + state);
        }

        Map<String, Object> result = new HashMap<>();
        try {
            TokenInfo tokenInfo = tokenService.fetchToken(appId, authCode);
            result.put("success", true);
            result.put("message", "授权成功！");
            result.put("appId", appId);
            result.put("advertiserId", tokenInfo.getAdvertiserId());
            result.put("approvalAdvertisers", tokenInfo.getApprovalAdvertisers());
        } catch (IOException e) {
            log.error("获取 token 失败, appId={}", appId, e);
            result.put("success", false);
            result.put("message", "授权失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 手动刷新指定账号的 token
     */
    @PostMapping("/token/refresh/{appId}")
    public Map<String, Object> refresh(@PathVariable Integer appId) {
        Map<String, Object> result = new HashMap<>();
        try {
            TokenInfo tokenInfo = tokenService.refreshToken(appId);
            result.put("success", true);
            result.put("message", "刷新成功");
            result.put("appId", appId);
            result.put("advertiserId", tokenInfo.getAdvertiserId());
        } catch (Exception e) {
            log.error("刷新 token 失败, appId={}", appId, e);
            result.put("success", false);
            result.put("message", "刷新失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 刷新所有账号的 token
     */
    @PostMapping("/token/refresh-all")
    public Map<String, Object> refreshAll() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> results = new ArrayList<>();

        for (XhsConfig.AccountConfig account : xhsConfig.getAccounts()) {
            Map<String, Object> item = new HashMap<>();
            item.put("appId", account.getAppId());
            item.put("name", account.getName());
            try {
                tokenService.refreshToken(account.getAppId());
                item.put("success", true);
            } catch (Exception e) {
                item.put("success", false);
                item.put("error", e.getMessage());
            }
            results.add(item);
        }

        result.put("results", results);
        return result;
    }

    /**
     * 查看所有账号的 token 状态
     */
    @GetMapping("/token/status")
    public Map<String, Object> tokenStatus() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();

        for (XhsConfig.AccountConfig account : xhsConfig.getAccounts()) {
            Map<String, Object> item = new HashMap<>();
            item.put("appId", account.getAppId());
            item.put("name", account.getName());

            TokenInfo token = tokenService.getToken(account.getAppId());
            if (token != null) {
                item.put("authorized", true);
                item.put("advertiserId", token.getAdvertiserId());
                item.put("obtainedAt", token.getObtainedAt());
                item.put("accessTokenExpiresIn", token.getAccessTokenExpiresIn());
                item.put("refreshTokenExpiresIn", token.getRefreshTokenExpiresIn());
            } else {
                item.put("authorized", false);
            }
            list.add(item);
        }

        result.put("accounts", list);
        return result;
    }

    private Integer parseAppIdFromState(String state) {
        if (state == null || !state.startsWith("appId_")) {
            return null;
        }
        try {
            return Integer.parseInt(state.substring("appId_".length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}