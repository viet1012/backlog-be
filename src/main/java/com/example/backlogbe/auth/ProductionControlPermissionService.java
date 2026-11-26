package com.example.backlogbe.auth;

import com.example.backlogbe.repository.auth.ProductionControlPermRepository;
import com.example.backlogbe.repository.auth.ProductionControlPermRepository.PermRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// =========================================================
// QUYỀN CỦA NHÂN VIÊN (AppCode = 'PCW') từ vw_F2_ProductionControl_EmpPerm
//
// Cache theo empCode 60s (mỗi instance BE): đổi quyền trong DB có hiệu lực sau tối đa 60s;
// login xóa cache của chính NV đó để roles trả về là mới nhất.
// So khớp không phân biệt hoa thường, bỏ khoảng trắng đầu/cuối; '*' khớp mọi Feature / Action.
// =========================================================
@Service
@RequiredArgsConstructor
public class ProductionControlPermissionService {

	private static final String ANY = "*";
	private static final Duration CACHE_TTL = Duration.ofSeconds(60);
	private static final int CACHE_CLEANUP_SIZE = 10_000;

	private final ProductionControlPermRepository repository;

	private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

	// roles: DISTINCT RoleCode; perms: Feature -> [ActionCode] (giữ nguyên '*' như trong DB)
	public record EmpPerms(List<String> roles, Map<String, List<String>> perms) {

		public boolean allows(String feature, String action) {

			String f = normalize(feature);
			String a = normalize(action);

			for (Map.Entry<String, List<String>> entry : perms.entrySet()) {

				String rowFeature = normalize(entry.getKey());

				if (!ANY.equals(rowFeature) && !rowFeature.equals(f)) {
					continue;
				}

				for (String rowAction : entry.getValue()) {
					String ra = normalize(rowAction);
					if (ANY.equals(ra) || ra.equals(a)) {
						return true;
					}
				}
			}

			return false;
		}
	}

	private record Cached(EmpPerms perms, Instant loadedAt) {
	}

	public EmpPerms get(String empCode) {

		String key = normalize(empCode);
		Instant now = Instant.now();

		Cached cached = cache.get(key);

		if (cached != null && cached.loadedAt().plus(CACHE_TTL).isAfter(now)) {
			return cached.perms();
		}

		EmpPerms perms = load(key);

		if (cache.size() >= CACHE_CLEANUP_SIZE) {
			cache.entrySet().removeIf(e -> !e.getValue().loadedAt().plus(CACHE_TTL).isAfter(now));
		}

		cache.put(key, new Cached(perms, now));

		return perms;
	}

	public boolean has(String empCode, String feature, String action) {
		return empCode != null && !empCode.isBlank() && get(empCode).allows(feature, action);
	}

	public void evict(String empCode) {
		cache.remove(normalize(empCode));
	}

	private EmpPerms load(String empCode) {

		Set<String> roles = new LinkedHashSet<>();
		Map<String, Set<String>> perms = new LinkedHashMap<>();

		for (PermRow row : repository.findByEmp(empCode, Perm.APP_CODE)) {

			if (row.roleCode() != null && !row.roleCode().isBlank()) {
				roles.add(row.roleCode().trim());
			}

			if (row.feature() == null || row.feature().isBlank()
					|| row.actionCode() == null || row.actionCode().isBlank()) {
				continue;
			}

			perms.computeIfAbsent(row.feature().trim(), k -> new LinkedHashSet<>())
					.add(row.actionCode().trim());
		}

		Map<String, List<String>> result = new LinkedHashMap<>();
		perms.forEach((feature, actions) -> result.put(feature, List.copyOf(actions)));

		return new EmpPerms(List.copyOf(new ArrayList<>(roles)), Collections.unmodifiableMap(result));
	}

	private static String normalize(String value) {
		return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
	}
}
