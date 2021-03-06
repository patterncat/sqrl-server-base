package com.github.dbadia.sqrl.server;

import static com.github.dbadia.sqrl.server.enums.SqrlInternalUserState.DISABLED;
import static com.github.dbadia.sqrl.server.enums.SqrlInternalUserState.IDK_EXISTS;
import static com.github.dbadia.sqrl.server.enums.SqrlInternalUserState.PIDK_EXISTS;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.dbadia.sqrl.server.backchannel.SqrlClientReply;
import com.github.dbadia.sqrl.server.backchannel.SqrlClientRequest;
import com.github.dbadia.sqrl.server.backchannel.SqrlClientRequestLoggingUtil;
import com.github.dbadia.sqrl.server.backchannel.SqrlClientRequestProcessor;
import com.github.dbadia.sqrl.server.backchannel.SqrlNutToken;
import com.github.dbadia.sqrl.server.backchannel.SqrlNutTokenUtil;
import com.github.dbadia.sqrl.server.backchannel.SqrlTif;
import com.github.dbadia.sqrl.server.backchannel.SqrlTif.SqrlTifBuilder;
import com.github.dbadia.sqrl.server.backchannel.SqrlTifFlag;
import com.github.dbadia.sqrl.server.enums.SqrlAuthenticationStatus;
import com.github.dbadia.sqrl.server.enums.SqrlClientParam;
import com.github.dbadia.sqrl.server.enums.SqrlInternalUserState;
import com.github.dbadia.sqrl.server.enums.SqrlRequestCommand;
import com.github.dbadia.sqrl.server.enums.SqrlRequestOpt;
import com.github.dbadia.sqrl.server.enums.SqrlServerSideKey;
import com.github.dbadia.sqrl.server.exception.SqrlClientRequestProcessingException;
import com.github.dbadia.sqrl.server.exception.SqrlException;
import com.github.dbadia.sqrl.server.exception.SqrlInvalidRequestException;
import com.github.dbadia.sqrl.server.exception.SqrlPersistenceException;
import com.github.dbadia.sqrl.server.persistence.SqrlAutoCloseablePersistence;
import com.github.dbadia.sqrl.server.persistence.SqrlCorrelator;
import com.github.dbadia.sqrl.server.persistence.SqrlIdentity;
import com.github.dbadia.sqrl.server.persistence.SqrlPersistenceCleanupTask;
import com.github.dbadia.sqrl.server.util.SqrlConstants;
import com.github.dbadia.sqrl.server.util.SqrlServiceExecutor;
import com.github.dbadia.sqrl.server.util.SqrlUtil;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

/**
 * The core SQRL class which processes all SQRL requests and generates the appropriates responses. Registers itself for
 * servlet context destroy events so background tasks can be stopped
 *
 * @author Dave Badia
 *
 */
public class SqrlServerOperations {
	private static final Logger logger = LoggerFactory.getLogger(SqrlServerOperations.class);

	private static final AtomicInteger COUNTER = new AtomicInteger(0);

	static final long MAX_TIMESTAMP = Integer.toUnsignedLong(-1) * 1000L;

	private static SqrlServiceExecutor sqrlServiceExecutor;

	private final SqrlPersistenceFactory	persistenceFactory;
	private final SqrlConfigOperations		configOperations;
	private final SqrlConfig				config;
	private final SqrlAuthStateMonitor		authStateMonitor;
	private final boolean					cpsEnabled;

