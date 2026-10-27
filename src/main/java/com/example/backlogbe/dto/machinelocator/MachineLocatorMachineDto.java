package com.example.backlogbe.dto.machinelocator;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

// Máy theo schema FE (LayoutData.m[code]).
//
// b   : [[x, y, w, h], ...]
// p   : (Polygon[] | null)[], cùng độ dài với b
// fit : ('closed' | 'tight' | 'hull' | 'rect' | null)[], cùng độ dài với b
// u   : uncertain
// src : 'manual' | 'cad' | 'old'
// r   : cần review (không có key khi null)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MachineLocatorMachineDto(
		List<List<Double>> b,
		JsonNode p,
		List<String> fit,
		Boolean u,
		String src,
		Boolean r
) {
}
