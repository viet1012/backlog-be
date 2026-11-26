package com.example.backlogbe.controller;

import com.example.backlogbe.auth.CurrentUser;
import com.example.backlogbe.auth.Perm;
import com.example.backlogbe.auth.RequirePerm;
import com.example.backlogbe.service.pdf2cad.Pdf2cadAuditLogger;
import com.example.backlogbe.service.pdf2cad.Pdf2cadJobOwners;
import com.example.backlogbe.service.pdf2cad.Pdf2cadProxyService;
import com.example.backlogbe.service.pdf2cad.Pdf2cadProxyService.Caller;
import com.example.backlogbe.service.pdf2cad.Pdf2cadProxyService.Relayed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/*
 * PDF → CAD (FE: Setting › PDF → CAD, /settings/pdf2cad)
 *
 * Proxy sang server pdf2cad (pdf2cad.base-url):
 *   /api/pdf2cad/health                         -> GET    /api/health
 *   /api/pdf2cad/templates                      -> GET    /api/templates
 *   /api/pdf2cad/bot                            -> POST   /api/bot
 *   /api/pdf2cad/jobs                           -> POST   /api/jobs (multipart, stream nguyên body)
 *   /api/pdf2cad/jobs/{id}                      -> GET | DELETE
 *   /api/pdf2cad/jobs/{id}/files/{name}         -> GET    (DXF)
 *   /api/pdf2cad/jobs/{id}/zip                  -> GET
 *   /api/pdf2cad/jobs/{id}/preview/{name}       -> GET    (JSON hình học)
 *   /api/pdf2cad/jobs/{id}/pdf/{idx}/{page}.png -> GET    (ảnh trang gốc)
 *   /api/pdf2cad/jobs/{id}/learn/{name}         -> POST   ("Dạy bot")
 *
 * Bảo mật (giống các API khác):
 * - Đăng nhập: ProductionControlAuthFilter (Authorization: Bearer <token> session); quyền: @RequirePerm feature PDF2CAD
 *   (V xem, C tạo job / bot / dạy bot, D dừng job, X tải DXF / zip). auth.enforce=true -> 401 / 403;
 *   auth.enforce=false -> không chặn, log would-deny; danh tính = session nếu có, không thì X-Employee-Id.
 * - Job: chỉ người tạo (upload hoặc bot vẽ) mới truy cập được (Pdf2cadJobOwners), khác → 404.
 * - Chỉ các endpoint trên được chuyển tiếp (không mở /api/docs, /api/log… của pdf2cad).
 * - Mỗi thao tác ghi nhật ký riêng (Pdf2cadAuditLogger, trừ hỏi tiến độ).
 * Lỗi: {"detail": "..."} giống pdf2cad (Pdf2cadExceptionHandler).
 *
 * Không dùng @RequestParam ở controller này: multipart của /api/pdf2cad không được Spring parse (MultipartConfig).
 */
@RestController
@RequestMapping("/api/pdf2cad")
@RequiredArgsConstructor
@RequirePerm(feature = Perm.PDF2CAD, action = Perm.VIEW)
public class Pdf2cadController {

	private static final Pattern JOB_ID = Pattern.compile("[0-9a-f]{32}");
	private static final Pattern PAGE_PNG = Pattern.compile("(\\d{1,5})\\.png");
	private static final String ANONYMOUS = "anonymous";

	private final Pdf2cadJobOwners owners;
	private final Pdf2cadProxyService proxy;
	private final Pdf2cadAuditLogger audit;
	private final ObjectMapper objectMapper;


	// =========================================================
	// CHUNG
	// =========================================================

	@GetMapping("/health")
	public void health(
			HttpServletRequest request,
			HttpServletResponse response
	) {
		long t0 = System.nanoTime();
		Caller caller = caller(request);
		int status = proxy.relay(HttpMethod.GET, "/api/health", Map.of(), caller, response);
		audit.write(caller.employeeId(), caller.ip(), "open", null, null, status, t0);
	}

