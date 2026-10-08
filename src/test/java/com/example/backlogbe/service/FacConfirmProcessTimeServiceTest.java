package com.example.backlogbe.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.backlogbe.dto.facconfirm.FacConfirmProcessTimeRequest;
import com.example.backlogbe.dto.facconfirm.FacConfirmProcessTimeRequest.ProcessTimeItem;
import com.example.backlogbe.repository.facconfirm.FacConfirmEditState;
import com.example.backlogbe.repository.facconfirm.FacConfirmProcessTimeRepository;

class FacConfirmProcessTimeServiceTest {

	private static final LocalDateTime PAST =
			LocalDateTime.now().minusDays(1).withSecond(0).withNano(0);

	private final FacConfirmProcessTimeRepository repository =
			mock(FacConfirmProcessTimeRepository.class);

	private final FacConfirmProcessTimeService service =
			new FacConfirmProcessTimeService(repository);


	@Test
	void rejectsCellAlreadyFilledByBacklog() {

		when(repository.findEditStates(anyList()))
				.thenReturn(Map.of(
						"A1",
						new FacConfirmEditState(
								"A1", false,
								Map.of("toDrill", PAST),
								Map.of()
						)
				));

		IllegalArgumentException ex =
				assertThrows(
						IllegalArgumentException.class,
						() -> service.save(request("toDrill", PAST), "PC")
				);

		assertTrue(ex.getMessage().startsWith("Ô đã có dữ liệu từ Backlog"));
		assertTrue(ex.getMessage().contains("PO A1: To Drill"));
		verify(repository, never()).upsert(anyString(), anyString(), any(), anyString());
	}


	@Test
	void savesValidCell() {

		when(repository.findEditStates(anyList()))
				.thenReturn(Map.of(
						"A1",
						new FacConfirmEditState("A1", false, Map.of(), Map.of())
				));

		when(repository.upsert(anyString(), anyString(), any(), anyString()))
				.thenReturn(1);

		assertEquals(1, service.save(request("toDrill", PAST), "PC"));
		verify(repository).upsert(eq("A1"), eq("To Drill"), eq(PAST), eq("PC_E1"));
	}


	private FacConfirmProcessTimeRequest request(
			String field,
			LocalDateTime value
	) {
		return new FacConfirmProcessTimeRequest(
				"E1",
				List.of(new ProcessTimeItem("A1", field, value)),
				"Rough"
		);
	}
}