	/**
	 * Initializes the operations class with the given config, defaulting to the built in JPA persisentce provider.
	 *
	 * @param sqrlPersistence
	 *            the persistence to be used for storing and retreiving SQRL data
	 * @param config
	 *            the SQRL settings to be used
	 * @throws SqrlException
	 */
	public SqrlServerOperations(final SqrlConfig config) {
		if (config == null) {
			throw new IllegalArgumentException("SqrlConfig object must not be null", null);
		}
		this.configOperations = new SqrlConfigOperations(config);
		this.persistenceFactory = configOperations.getSqrlPersistenceFactory();
		this.config = config;
		final String classname = config.getClientAuthStateUpdaterClass();
		if (config.getClientAuthStateUpdaterClass() == null) {
			logger.warn("No ClientAuthStateUpdaterClass is set, auto client status refresh is disabled");
			authStateMonitor = null;
		} else {
			try {
				logger.info("Instantiating ClientAuthStateUpdater class of {}", classname);
				@SuppressWarnings("rawtypes")
				final Class clazz = Class.forName(classname);
				final Constructor<SqrlClientAuthStateUpdater> constructor = clazz.getConstructor();
				if (constructor == null) {
					throw new IllegalStateException("SQRL AuthStateUpdaterClass of " + classname
							+ " must have a no-arg constructor, but does not");
				}
				final Object object = constructor.newInstance();
				if (!(object instanceof SqrlClientAuthStateUpdater)) {
					throw new IllegalStateException("SQRL AuthStateUpdaterClass of " + classname
							+ " was not an instance of ClientAuthStateUpdater");
				}
				final SqrlClientAuthStateUpdater clientAuthStateUpdater = (SqrlClientAuthStateUpdater) object;
				authStateMonitor = new SqrlAuthStateMonitor(config, this, clientAuthStateUpdater);
				clientAuthStateUpdater.initSqrl(config, authStateMonitor);
				final long intervalInMilis = config.getAuthSyncCheckInMillis();
				logger.info("Client auth state task scheduled to run every {} ms", intervalInMilis);
				sqrlServiceExecutor.scheduleAtFixedRate(authStateMonitor, intervalInMilis, intervalInMilis,
						TimeUnit.MILLISECONDS);
			} catch (final ReflectiveOperationException e) {
				throw new IllegalStateException("SQRL: Error instantiating ClientAuthStateUpdaterClass of " + classname,
						e);
			}
		}

		// DB Cleanup task
		final int cleanupIntervalInMinutes = config.getCleanupTaskExecInMinutes();
		if (cleanupIntervalInMinutes == -1) {
			logger.warn("Auto cleanup is disabled since config.getCleanupTaskExecInMinutes() == -1");
		} else if (cleanupIntervalInMinutes <= 0) {
			throw new IllegalArgumentException("config.getCleanupTaskExecInMinutes() must be -1 or > 0");
		} else {
			logger.info("Persistence cleanup task registered to run every {} minutes", cleanupIntervalInMinutes);
			final SqrlPersistenceCleanupTask cleanupRunnable = new SqrlPersistenceCleanupTask(persistenceFactory);
			sqrlServiceExecutor.scheduleAtFixedRate(cleanupRunnable, 0, cleanupIntervalInMinutes,
					TimeUnit.MINUTES);
		}
		// TODOCPS: set cpsEnabled
		cpsEnabled = false;
	}

	/**
	 * Poor mans dependency injection. Can't use CDI since we want to support lightweight JEE servers like tomcat
	 *
	 * @param sqrlServiceExecutor
	 */
	public static void setExecutor(final SqrlServiceExecutor sqrlServiceExecutor) {
		SqrlServerOperations.sqrlServiceExecutor = sqrlServiceExecutor;
	}

