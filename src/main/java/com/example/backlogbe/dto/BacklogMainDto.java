package com.example.backlogbe.dto;

import com.example.backlogbe.utils.SmartDateTimeSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BacklogMainDto(String vbeln, String zglobalCode, String pierAufnr, String aufnr,
                             @JsonSerialize(using = SmartDateTimeSerializer.class) LocalDateTime issueD,
                             @JsonSerialize(using = SmartDateTimeSerializer.class) LocalDateTime productionD,
                             @JsonSerialize(using = SmartDateTimeSerializer.class) LocalDateTime promiseD,
                             @JsonSerialize(using = SmartDateTimeSerializer.class) LocalDateTime exportD,
                             @JsonSerialize(using = SmartDateTimeSerializer.class) LocalDateTime orgDate,
                             @JsonSerialize(using = SmartDateTimeSerializer.class) LocalDateTime msmShip, String pname,
                             String rronyu1, String shipBy, BigDecimal gamng, BigDecimal netpr, String phcd,
                             BigDecimal kwmeng, String rodenk, String loekz, String mtoId, String prtAddcmt1,
                             String prtAddcmt2, Integer prtSts, String div, String ferth, String poSrgConvert,
                             @JsonSerialize(using = SmartDateTimeSerializer.class) LocalDateTime toDrill,
                             @JsonSerialize(using = SmartDateTimeSerializer.class) LocalDateTime toHeat,
                             @JsonSerialize(using = SmartDateTimeSerializer.class) LocalDateTime toPk, String status,
                             String currentProcess, String heatCharge, BigDecimal processQty, BigDecimal z300Qty,
                             BigDecimal pkQty, BigDecimal finalQty,
                             @JsonSerialize(using = SmartDateTimeSerializer.class) LocalDateTime timeSQuenching,
                             @JsonSerialize(using = SmartDateTimeSerializer.class) LocalDateTime timeFHeat,
                             String cProdh, String cKeycontrol1, String cKeycontrol3, String updater,
                             @JsonSerialize(using = SmartDateTimeSerializer.class) LocalDateTime updatedAt) {
}