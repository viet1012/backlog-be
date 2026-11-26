package com.example.backlogbe.service.pdf2cad;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// =========================================================
// Job pdf2cad thuộc về ai (MSNV) – chỉ người tạo mới xem / tải / dừng / dạy bot được job đó.
//
// pdf2cad không có khái niệm người dùng, nên BE ghi lại lúc tạo job (POST /jobs) và lúc bot vẽ
// (POST /bot trả "job"). Lưu trong RAM, hết hạn theo pdf2cad.job-ttl-hours (pdf2cad cũng tự xoá job
// sau --ttl). BE restart → job cũ coi như hết hạn (404, FE báo "Job không còn trên server").
// Job không có trong bảng / của người khác đều trả 404 (không để lộ job có tồn tại).
// =========================================================
@Component
public class Pdf2cadJobOwners {

	public static final String MSG_NOT_FOUND = "Không tìm thấy job (có thể đã hết hạn)";

	private record Owner(String employeeId, Instant expiresAt) {
	}

	private final Map<String, Owner> owners = new ConcurrentHashMap<>();

	private final Duration ttl;

	private volatile Instant nextCleanup = Instant.now();

	public Pdf2cadJobOwners(Pdf2cadProperties properties) {
		this.ttl = Duration.ofHours(Math.max(1, properties.getJobTtlHours()));
	}

	public void register(String jobId, String employeeId) {
		if (jobId == null || jobId.isBlank()) return;
		cleanup();
		owners.put(jobId, new Owner(employeeId, Instant.now().plus(ttl)));
	}

	public void requireOwner(String jobId, String employeeId) {
		Owner owner = owners.get(jobId);

		if (owner == null || owner.expiresAt().isBefore(Instant.now()) || !owner.employeeId().equals(employeeId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, MSG_NOT_FOUND);
		}
	}

	/** dọn job hết hạn, tối đa 1 lần / 10 phút */
	private void cleanup() {
		Instant now = Instant.now();
		if (now.isBefore(nextCleanup)) return;
		nextCleanup = now.plus(Duration.ofMinutes(10));
		owners.values().removeIf((o) -> o.expiresAt().isBefore(now));
	}
}