	/**
	 * Called to generate the data the server needs to display to allow a user to authenticate via SQRL
	 *
	 * @param request
	 *            the servlet request
	 * @param response
	 * @param userInetAddress
	 *            the IP address of the users browser
	 * @param qrCodeSizeInPixels
	 *            the size (in pixels) that the generated QR code will be
	 * @return the data the server needs to display to allow a user to authenticate via SQRL
	 * @throws SqrlException
	 *             if an error occurs
	 */
	public SqrlAuthPageData prepareSqrlAuthPageData(final HttpServletRequest request, final HttpServletResponse response,
			final InetAddress userInetAddress, final int qrCodeSizeInPixels) throws SqrlException {
		final URI backchannelUri = configOperations.getBackchannelRequestUrl(request);
		final StringBuilder urlBuf = new StringBuilder(backchannelUri.toString());
		// Now we append the nut and our SFN
		// Even though urlBuf only contains the baseUrl, it's enough for NetUtil.inetAddressToInt
		final SqrlNutToken nut = buildNut(backchannelUri, userInetAddress);
		urlBuf.append("?nut=").append(nut.asSqrlBase64EncryptedNut());
		// Append the SFN
		String sfn = config.getServerFriendlyName();
		if (sfn == null) {
			// Auto compute the SFN from the server URL
			sfn = SqrlUtil.computeSfnFromUrl(request);
			config.setServerFriendlyName(sfn);
		}
		urlBuf.append("&sfn=").append(SqrlUtil.sqrlBase64UrlEncode(sfn));
		try (SqrlAutoCloseablePersistence sqrlPersistence = createSqrlPersistence()) {
			// Append our correlation id
			// Need correlation id to be unique to each Nut, so sha-256 the nut
			final MessageDigest digest = MessageDigest.getInstance("SHA-256");
			final String correlator = SqrlUtil
					.sqrlBase64UrlEncode(digest.digest(nut.asSqrlBase64EncryptedNut().getBytes()));
			urlBuf.append("&").append(SqrlClientParam.cor.toString()).append("=").append(correlator);

			final String url = urlBuf.toString();
			final ByteArrayOutputStream qrBaos = generateQrCode(config, url, qrCodeSizeInPixels);
			// Store the url in the server parrot value so it will be there when the SQRL client makes the request
			final Date expiryTime = new Date(System.currentTimeMillis() + (1000 * config.getNutValidityInSeconds()));
			final SqrlCorrelator sqrlCorrelator = sqrlPersistence.createCorrelator(correlator, expiryTime);
			sqrlCorrelator.getTransientAuthDataTable().put(SqrlConstants.TRANSIENT_NAME_SERVER_PARROT,
					SqrlUtil.sqrlBase64UrlEncode(url));
			sqrlPersistence.closeCommit();
			final String cookieDomain = SqrlUtil.computeCookieDomain(request, config);
			// Correlator outlives the nut so extend the cookie expiry
			final int correlatorCookieAgeInSeconds = config.getNutValidityInSeconds() + 120;
			response.addCookie(SqrlUtil.createOrUpdateCookie(request, cookieDomain, config.getCorrelatorCookieName(),
					correlator, correlatorCookieAgeInSeconds, config));
			response.addCookie(SqrlUtil.createOrUpdateCookie(request, cookieDomain, config.getFirstNutCookieName(),
					nut.asSqrlBase64EncryptedNut(), config.getNutValidityInSeconds(), config));
			return new SqrlAuthPageData(url, qrBaos, nut, correlator);
		} catch (final NoSuchAlgorithmException e) {
			throw new SqrlException(SqrlClientRequestLoggingUtil.getLogHeader() + "Caught exception during correlator create", e);
		}
	}

	private SqrlNutToken buildNut(final URI backchannelUri, final InetAddress userInetAddress) throws SqrlException {
		final int inetInt = SqrlNutTokenUtil.inetAddressToInt(backchannelUri, userInetAddress, config);
		final int randomInt = config.getSecureRandom().nextInt();
		final long timestamp = config.getCurrentTimeMs();
		return new SqrlNutToken(inetInt, configOperations, COUNTER.getAndIncrement(), timestamp, randomInt);
	}


