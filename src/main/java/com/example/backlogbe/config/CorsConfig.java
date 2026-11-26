package com.example.backlogbe.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

// =========================================================
// CORS GLOBAL (thay cho @CrossOrigin("*") ở từng controller)
//
// app.cors.allowed-origins: danh sách origin FE, cách nhau dấu phẩy (nhận cả pattern, vd http://192.168.0.*:5173).
// Trống -> chặn mọi request cross-origin (same-origin vẫn chạy).
// Dùng CorsFilter (chạy trước ProductionControlAuthFilter) để cả response 401 / 403 cũng có header CORS –
// FE đọc được status thay vì lỗi CORS.
// Header X-Employee-Id chỉ cần trong giai đoạn auth.enforce=false; bỏ khi đã bật enforce.
// =========================================================
@Slf4j
@Configuration
public class CorsConfig {

	@Bean
	public FilterRegistrationBean<CorsFilter> corsFilterRegistration(
			@Value("${app.cors.allowed-origins:}") String allowedOrigins
	) {

		List<String> origins = Arrays.stream(allowedOrigins.split(","))
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.toList();

		if (origins.isEmpty()) {
			log.warn("CORS: app.cors.allowed-origins is empty – cross-origin requests are blocked.");
		}

		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOriginPatterns(origins);
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Employee-Id"));
		// Tên file khi export Excel
		config.setExposedHeaders(List.of("Content-Disposition"));
		config.setAllowCredentials(false);
		config.setMaxAge(3600L);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);

		FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);

		return registration;
	}
}
