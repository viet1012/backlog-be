package com.example.backlogbe.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.net.InetAddress;

@Service
public class ClientMachineService {

	public String getClientIp(
			HttpServletRequest request
	) {

		// Reverse proxy chu?n
		String forwardedFor =
				request.getHeader("X-Forwarded-For");

		if (
				forwardedFor != null
						&& !forwardedFor.isBlank()
						&& !"unknown".equalsIgnoreCase(forwardedFor)
		) {
			return forwardedFor
					.split(",")[0]
					.trim();
		}

		String realIp =
				request.getHeader("X-Real-IP");

		if (
				realIp != null
						&& !realIp.isBlank()
						&& !"unknown".equalsIgnoreCase(realIp)
		) {
			return realIp.trim();
		}

		return request.getRemoteAddr();
	}


	public String resolveMachineName(
			String ip
	) {

		if (ip == null || ip.isBlank()) {
			return "UNKNOWN";
		}

		try {
			InetAddress address =
					InetAddress.getByName(ip);

			String hostName =
					address.getCanonicalHostName();

			if (
					hostName == null
							|| hostName.isBlank()
							|| hostName.equals(ip)
			) {
				return ip;
			}

			int dotIndex =
					hostName.indexOf('.');

			if (dotIndex > 0) {
				hostName =
						hostName.substring(
								0,
								dotIndex
						);
			}

			return hostName.toUpperCase();

		} catch (Exception e) {
			return ip;
		}
	}
}