	/**
	 * The backchannel servlet which is accepting requests from SQRL clients should call this method to process the
	 * request
	 *
	 * @param servletRequest
	 *            the servlet request
	 * @param servletResponse
	 *            the servlet response which will be populated accordingly
	 * @throws IOException
	 *             if an IO error occurs
	 */
	public void handleSqrlClientRequest(final HttpServletRequest servletRequest,
			final HttpServletResponse servletResponse) throws IOException {
		SqrlClientRequestLoggingUtil.initLoggingHeader(servletRequest);
		if (logger.isInfoEnabled()) {
			logger.info(SqrlUtil.buildLogMessageForSqrlClientRequest(servletRequest).toString());
		}
		String correlator = "unknown";
		final SqrlTifBuilder tifBuilder = new SqrlTifBuilder();
		SqrlInternalUserState sqrlInternalUserState = null;
		String requestState = "invalid";
		try {
			String logHeader = "";
			SqrlClientRequest sqrlClientRequest = null;
			// Per the spec, SQRL transactions are atomic; so we create our persistence here and only commit after all
			// processing is completed successfully
			SqrlPersistence sqrlPersistence = createSqrlPersistence();
			Exception exception = null;
			try {
				// Get the correlator first. Then, if the request is invalid, we can update the auth page saying so
				correlator = SqrlClientRequest.parseCorrelatorOnly(servletRequest);

				sqrlClientRequest = new SqrlClientRequest(servletRequest, sqrlPersistence, configOperations);
				final SqrlClientRequestProcessor processor = new SqrlClientRequestProcessor(sqrlClientRequest,
						sqrlPersistence);

				logHeader = SqrlClientRequestLoggingUtil.updateLogHeader(
						new StringBuilder(sqrlClientRequest.getNegotiatedSqrlProtocolVersion()).append(" ")
						.append(sqrlClientRequest.getClientCommand()).append(":: ").toString());

				if (checkIfIpsMatch(sqrlClientRequest.getNut(), servletRequest)) {
					tifBuilder.addFlag(SqrlTifFlag.IPS_MATCHED);
				}
				SqrlNutTokenUtil.validateNut(correlator, sqrlClientRequest.getNut(), config, sqrlPersistence);
				sqrlInternalUserState = processor.processClientCommand();
				if (sqrlInternalUserState == IDK_EXISTS) {
					tifBuilder.addFlag(SqrlTifFlag.CURRENT_ID_MATCH);
				} else if (sqrlInternalUserState == PIDK_EXISTS) {
					tifBuilder.addFlag(SqrlTifFlag.PREVIOUS_ID_MATCH);
				}
				servletResponse.setStatus(HttpServletResponse.SC_OK);
				requestState = "OK";
				sqrlPersistence.closeCommit();
			} catch (final SqrlException e) {
				exception = e;
				sqrlPersistence.closeRollback();
				tifBuilder.clearAllFlags().addFlag(SqrlTifFlag.COMMAND_FAILED);
				if (e instanceof SqrlClientRequestProcessingException) {
					tifBuilder.addFlag(((SqrlClientRequestProcessingException) e).getTifToAdd());
					logger.error("{}Received invalid SQRL request: {} of {}", SqrlClientRequestLoggingUtil.getLogHeader(),
							e.getMessage(), SqrlUtil.buildLogMessageForSqrlClientRequest(servletRequest), e);
				} else {
					logger.error("{}Generate exception processing SQRL request: {} of {}",
							SqrlClientRequestLoggingUtil.getLogHeader(), e.getMessage(),
							SqrlUtil.buildLogMessageForSqrlClientRequest(servletRequest), e);
				}
				// The SQRL spec is unclear about HTTP return codes. It mentions returning a 404 for an invalid request
				// but 404 is for page not found. We leave the use of 404 for an actual page not found condition and use
				// 500 here
				servletResponse.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			}

			// We have processed the request, success or failure. Now prep and transmit the reply
			String serverReplyString = ""; // for logging
			sqrlPersistence = persistenceFactory.createSqrlPersistence();
			try {
				final SqrlTif tif = tifBuilder.createTif();
				final boolean isInErrorState = exception != null;
				serverReplyString = buildReply(servletRequest, sqrlClientRequest,
						tif, correlator, sqrlInternalUserState, isInErrorState);
				// Don't use AutoClosable here, we will handle it ourselves
				final SqrlCorrelator sqrlCorrelator = sqrlPersistence.fetchSqrlCorrelatorRequired(correlator);
				if (isInErrorState || sqrlInternalUserState == DISABLED) {
					tifBuilder.addFlag(SqrlTifFlag.COMMAND_FAILED);
					// update the correlator with the proper error state
					SqrlAuthenticationStatus authErrorState = SqrlAuthenticationStatus.ERROR_SQRL_INTERNAL;
					if (exception instanceof SqrlInvalidRequestException) {
						authErrorState = SqrlAuthenticationStatus.ERROR_BAD_REQUEST;
					} else if (sqrlInternalUserState == DISABLED) {
						authErrorState = SqrlAuthenticationStatus.SQRL_USER_DISABLED;
					}
					sqrlCorrelator.setAuthenticationStatus(authErrorState);
					// There should be no further requests so remove the parrot value
					if (sqrlCorrelator.getTransientAuthDataTable()
							.remove(SqrlConstants.TRANSIENT_NAME_SERVER_PARROT) == null) {
						logger.warn("{}Tried to remove server parrot since we are in error state but it doesn't exist",
								SqrlClientRequestLoggingUtil.getLogHeader());
					}
				} else {
					// Store the serverReplyString in the server parrot value so we can validate it on the clients next
					// request
					sqrlCorrelator.getTransientAuthDataTable().put(SqrlConstants.TRANSIENT_NAME_SERVER_PARROT,
							serverReplyString);
				}
				sqrlPersistence.closeCommit();
				transmitReplyToSqrlClient(servletResponse, serverReplyString);
				logger.info("{}Processed sqrl client request replied with tif 0x{}", logHeader, tif.toHexString());
			} catch (final SqrlException e) {
				sqrlPersistence.closeRollback();
				logger.error("{}Error sending SQRL reply with param: {}", logHeader, requestState,
						SqrlUtil.base64UrlDecodeToStringOrErrorMessage(serverReplyString), e);
				logger.debug("{}Request {}, responded with   B64: {}", logHeader, requestState, serverReplyString);
			}
		} finally {
			SqrlClientRequestLoggingUtil.clearLogHeader();
		}
	}

