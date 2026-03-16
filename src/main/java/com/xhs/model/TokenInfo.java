package com.xhs.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TokenInfo {

    @JsonProperty("access_token")
    private String accessToken;

    @JsonProperty("access_token_expires_in")
    private Long accessTokenExpiresIn;

    @JsonProperty("refresh_token")
    private String refreshToken;

    @JsonProperty("refresh_token_expires_in")
    private Long refreshTokenExpiresIn;

    @JsonProperty("advertiser_id")
    private Long advertiserId;

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("role_type")
    private Integer roleType;

    @JsonProperty("approval_role_type")
    private Integer approvalRoleType;

    @JsonProperty("platform_type")
    private Integer platformType;

    @JsonProperty("approval_advertisers")
    private List<Advertiser> approvalAdvertisers;

    /** 对应的 appId */
    private Integer appId;

    /** 本地记录的获取时间，用于判断是否过期 */
    private LocalDateTime obtainedAt;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Advertiser {
        @JsonProperty("advertiser_id")
        private Long advertiserId;

        @JsonProperty("advertiser_name")
        private String advertiserName;
    }
}