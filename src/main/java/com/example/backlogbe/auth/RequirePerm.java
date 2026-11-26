package com.example.backlogbe.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// =========================================================
// Quyền cần có để gọi endpoint (AppCode = 'PCW'), kiểm tra bởi RequirePermInterceptor
// theo vw_F2_ProductionControl_EmpPerm (Feature / ActionCode = '*' khớp mọi giá trị).
// Đặt ở method; đặt ở class = mặc định cho mọi method chưa có annotation riêng.
// Giá trị: hằng trong Perm.
// =========================================================
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequirePerm {

	String feature();

	String action();
}
