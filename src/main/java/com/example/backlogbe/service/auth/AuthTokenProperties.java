package com.example.backlogbe.service.auth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// =========================================================
// TOKEN ĐĂNG NHẬP (auth.token.*)
//
// auth.token.secret    : khoá ký HMAC-SHA256, lấy từ biến môi trường AUTH_TOKEN_SECRET (≥ 32 ký tự).
//                        KHÔNG ghi giá trị thật vào file properties / git.
//                        Trống → tự sinh khoá ngẫu nhiên lúc khởi động (token cũ mất hiệu lực khi restart,
//                        và không dùng được khi chạy nhiều instance BE).
// auth.token.ttl-hours : thời hạn token (giờ)
// =========================================================
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "auth.token")
public class AuthTokenProperties {

	private String secret = "";

	private int ttlHours = 12;
}
