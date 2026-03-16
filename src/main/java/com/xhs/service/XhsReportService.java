package com.xhs.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class XhsReportService {

    private final TokenService tokenService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient = new OkHttpClient();

    private static final String REPORT_URL = "https://adapi.xiaohongshu.com/api/open/jg/data/report/realtime/campaign";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    /**
     * 获取指定广告主当天有效计划的实时数据
     * 返回列表，每个元素是一个计划的数据（包含计划基本信息 + 报表指标）
     */
    public List<CampaignReport> fetchActiveCampaigns(Integer appId, Long advertiserId, String date) throws IOException {
        String accessToken = tokenService.getAccessToken(appId);

        List<CampaignReport> allCampaigns = new ArrayList<>();
        int pageNum = 1;
        int pageSize = 100;

        while (true) {
            Map<String, Object> body = new HashMap<>();
            body.put("advertiser_id", advertiserId);
            body.put("start_date", date);
            body.put("end_date", date);
            body.put("campaign_filter_state", 1); // 只要有效计划
            body.put("page_num", pageNum);
            body.put("page_size", pageSize);

            String json = objectMapper.writeValueAsString(body);
            log.info("请求 XHS 计划报表, advertiserId={}, date={}, page={}", advertiserId, date, pageNum);

            Request request = new Request.Builder()
                    .url(REPORT_URL)
                    .header("Access-Token", accessToken)
                    .post(RequestBody.create(json, JSON_TYPE))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : null;
                log.info("XHS 计划报表响应 (advertiserId={}, page={}): {}", advertiserId, pageNum, responseBody);

                JsonNode root = objectMapper.readTree(responseBody);
                if (root.path("code").asInt() != 0) {
                    throw new IOException("获取计划报表失败 (advertiserId=" + advertiserId + "): " + root.path("msg").asText());
                }

                JsonNode campaignDtos = root.path("data").path("campaign_dtos");
                if (campaignDtos.isMissingNode() || !campaignDtos.isArray() || campaignDtos.isEmpty()) {
                    break;
                }

                for (JsonNode campaignNode : campaignDtos) {
                    CampaignReport report = parseCampaign(campaignNode);
                    if (report != null) {
                        allCampaigns.add(report);
                    }
                }

                // 检查是否还有下一页
                JsonNode page = root.path("data").path("page");
                int totalCount = page.path("total_count").asInt(0);
                if (pageNum * pageSize >= totalCount) {
                    break;
                }
                pageNum++;
            }
        }

        log.info("广告主 {} 共获取 {} 个有效计划", advertiserId, allCampaigns.size());
        return allCampaigns;
    }

    private CampaignReport parseCampaign(JsonNode campaignNode) {
        JsonNode baseCampaign = campaignNode.path("base_campaign_dto");
        JsonNode data = campaignNode.path("data");

        if (baseCampaign.isMissingNode()) {
            return null;
        }

        CampaignReport report = new CampaignReport();
        report.setCampaignId(baseCampaign.path("campaign_id").asLong());
        report.setCampaignName(baseCampaign.path("campaign_name").asText(""));

        // 提取报表指标
        Map<String, String> metrics = new HashMap<>();
        if (!data.isMissingNode()) {
            extractField(data, metrics, "fee");
            extractField(data, metrics, "impression");
            extractField(data, metrics, "click");
            extractField(data, metrics, "ctr");
            extractField(data, metrics, "acp");
            extractField(data, metrics, "cpm");
            extractField(data, metrics, "interaction");
            extractField(data, metrics, "cpi");
            extractField(data, metrics, "message_consult");
            extractField(data, metrics, "initiative_message");
            extractField(data, metrics, "msg_leads_num");
            extractField(data, metrics, "message_consult_cpl");
            extractField(data, metrics, "initiative_message_cpl");
            extractField(data, metrics, "msg_leads_cost");
        }
        report.setMetrics(metrics);

        return report;
    }

    private void extractField(JsonNode node, Map<String, String> result, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (!value.isMissingNode() && !value.isNull()) {
            result.put(fieldName, value.asText());
        }
    }

    /**
     * 计划报表数据
     */
    @lombok.Data
    public static class CampaignReport {
        private Long campaignId;
        private String campaignName;
        private Map<String, String> metrics;
    }
}