	@GetMapping("/templates")
	public void templates(
			HttpServletRequest request,
			HttpServletResponse response
	) {
		Caller caller = caller(request);
		proxy.relay(HttpMethod.GET, "/api/templates", Map.of(), caller, response);
	}

	@RequirePerm(feature = Perm.PDF2CAD, action = Perm.CREATE)
	@PostMapping(value = "/bot", consumes = MediaType.APPLICATION_JSON_VALUE)
	public void bot(
			@RequestBody String body,
			HttpServletRequest request,
			HttpServletResponse response
	) {
		long t0 = System.nanoTime();
		Caller caller = caller(request);

		JsonNode req = readJson(body);
		Relayed res = proxy.relayJson(HttpMethod.POST, "/api/bot", caller, body, response);

		// bot vẽ xong → job mới thuộc về người chat
		String job = text(res.json(), "job");
		if (job != null && JOB_ID.matcher(job).matches()) owners.register(job, caller.employeeId());

		String message = text(req, "message");
		String detail = "template=" + text(req, "template")
				+ ", messageChars=" + (message != null ? message.length() : 0)
				+ (text(res.json(), "output") != null ? ", output=" + text(res.json(), "output") : "");
		audit.write(caller.employeeId(), caller.ip(), message == null || message.isBlank() ? "bot-open" : "bot-chat",
				job, detail, res.status(), t0);
	}


	// =========================================================
	// JOB CHUYỂN ĐỔI
	// =========================================================

	@RequirePerm(feature = Perm.PDF2CAD, action = Perm.CREATE)
	@PostMapping(value = "/jobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public void createJob(
			HttpServletRequest request,
			HttpServletResponse response
	) throws IOException {
		long t0 = System.nanoTime();
		Caller caller = caller(request);

		Relayed res = proxy.relayUpload(request, caller, response);

		String job = text(res.json(), "id");
		if (res.status() == HttpStatus.CREATED.value() && job != null && JOB_ID.matcher(job).matches()) {
			owners.register(job, caller.employeeId());
		}

		List<String> names = new ArrayList<>();
		if (res.json() != null) {
			res.json().path("files").forEach((f) -> names.add(f.path("name").asText()));
		}
		String detail = String.format("%.1f MB, %d file: %s",
				Math.max(0, request.getContentLengthLong()) / 1048576.0, names.size(), String.join(", ", names));
		audit.write(caller.employeeId(), caller.ip(), "convert", job,
				res.status() < 400 ? detail : detail + " | " + text(res.json(), "detail"), res.status(), t0);
	}

	@GetMapping("/jobs/{id}")
	public void job(
			@PathVariable String id,
			HttpServletRequest request,
			HttpServletResponse response
	) {
		// hỏi tiến độ mỗi giây – không ghi nhật ký
		Caller caller = ownerOf(id, request);
		proxy.relay(HttpMethod.GET, "/api/jobs/{id}", Map.of("id", id), caller, response);
	}

	@RequirePerm(feature = Perm.PDF2CAD, action = Perm.DELETE)
	@DeleteMapping("/jobs/{id}")
	public void deleteJob(
			@PathVariable String id,
			HttpServletRequest request,
			HttpServletResponse response
	) {
		long t0 = System.nanoTime();
		Caller caller = ownerOf(id, request);
		int status = proxy.relay(HttpMethod.DELETE, "/api/jobs/{id}", Map.of("id", id), caller, response);
		audit.write(caller.employeeId(), caller.ip(), "stop", id, null, status, t0);
	}

	@RequirePerm(feature = Perm.PDF2CAD, action = Perm.EXPORT_IMPORT)
	@GetMapping("/jobs/{id}/files/{name}")
	public void file(
			@PathVariable String id,
			@PathVariable String name,
			HttpServletRequest request,
			HttpServletResponse response
	) {
		long t0 = System.nanoTime();
		Caller caller = ownerOf(id, request);
		int status = proxy.relay(HttpMethod.GET, "/api/jobs/{id}/files/{name}", Map.of("id", id, "name", name),
				caller, response);
		audit.write(caller.employeeId(), caller.ip(), "download", id, name, status, t0);
	}

