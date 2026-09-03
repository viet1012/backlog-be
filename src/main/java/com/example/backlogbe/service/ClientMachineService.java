package com.example.backlogbe.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.net.InetAddress;

@Service
public class ClientMachineService {

	public String getClientIp(
			HttpServletRequest request
	) {

		String forwardedFor =
				request.getHeader(
						"X-Forwarded-For"
				);

		if (
				isValidHeader(
						forwardedFor
				)
		) {

			return forwardedFor
					.split(",")[0]
					.trim();
		}


		String realIp =
				request.getHeader(
						"X-Real-IP"
				);

		if (
				isValidHeader(
						realIp
				)
		) {

			return realIp.trim();
		}


		String remoteAddr =
				request.getRemoteAddr();

		if (
				remoteAddr == null
						|| remoteAddr.isBlank()
		) {

			return "UNKNOWN";
		}


		return remoteAddr.trim();
	}


	public String resolveMachineName(
			String ip
	) {

		if (
				ip == null
						|| ip.isBlank()
						|| "UNKNOWN".equalsIgnoreCase(ip)
		) {

			return "UNKNOWN";
		}


		try {

			InetAddress address =
					InetAddress.getByName(
							ip
					);


			// =============================================
			// LOCALHOST
			// =============================================

			if (
					address.isLoopbackAddress()
			) {

				String localHostName =
						InetAddress
								.getLocalHost()
								.getHostName();

				return normalizeHostName(
						localHostName,
						ip
				);
			}


			// =============================================
			// REMOTE CLIENT
			// =============================================

			String hostName =
					address.getHostName();


			// getHostName() không resolve được
			// thì thử canonical
			if (
					isUnresolvedHostName(
							hostName,
							ip
					)
			) {

				hostName =
						address
								.getCanonicalHostName();
			}


			// Vẫn không resolve được
			// thì fallback IP
			if (
					isUnresolvedHostName(
							hostName,
							ip
					)
			) {

				return ip;
			}


			return normalizeHostName(
					hostName,
					ip
			);


		} catch (Exception e) {

			return ip;
		}
	}


	private boolean isValidHeader(
			String value
	) {

		return value != null
				&& !value.isBlank()
				&& !"unknown".equalsIgnoreCase(
				value
		)
				&& !"null".equalsIgnoreCase(
				value
		);
	}


	private boolean isUnresolvedHostName(
			String hostName,
			String ip
	) {

		return hostName == null
				|| hostName.isBlank()
				|| hostName.equalsIgnoreCase(ip);
	}


	private String normalizeHostName(
			String hostName,
			String fallbackIp
	) {

		if (
				hostName == null
						|| hostName.isBlank()
		) {

			return fallbackIp;
		}


		String result =
				hostName.trim();


		// Ví dụ:
		//
		// PC001.company.local
		// ->
		// PC001
		//
		int dotIndex =
				result.indexOf('.');

		if (
				dotIndex > 0
		) {

			result =
					result.substring(
							0,
							dotIndex
					);
		}


		if (
				result.isBlank()
		) {

			return fallbackIp;
		}


		return result.toUpperCase();
	}
}