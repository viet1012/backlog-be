package com.example.backlogbe.service.pdf2cad;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

// =========================================================
// Chuyển tiếp request sang server pdf2cad.
//
// - relay       : file / JSON lớn (DXF, zip, png, preview) → stream thẳng về trình duyệt, không giữ trong RAM.
// - relayJson   : JSON nhỏ cần đọc (tạo job, bot) → đọc hết, chép về trình duyệt, trả JsonNode cho controller
//                 (ghi chủ job, nhật ký).
// - relayUpload : POST /jobs – stream nguyên body multipart của trình duyệt sang pdf2cad (không parse,
//                 không file tạm), giới hạn pdf2cad.max-upload-mb.
// Path biến (job id, tên file) đi qua URI template → được encode, không chèn được "../".
// Gửi kèm X-Forwarded-For (IP người dùng) và X-Employee-Id (MSNV đã xác thực) để pdf2cad ghi log nếu cần.
// =========================================================
@Service
public class Pdf2cadProxyService {

	public static final String MSG_TOO_LARGE_PREFIX = "Tổng dung lượng vượt ";

	/** header của pdf2cad trả lại cho trình duyệt */
	private static final List<String> PASS_HEADERS = List.of(
			HttpHeaders.CONTENT_TYPE,
			HttpHeaders.CONTENT_DISPOSITION,
			HttpHeaders.CONTENT_LENGTH,
			HttpHeaders.CACHE_CONTROL
	);

	/** JSON nhỏ (tạo job, bot) – quá mức này coi như lỗi */
	private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;

	public record Relayed(int status, @Nullable JsonNode json) {
	}

	/** Người gọi đã xác thực: MSNV + IP máy */
	public record Caller(String employeeId, String ip) {
	}

	private final RestClient client;
	private final ObjectMapper objectMapper;
	private final Pdf2cadProperties properties;

	public Pdf2cadProxyService(Pdf2cadProperties properties, ObjectMapper objectMapper) {
		this.properties = properties;
		this.objectMapper = objectMapper;

		// HTTP/1.1: uvicorn (pdf2cad) không hỗ trợ nâng cấp h2c
		HttpClient http = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSec()))
				.build();

		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
		factory.setReadTimeout(Duration.ofSeconds(properties.getReadTimeoutSec()));

		this.client = RestClient.builder()
				.baseUrl(properties.getBaseUrl().replaceAll("/+$", ""))
				.requestFactory(factory)
				.build();
	}

	// =========================================================
	// STREAM
	// =========================================================

	public int relay(
			HttpMethod method,
			String uriTemplate,
			Map<String, ?> uriVariables,
			Caller caller,
			HttpServletResponse out
	) {
		Integer status = request(method, uriTemplate, uriVariables, caller).exchange((req, res) -> {
			copyStatusAndHeaders(res, out);
			try (InputStream in = res.getBody(); OutputStream os = out.getOutputStream()) {
				StreamUtils.copy(in, os);
			}
			return res.getStatusCode().value();
		});
		return status != null ? status : HttpStatus.BAD_GATEWAY.value();
	}

	// =========================================================
	// JSON NHỎ
	// =========================================================

	public Relayed relayJson(
			HttpMethod method,
			String uriTemplate,
			Caller caller,
			@Nullable String jsonBody,
			HttpServletResponse out
	) {
		RestClient.RequestBodySpec spec = request(method, uriTemplate, Map.of(), caller);
		if (jsonBody != null) {
			spec.contentType(MediaType.APPLICATION_JSON).body(jsonBody);
		}
		return spec.exchange((req, res) -> writeBuffered(res, out));
	}

	// =========================================================
	// UPLOAD (POST /jobs)
	// =========================================================

	public Relayed relayUpload(HttpServletRequest request, Caller caller, HttpServletResponse out) throws IOException {

		String contentType = request.getContentType();
		if (contentType == null || !contentType.toLowerCase().startsWith(MediaType.MULTIPART_FORM_DATA_VALUE)) {
			throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Chưa có file");
		}

		long max = properties.maxUploadBytes();
		long declared = request.getContentLengthLong();
		if (declared > max) {
			throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, tooLargeMessage());
		}

		InputStream body = new LimitedInputStream(request.getInputStream(), max);

		RestClient.RequestBodySpec spec = request(HttpMethod.POST, "/api/jobs", Map.of(), caller)
				.contentType(MediaType.parseMediaType(contentType));
		if (declared >= 0) {
			spec.headers((h) -> h.setContentLength(declared));
		}

		return spec
				.body((OutputStream os) -> StreamUtils.copy(body, os))
				.exchange((req, res) -> writeBuffered(res, out));
	}

	public String tooLargeMessage() {
		return MSG_TOO_LARGE_PREFIX + properties.getMaxUploadMb() + " MB";
	}

	// =========================================================
	// HELPER
	// =========================================================

	private RestClient.RequestBodySpec request(
			HttpMethod method,
			String uriTemplate,
			Map<String, ?> uriVariables,
			Caller caller
	) {
		return client
				.method(method)
				.uri(uriTemplate, uriVariables)
				.header("X-Forwarded-For", caller.ip())
				.header("X-Employee-Id", caller.employeeId());
	}

	private Relayed writeBuffered(ClientHttpResponse res, HttpServletResponse out) throws IOException {
		byte[] bytes;
		try (InputStream in = res.getBody()) {
			bytes = in.readNBytes(MAX_JSON_BYTES + 1);
		}
		if (bytes.length > MAX_JSON_BYTES) {
			throw new IOException("pdf2cad response too large");
		}

		int status = res.getStatusCode().value();
		copyStatusAndHeaders(res, out);
		out.setContentLength(bytes.length);
		try (OutputStream os = out.getOutputStream()) {
			os.write(bytes);
		}

		JsonNode json = null;
		if (bytes.length > 0) {
			try {
				json = objectMapper.readTree(bytes);
			} catch (IOException ignored) {
				// không phải JSON – vẫn trả nguyên cho trình duyệt
			}
		}
		return new Relayed(status, json);
	}

	private static void copyStatusAndHeaders(ClientHttpResponse res, HttpServletResponse out) throws IOException {
		out.setStatus(res.getStatusCode().value());
		HttpHeaders headers = res.getHeaders();
		for (String name : PASS_HEADERS) {
			String value = headers.getFirst(name);
			if (value != null) out.setHeader(name, value);
		}
	}

	/** Đếm byte khi đọc; vượt giới hạn → UploadTooLargeException (khi trình duyệt không gửi Content-Length) */
	static final class LimitedInputStream extends FilterInputStream {

		private final long max;
		private long count;

		LimitedInputStream(InputStream in, long max) {
			super(in);
			this.max = max;
		}

		@Override
		public int read() throws IOException {
			int b = super.read();
			if (b >= 0) add(1);
			return b;
		}

		@Override
		public int read(byte[] buf, int off, int len) throws IOException {
			int n = super.read(buf, off, len);
			if (n > 0) add(n);
			return n;
		}

		private void add(long n) throws IOException {
			count += n;
			if (count > max) throw new UploadTooLargeException();
		}
	}

	public static final class UploadTooLargeException extends IOException {
		UploadTooLargeException() {
			super("upload exceeds pdf2cad.max-upload-mb");
		}
	}
}
