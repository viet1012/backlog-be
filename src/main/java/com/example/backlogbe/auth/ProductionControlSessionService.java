package com.example.backlogbe.auth;

import com.example.backlogbe.repository.auth.ProductionControlSessionRepository;
import com.example.backlogbe.repository.auth.ProductionControlSessionRepository.SessionRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// =========================================================
// SESSION ĐĂNG NHẬP (F2_ProductionControl_Session)
//
// - Token: 32 byte SecureRandom, base64url (không padding). DB chỉ lưu SHA-256 hex – không lưu / log token.
// - Hết hạn: tạo = now + 12h; mỗi lần dùng gia hạn trượt thêm 12h, tối đa CreatedAt + 7 ngày.
// - Gia hạn + LastSeenAt chỉ ghi DB khi lần ghi trước đã cách > 5 phút
//   (ExpiresAt có thể sớm hơn mốc "lần dùng cuối + 12h" tối đa 5 phút).
// - Cache kết quả tra session 60s trong bộ nhớ (mỗi instance BE): token bị revoke từ nơi khác
//   (instance khác / app khác / sửa DB tay) còn dùng được tối đa 60s. Logout trên instance này xóa cache ngay.
// =========================================================
@Service
@RequiredArgsConstructor
public class ProductionControlSessionService {

	static final int IDLE_HOURS = 12;
	static final int MAX_DAYS = 7;

	private static final Duration CACHE_TTL = Duration.ofSeconds(60);
	private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(5);
	private static final int TOKEN_BYTES = 32;
	private static final int MAX_TOKEN_LENGTH = 100;
	private static final int CACHE_CLEANUP_SIZE = 10_000;

	private final ProductionControlSessionRepository repository;

	private final SecureRandom secureRandom = new SecureRandom();

	private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

	public record NewSession(String token, Instant expiresAt) {
	}

	private record Cached(SessionRow session, Instant loadedAt) {
	}

	// =========================================================
	// CREATE
	// =========================================================

	public NewSession create(String empCode) {

		byte[] bytes = new byte[TOKEN_BYTES];
		secureRandom.nextBytes(bytes);

		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

		Instant expiresAt = repository.insert(hash(token), empCode, IDLE_HOURS);

		return new NewSession(token, expiresAt);
	}

	// =========================================================
	// RESOLVE (filter gọi mỗi request)
	// =========================================================

	public Optional<SessionRow> resolve(String token) {

		if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
			return Optional.empty();
		}

		String tokenHash = hash(token);
		Instant now = Instant.now();

		SessionRow session = cachedOrNull(tokenHash, now);

		if (session == null) {
			session = repository.findActive(tokenHash).orElse(null);

			if (session == null) {
				cache.remove(tokenHash);
				return Optional.empty();
			}

			put(tokenHash, session, now);
		}

		Instant lastSeen = session.lastSeenAt() != null ? session.lastSeenAt() : session.createdAt();

		if (lastSeen == null || Duration.between(lastSeen, now).compareTo(TOUCH_INTERVAL) > 0) {

			SessionRow touched = repository.touch(tokenHash, IDLE_HOURS, MAX_DAYS).orElse(null);

			if (touched == null) {
				// Vừa bị revoke / hết hạn
				cache.remove(tokenHash);
				return Optional.empty();
			}

			session = touched;
			put(tokenHash, session, now);
		}

		return Optional.of(session);
	}

	// =========================================================
	// REVOKE (logout)
	// =========================================================

	public void revoke(String token) {

		if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
			return;
		}

		String tokenHash = hash(token);

		repository.revoke(tokenHash);
		cache.remove(tokenHash);
	}

	// =========================================================
	// CACHE
	// =========================================================

	private SessionRow cachedOrNull(String tokenHash, Instant now) {

		Cached cached = cache.get(tokenHash);

		if (cached == null) {
			return null;
		}

		boolean fresh = cached.loadedAt().plus(CACHE_TTL).isAfter(now);
		boolean notExpired = cached.session().expiresAt() != null && cached.session().expiresAt().isAfter(now);

		return fresh && notExpired ? cached.session() : null;
	}

	private void put(String tokenHash, SessionRow session, Instant now) {

		if (cache.size() >= CACHE_CLEANUP_SIZE) {
			cache.entrySet().removeIf(e -> !e.getValue().loadedAt().plus(CACHE_TTL).isAfter(now));
		}

		cache.put(tokenHash, new Cached(session, now));
	}

	// =========================================================
	// HASH
	// =========================================================

	static String hash(String token) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}
}
