package com.example.backlogbe.config;

import com.example.backlogbe.auth.RequirePermInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// Đăng ký RequirePermInterceptor cho /api/** (trừ login / register / logout / health)
@Configuration
@RequiredArgsConstructor
public class AuthWebConfig implements WebMvcConfigurer {

	private final RequirePermInterceptor requirePermInterceptor;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(requirePermInterceptor)
				.addPathPatterns("/api/**")
				.excludePathPatterns(
						"/api/production-control/auth/login",
						"/api/production-control/auth/register",
						"/api/production-control/auth/logout",
						"/api/health"
				);
	}
}