	private String buildReply(final HttpServletRequest servletRequest, final SqrlClientRequest sqrlRequest,
			final SqrlTif tif, final String correlator, final SqrlInternalUserState sqrlInternalUserState,
			final boolean isInErrorState) throws SqrlException {
		final String logHeader = SqrlClientRequestLoggingUtil.getLogHeader();
		final SqrlPersistence sqrlPersistence = createSqrlPersistence();
		try {
			final URI sqrlServerUrl = new URI(servletRequest.getRequestURL().toString());
			final String subsequentRequestPath = configOperations.getSubsequentRequestPath(servletRequest);
			SqrlClientReply reply;
			if (isInErrorState) {
				// Send the error flag as nut and correlator, so if the client mistakenly sends a followup request it be
				// obvious to us
				reply = new SqrlClientReply(SqrlConstants.ERROR, tif, subsequentRequestPath, SqrlConstants.ERROR,
						Collections.emptyMap());
			} else {
				// Nut is one time use, so generate a new one for the reply
				final SqrlNutToken replyNut = buildNut(sqrlServerUrl, determineClientIpAddress(servletRequest, config));

				final Map<String, String> additionalDataTable = buildReplyAdditionalDataTable(sqrlRequest,
						sqrlInternalUserState, sqrlPersistence);
				// Build the final reply object
				reply = new SqrlClientReply(replyNut.asSqrlBase64EncryptedNut(), tif, subsequentRequestPath, correlator,
						additionalDataTable);
			}

			final String serverReplyString = reply.toBase64();
			logger.debug("{}Build serverReplyString: {}", logHeader, serverReplyString);
			sqrlPersistence.closeCommit();
			return serverReplyString;
		} catch (final URISyntaxException e) {
			sqrlPersistence.closeRollback();
			throw new SqrlException(
					SqrlClientRequestLoggingUtil.getLogHeader() + "Error converting servletRequest.getRequestURL() to URI.  "
							+ "servletRequest.getRequestURL()=" + servletRequest.getRequestURL(),
							e);
		}
	}