	@RequirePerm(feature = Perm.PDF2CAD, action = Perm.EXPORT_IMPORT)
	@GetMapping("/jobs/{id}/zip")
	public void zip(
			@PathVariable String id,
			HttpServletRequest request,
			HttpServletResponse response
	) {
		long t0 = System.nanoTime();
		Caller caller = ownerOf(id, request);
		int status = proxy.relay(HttpMethod.GET, "/api/jobs/{id}/zip", Map.of("id", id), caller, response);
		audit.write(caller.employeeId(), caller.ip(), "download-zip", id, null, status, t0);
	}

	@GetMapping("/jobs/{id}/preview/{name}")
	public void preview(
			@PathVariable String id,
			@PathVariable String name,
			HttpServletRequest request,
			HttpServletResponse response
	) {
		long t0 = System.nanoTime();
		Caller caller = ownerOf(id, request);
		int status = proxy.relay(HttpMethod.GET, "/api/jobs/{id}/preview/{name}", Map.of("id", id, "name", name),
				caller, response);
		audit.write(caller.employeeId(), caller.ip(), "view-dxf", id, name, status, t0);
	}

	@GetMapping("/jobs/{id}/pdf/{idx}/{pageFile}")
	public void pageImage(
			@PathVariable String id,
			@PathVariable int idx,
			@PathVariable String pageFile,
			HttpServletRequest request,
			HttpServletResponse response
	) {
		long t0 = System.nanoTime();
		Caller caller = ownerOf(id, request);

		var page = PAGE_PNG.matcher(pageFile);
		if (!page.matches() || idx < 0) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không có trang");
		}

		// DPI cố định (FE dùng 110) – không cho client đặt DPI cao làm nặng server pdf2cad
		int status = proxy.relay(HttpMethod.GET, "/api/jobs/{id}/pdf/{idx}/{page}.png?dpi=110",
				Map.of("id", id, "idx", idx, "page", page.group(1)), caller, response);
		audit.write(caller.employeeId(), caller.ip(), "view-original", id, "file#" + idx + " page " + page.group(1),
				status, t0);
	}

	@RequirePerm(feature = Perm.PDF2CAD, action = Perm.CREATE)
	@PostMapping("/jobs/{id}/learn/{name}")
	public void learn(
			@PathVariable String id,
			@PathVariable String name,
			HttpServletRequest request,
			HttpServletResponse response
	) {
		long t0 = System.nanoTime();
		Caller caller = ownerOf(id, request);
		int status = proxy.relay(HttpMethod.POST, "/api/jobs/{id}/learn/{name}", Map.of("id", id, "name", name),
				caller, response);
		audit.write(caller.employeeId(), caller.ip(), "learn", id, name, status, t0);
	}


	// =========================================================
	// HELPER
	// =========================================================

	/** Mã NV do ProductionControlAuthFilter gắn (session; auth.enforce=false thì có thể là X-Employee-Id), không có -> anonymous */
	private Caller caller(HttpServletRequest request) {
		String employeeId = CurrentUser.empCodeOrNull(request);
		return new Caller(employeeId != null ? employeeId : ANONYMOUS, request.getRemoteAddr());
	}

	/** là người tạo job (đăng nhập + quyền do filter / RequirePermInterceptor kiểm) */
	private Caller ownerOf(String id, HttpServletRequest request) {
		Caller caller = caller(request);
		if (id == null || !JOB_ID.matcher(id).matches()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, Pdf2cadJobOwners.MSG_NOT_FOUND);
		}
		owners.requireOwner(id, caller.employeeId());
		return caller;
	}

	private JsonNode readJson(String body) {
		try {
			return objectMapper.readTree(body);
		} catch (IOException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid request body");
		}
	}

	private static String text(JsonNode node, String field) {
		if (node == null) return null;
		JsonNode v = node.get(field);
		return v == null || v.isNull() ? null : v.asText();
	}
}
