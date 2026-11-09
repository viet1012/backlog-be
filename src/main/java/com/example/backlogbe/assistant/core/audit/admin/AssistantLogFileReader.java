package com.example.backlogbe.assistant.core.audit.admin;

import com.example.backlogbe.assistant.core.config.AssistantProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;

// =========================================================
// ĐỌC FILE AUDIT JSONL: <assistant.audit.dir>/assistant-audit-yyyy-MM-dd.jsonl
//
// - Theo khoảng ngày: chỉ mở file của các ngày from .. to+1 (dòng cuối ngày có thể được AsyncAppender ghi
//   sang file ngày sau); lọc theo ngày trong timestamp ở AssistantLogFilter.
// - Dòng JSON lỗi / thiếu timestamp: bỏ qua, chỉ log WARN số dòng (không log nội dung).
// - File đang được logback ghi vẫn đọc được; dòng cuối dở dang (chưa ghi xong) bị bỏ như dòng lỗi.
// =========================================================
@Slf4j
@Component
@RequiredArgsConstructor
class AssistantLogFileReader {

	private static final String PREFIX = "assistant-audit-";
	private static final String SUFFIX = ".jsonl";

	private static final Pattern FILE_NAME = Pattern.compile("^assistant-audit-\\d{4}-\\d{2}-\\d{2}\\.jsonl$");

	private final AssistantProperties properties;
	private final ObjectMapper objectMapper;

	// Gọi consumer cho mọi dòng hợp lệ trong file của các ngày from .. to+1
	void forEach(LocalDate from, LocalDate to, Consumer<AssistantLogEntry> consumer) {

		int badLines = 0;

		for (LocalDate d = from; !d.isAfter(to.plusDays(1)); d = d.plusDays(1)) {

			Path file = dir().resolve(PREFIX + d + SUFFIX);

			if (!Files.isRegularFile(file)) {
				continue;
			}

			badLines += readFile(file, node -> {
				AssistantLogEntry entry = AssistantLogEntry.from(node);
				if (entry == null) {
					return false;
				}
				consumer.accept(entry);
				return true;
			});
		}

		if (badLines > 0) {
			log.warn("Assistant audit admin: skipped {} invalid line(s) in {} .. {}", badLines, from, to.plusDays(1));
		}
	}

	// Bản ghi đầy đủ theo rid: quét file mới nhất trước; trong 1 file lấy dòng cuối cùng khớp (mới nhất)
	Optional<JsonNode> findByRid(String rid) {

		String needle = "\"rid\":\"" + rid + "\"";

		for (Path file : listFilesNewestFirst()) {

			JsonNode[] found = {null};

			readFile(file, needle, node -> {
				if (rid.equals(node.path("rid").asText(null))) {
					found[0] = node;
				}
				return true;
			});

			if (found[0] != null) {
				return Optional.of(found[0]);
			}
		}

		return Optional.empty();
	}

	private Path dir() {
		return Paths.get(properties.getAudit().getDir());
	}

	private List<Path> listFilesNewestFirst() {

		List<Path> files = new ArrayList<>();
		Path dir = dir();

		if (!Files.isDirectory(dir)) {
			return files;
		}

		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, PREFIX + "*" + SUFFIX)) {
			for (Path p : stream) {
				if (FILE_NAME.matcher(p.getFileName().toString()).matches() && Files.isRegularFile(p)) {
					files.add(p);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		// Tên chứa yyyy-MM-dd -> sắp theo tên = theo ngày
		files.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());

		return files;
	}

	private interface LineHandler {
		// false -> dòng không hợp lệ (tính vào số dòng lỗi)
		boolean handle(JsonNode node);
	}

	private int readFile(Path file, LineHandler handler) {
		return readFile(file, null, handler);
	}

	// Trả về số dòng lỗi. requiredText != null: chỉ parse dòng chứa chuỗi này (tìm nhanh theo rid)
	private int readFile(Path file, String requiredText, LineHandler handler) {

		int bad = 0;

		// InputStreamReader thay byte UTF-8 lỗi bằng U+FFFD (Files.newBufferedReader sẽ ném lỗi, hỏng cả request)
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {

			String line;

			while ((line = reader.readLine()) != null) {

				if (line.isBlank() || (requiredText != null && !line.contains(requiredText))) {
					continue;
				}

				JsonNode node;

				try {
					node = objectMapper.readTree(line);
				} catch (IOException e) {
					bad++;
					continue;
				}

				if (!handler.handle(node)) {
					bad++;
				}
			}

		} catch (NoSuchFileException e) {
			// File bị xoá (hết hạn 90 ngày) giữa lúc liệt kê và lúc đọc
			return bad;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		return bad;
	}
}
