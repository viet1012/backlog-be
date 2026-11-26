package com.example.backlogbe.service.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

// =========================================================
// TOKEN ĐĂNG NHẬP (JWT HS256, không thêm thư viện)
//
// Login cấp token: header {"alg":"HS256","typ":"JWT"}, payload {"sub": MSNV, "iat", "exp"} (giây epoch).
// API cần danh tính thật (hiện tại: /api/pdf2cad/**) nhận "Authorization: Bearer <token>" và gọi verify():
//   chữ ký sai / hết hạn / sai định dạng → Optional.empty().
// Role KHÔNG nằm trong token: API tính lại role từ DB mỗi lần (đổi quyền có hiệu lực ngay).
// =========================================================
@Slf4j
@Service
public class AuthTokenService {

	private static final String ALG = "HmacSHA256";
	private static final int MIN_SECRET_BYTES = 32;
	private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
	private static final Base64.Decoder B64D = Base64.getUrlDecoder();
	private static final String HEADER = B64.encodeToString(
			"{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

	public record IssuedToken(String token, Instant expiresAt) {
	}

	private final byte[] secret;
	private final Duration ttl;
	private final ObjectMapper objectMapper;

	public AuthTokenService(AuthTokenProperties properties, ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
		this.ttl = Duration.ofHours(Math.max(1, properties.getTtlHours()));

		byte[] configured = properties.getSecret() == null
				? new byte[0]
				: properties.getSecret().getBytes(StandardCharsets.UTF_8);

		if (configured.length >= MIN_SECRET_BYTES) {
			this.secret = configured;
		} else {
			this.secret = new byte[MIN_SECRET_BYTES];
			new SecureRandom().nextBytes(this.secret);
			log.warn("auth.token.secret is not set (or shorter than {} bytes): using a random key, "
					+ "tokens become invalid after restart. Set AUTH_TOKEN_SECRET.", MIN_SECRET_BYTES);
		}
	}

	public IssuedToken issue(String employeeId) {
		Instant now = Instant.now();
		Instant exp = now.plus(ttl);

		Map<String, Object> claims = new LinkedHashMap<>();
		claims.put("sub", employeeId);
		claims.put("iat", now.getEpochSecond());
		claims.put("exp", exp.getEpochSecond());

		try {
			String payload = B64.encodeToString(objectMapper.writeValueAsBytes(claims));
			String signingInput = HEADER + "." + payload;
			return new IssuedToken(signingInput + "." + B64.encodeToString(sign(signingInput)), exp);
		} catch (Exception e) {
			throw new IllegalStateException("Cannot issue login token", e);
		}
	}

	/** MSNV trong token nếu token hợp lệ và còn hạn */
	public Optional<String> verify(String token) {
		if (token == null) return Optional.empty();

		String[] parts = token.split("\\.");
		if (parts.length != 3 || !HEADER.equals(parts[0])) return Optional.empty();

		try {
			byte[] expected = sign(parts[0] + "." + parts[1]);
			byte[] actual = B64D.decode(parts[2]);
			if (!MessageDigest.isEqual(expected, actual)) return Optional.empty();

			JsonNode claims = objectMapper.readTree(B64D.decode(parts[1]));
			long exp = claims.path("exp").asLong(0);
			String sub = claims.path("sub").asText("");

			if (sub.isBlank() || Instant.now().getEpochSecond() >= exp) return Optional.empty();
			return Optional.of(sub);
		} catch (IllegalArgumentException | java.io.IOException | GeneralSecurityException e) {
			return Optional.empty();
		}
	}

	/** "Bearer <token>" → MSNV */
	public Optional<String> verifyBearer(String authorization) {
		if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
			return Optional.empty();
		}
		return verify(authorization.substring(7).trim());
	}

	private byte[] sign(String input) throws GeneralSecurityException {
		Mac mac = Mac.getInstance(ALG);
		mac.init(new SecretKeySpec(secret, ALG));
		return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
	}
}
