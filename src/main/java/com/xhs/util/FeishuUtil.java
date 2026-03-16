package com.xhs.util;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class FeishuUtil {

    private static final String FEISHU_API_BASE = "https://open.feishu.cn/open-apis";

    public static Map<String, String> buildAuthHeaders(String token) {
        Map<String, String> headers = new HashMap<>();
        if (StrUtil.isNotBlank(token)) {
            headers.put("Authorization", "Bearer " + token);
        }
        headers.put("Content-Type", "application/json; charset=utf-8");
        return headers;
    }

    public static JSONObject post(String url, String token, Object body) {
        return executeRequest(HttpRequest.post(url)
                .headerMap(buildAuthHeaders(token), true)
                .body(JSONUtil.toJsonStr(body)));
    }

    public static JSONObject put(String url, String token, Object body) {
        return executeRequest(HttpRequest.put(url)
                .headerMap(buildAuthHeaders(token), true)
                .body(JSONUtil.toJsonStr(body)));
    }

    private static JSONObject executeRequest(HttpRequest request) {
        try {
            HttpResponse response = request.execute();
            JSONObject jsonResponse = JSONUtil.parseObj(response.body());

            Integer code = jsonResponse.getInt("code");
            if (code != null && code != 0) {
                String msg = jsonResponse.getStr("msg", "未知错误");
                throw new FeishuApiException(code, msg);
            }

            return jsonResponse;
        } catch (FeishuApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("请求飞书 API 异常", e);
            throw new RuntimeException("请求飞书 API 失败: " + e.getMessage(), e);
        }
    }

    public static String buildApiUrl(String path) {
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return FEISHU_API_BASE + path;
    }

    @Getter
    public static class FeishuApiException extends RuntimeException {
        private final Integer code;

        public FeishuApiException(Integer code, String message) {
            super(message);
            this.code = code;
        }
    }
}