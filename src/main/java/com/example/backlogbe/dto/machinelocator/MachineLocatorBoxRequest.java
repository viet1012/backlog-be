package com.example.backlogbe.dto.machinelocator;

import com.fasterxml.jackson.databind.JsonNode;

// 1 box trong request PUT machine / bulk 'set' (format FE, giống dev plugin cũ).
//
// p   : Polygon[] | null, Polygon = [[x, y], ...]
// fit : 'closed' | 'tight' | 'hull' | 'rect' | null
public record MachineLocatorBoxRequest(
		Double x,
		Double y,
		Double w,
		Double h,
		JsonNode p,
		String fit
) {
}
