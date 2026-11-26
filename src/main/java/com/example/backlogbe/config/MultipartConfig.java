package com.example.backlogbe.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import org.springframework.web.servlet.DispatcherServlet;

// =========================================================
// MULTIPART
//
// Giữ cách parse multipart mặc định của Spring Boot (giới hạn spring.servlet.multipart.*) cho mọi API,
// TRỪ /api/pdf2cad/**: upload bản vẽ (tới pdf2cad.max-upload-mb) được stream nguyên body sang server
// pdf2cad (Pdf2cadProxyService.relayUpload) – không parse, không ghi file tạm, không phải nâng giới hạn chung.
// Lưu ý: controller pdf2cad không được đọc request parameter (Tomcat sẽ parse multipart khi gọi getParameter).
// =========================================================
@Configuration
public class MultipartConfig {

	private static final String PDF2CAD_PREFIX = "/api/pdf2cad/";

	@Bean(name = DispatcherServlet.MULTIPART_RESOLVER_BEAN_NAME)
	public MultipartResolver multipartResolver() {
		return new StandardServletMultipartResolver() {
			@Override
			public boolean isMultipart(HttpServletRequest request) {
				String path = request.getRequestURI().substring(request.getContextPath().length());
				return !path.startsWith(PDF2CAD_PREFIX) && super.isMultipart(request);
			}
		};
	}
}