	private Map<String, String> buildReplyAdditionalDataTable(final SqrlClientRequest sqrlRequest,
			final SqrlInternalUserState sqrlInternalUserState,
			final SqrlPersistence sqrlPersistence) {
		// TreeMap to keep the items in order. Order is required as as the SQRL client will ignore everything
		// after an unrecognized option
		final Map<String, String> additionalDataTable = new TreeMap<>();

		// suk?
		if (shouldIncludeSukInReply(sqrlRequest, sqrlInternalUserState)
				&& (sqrlInternalUserState == IDK_EXISTS || sqrlInternalUserState == PIDK_EXISTS)) {
			final String sukSring = SqrlRequestOpt.suk.toString();
			final String sukValue = sqrlPersistence.fetchSqrlIdentityDataItem(sqrlRequest.getKey(SqrlServerSideKey.idk),
					sukSring);
			if (sukValue != null) {
				additionalDataTable.put(sukSring, sukValue);
			}
		}

		return additionalDataTable;
	}

	private boolean shouldIncludeSukInReply(final SqrlClientRequest sqrlRequest, final SqrlInternalUserState sqrlInternalUserState) {
		return sqrlRequest.getOptList().contains(SqrlRequestOpt.suk)
				// https://www.grc.com/sqrl/semantics.htm says
				// The SQRL specification requires the SQRL server to automatically return the account's matching SUK whenever
				// it is able to anticipate that the client is likely to require it, such as when the server contains a previous
				// identity key, or when the account is disabled
				|| sqrlInternalUserState == DISABLED
				|| (sqrlRequest.getClientCommand() == SqrlRequestCommand.QUERY
				&& sqrlInternalUserState == SqrlInternalUserState.PIDK_EXISTS);
	}

	static InetAddress determineClientIpAddress(final HttpServletRequest servletRequest, final SqrlConfig config)
			throws SqrlException {
		final String[] headersToCheck = config.getIpForwardedForHeaders();
		String ipToParse = null;
		if (headersToCheck != null) {
			for (final String headerToFind : headersToCheck) {
				ipToParse = servletRequest.getHeader(headerToFind);
				if (SqrlUtil.isNotBlank(ipToParse)) {
					break;
				}
			}
		}
		if (SqrlUtil.isBlank(ipToParse)) {
			ipToParse = servletRequest.getRemoteAddr();
		}
		try {
			return InetAddress.getByName(ipToParse);
		} catch (final UnknownHostException e) {
			throw new SqrlException("Caught exception trying to determine clients IP address", e);
		}
	}

	private void transmitReplyToSqrlClient(final HttpServletResponse response, final String serverReplyString)
			throws IOException {
		// Send the reply to the SQRL client
		response.setContentType("text/html;charset=utf-8");
		response.setContentLength(serverReplyString.length());
		try (PrintWriter writer = response.getWriter()) {
			writer.write(serverReplyString);
			writer.flush();
			writer.close();
		}
	}

	private boolean checkIfIpsMatch(final SqrlNutToken nut, final HttpServletRequest servletRequest)
			throws SqrlException {
		final String ipAddressString = servletRequest.getRemoteAddr();
		if (SqrlUtil.isBlank(ipAddressString)) {
			throw new SqrlException(SqrlClientRequestLoggingUtil.getLogHeader() + "No ip address found in sqrl request");
		}
		final InetAddress requesterIpAddress = SqrlUtil.ipStringToInetAddresss(servletRequest.getRemoteAddr());
		return SqrlNutTokenUtil.validateInetAddress(requesterIpAddress, nut.getInetInt(), config);

	}

	private ByteArrayOutputStream generateQrCode(final SqrlConfig config, final String urlToEmbed,
			final int qrCodeSizeInPixels) throws SqrlException {
		try {
			final Map<EncodeHintType, Object> hintMap = new EnumMap<>(EncodeHintType.class);
			hintMap.put(EncodeHintType.CHARACTER_SET, "UTF-8");

			// Now with zxing version 3.2.1 you could change border size (white border size to just 1)
			hintMap.put(EncodeHintType.MARGIN, 1); /* default = 4 */
			hintMap.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);

			final QRCodeWriter qrCodeWriter = new QRCodeWriter();
			final BitMatrix byteMatrix = qrCodeWriter.encode(urlToEmbed, BarcodeFormat.QR_CODE, qrCodeSizeInPixels,
					qrCodeSizeInPixels, hintMap);
			final int crunchifyWidth = byteMatrix.getWidth();
			final BufferedImage image = new BufferedImage(crunchifyWidth, crunchifyWidth, BufferedImage.TYPE_INT_RGB);
			image.createGraphics();

			final Graphics2D graphics = (Graphics2D) image.getGraphics();
			graphics.setColor(Color.WHITE);
			graphics.fillRect(0, 0, crunchifyWidth, crunchifyWidth);
			graphics.setColor(Color.BLACK);

			for (int i = 0; i < crunchifyWidth; i++) {
				for (int j = 0; j < crunchifyWidth; j++) {
					if (byteMatrix.get(i, j)) {
						graphics.fillRect(i, j, 1, 1);
					}
				}
			}
			final ByteArrayOutputStream os = new ByteArrayOutputStream();
			ImageIO.write(image, config.getQrCodeFileType().toString().toLowerCase(), os);
			return os;
		} catch (final IOException | WriterException e) {
			throw new SqrlException("Caught exception during QR code generation", e);
		}
	}

	private SqrlAutoCloseablePersistence createSqrlPersistence() {
		final SqrlPersistence sqrlPersistence = persistenceFactory.createSqrlPersistence();
		return new SqrlAutoCloseablePersistence(sqrlPersistence);
	}

	/**
	 * Called by the web app once authentication is complete to cleanup any cookies set by the SQRL library
	 *
	 * @param request
	 *            the HTTP request
	 * @param response
	 *            the HTTP response
	 */
	public void deleteSqrlAuthCookies(final HttpServletRequest request, final HttpServletResponse response) {
		SqrlUtil.deleteCookies(request, response, config, config.getCorrelatorCookieName(),
				config.getFirstNutCookieName());
	}

	/**
	 * Looks for the SQRL first nut cookie and extracts the time at which it expires
	 *
	 * @param request
	 *            the HTTP request
	 * @return the timestamp when the nut token expires
	 * @throws SqrlException
	 *             if an error occurs
	 */
	public long determineNutExpiry(final HttpServletRequest request) throws SqrlException {
		final String stringValue = SqrlUtil.findCookieValue(request, config.getFirstNutCookieName());
		if (stringValue == null) {
			throw new SqrlException(
					"firstNutCookie with name " + config.getFirstNutCookieName() + " was not found on http request");
		}
		final SqrlNutToken token = new SqrlNutToken(configOperations, stringValue);
		return SqrlNutTokenUtil.computeNutExpiresAt(token, config);
	}

	// @formatter:off
	/*
	 * **************** Persistence layer interface **********************
	 * To isolate the web app from the full persistence API and transaction management, we expose the limited subset here
	 */
	// @formatter:on

	public void updateNativeUserXref(final SqrlIdentity sqrlIdentity, final String nativeUserCrossReference) {
		try (SqrlAutoCloseablePersistence sqrlPersistence = createSqrlPersistence()) {
			sqrlPersistence.updateNativeUserXref(sqrlIdentity.getId(), nativeUserCrossReference);
			sqrlPersistence.closeCommit();
		}
	}

	/**
	 * Checks the request and trys to extract the correlator string from the cookie. Useful for error logging/reporting
	 * when a persistence call is unnecessary
	 *
	 * @return the value or null if the cookie was not present
	 */
	public String extractSqrlCorrelatorStringFromRequestCookie(final HttpServletRequest request) {
		return SqrlUtil.findCookieValue(request, config.getCorrelatorCookieName());
	}

	public SqrlCorrelator fetchSqrlCorrelator(final HttpServletRequest request) {
		final String correlatorString = extractSqrlCorrelatorStringFromRequestCookie(request);
		if (correlatorString == null) {
			return null;
		}
		return fetchSqrlCorrelator(correlatorString);
	}

	public SqrlCorrelator fetchSqrlCorrelator(final String correlatorString) {
		if (SqrlUtil.isBlank(correlatorString)) {
			throw new SqrlPersistenceException("Correlator cookie not found on request");
		}
		try (SqrlAutoCloseablePersistence sqrlPersistence = createSqrlPersistence()) {
			final SqrlCorrelator sqrlCorrelator = sqrlPersistence.fetchSqrlCorrelator(correlatorString);
			sqrlPersistence.closeCommit();
			return sqrlCorrelator;
		}
	}

	/**
	 * Invoked to see if a web app user has a corresponding SQRL identity registered
	 *
	 * @param webAppUserCrossReference
	 *            the username, customer id, or whatever mechanism is used to uniquely identify users in the web app
	 * @return the SQRL identity or null if none exists for this web app user
	 */
	public SqrlIdentity fetchSqrlIdentityByUserXref(final String webAppUserCrossReference) {
		try (SqrlAutoCloseablePersistence sqrlPersistence = createSqrlPersistence()) {
			final SqrlIdentity sqrlIdentity = sqrlPersistence.fetchSqrlIdentityByUserXref(webAppUserCrossReference);
			sqrlPersistence.closeCommit();
			return sqrlIdentity;
		}
	}

	public Map<String, SqrlCorrelator> fetchSqrlCorrelatorsDetached(final Set<String> correlatorStringSet) {
		try (SqrlAutoCloseablePersistence sqrlPersistence = createSqrlPersistence()) {
			final Map<String, SqrlCorrelator> resultTable = sqrlPersistence
					.fetchSqrlCorrelatorsDetached(correlatorStringSet);
			sqrlPersistence.closeCommit();
			return resultTable;
		}
	}

	public Map<String, SqrlAuthenticationStatus> fetchSqrlCorrelatorStatusUpdates(
			final Map<String, SqrlAuthenticationStatus> correlatorToCurrentStatusTable) {
		try (SqrlAutoCloseablePersistence sqrlPersistence = createSqrlPersistence()) {
			final Map<String, SqrlAuthenticationStatus> resultTable = sqrlPersistence
					.fetchSqrlCorrelatorStatusUpdates(correlatorToCurrentStatusTable);
			sqrlPersistence.closeCommit();
			return resultTable;
		}
	}

	public void deleteSqrlCorrelator(final SqrlCorrelator sqrlCorrelator) {
		try (SqrlAutoCloseablePersistence sqrlPersistence = createSqrlPersistence()) {
			sqrlPersistence.deleteSqrlCorrelator(sqrlCorrelator);
			sqrlPersistence.closeCommit();
		}
	}

	/**
	 * Clears SQRL auth one time use data from the browser and database
	 */
	public void cleanSqrlAuthData(final HttpServletRequest request, final HttpServletResponse response) {
		final SqrlCorrelator sqrlCorrelator = fetchSqrlCorrelator(request);
		deleteSqrlAuthCookies(request, response);
		deleteSqrlCorrelator(sqrlCorrelator);
	}
}
