package com.github.dbadia.sqrl.server.backchannel;

import static com.github.dbadia.sqrl.server.backchannel.SqrlInternalUserState.IDK_EXISTS;
import static com.github.dbadia.sqrl.server.backchannel.SqrlInternalUserState.NONE_EXIST;
import static com.github.dbadia.sqrl.server.backchannel.SqrlInternalUserState.PIDK_EXISTS;

import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.dbadia.sqrl.server.SqrlFlag;
import com.github.dbadia.sqrl.server.SqrlPersistence;
import com.github.dbadia.sqrl.server.SqrlServerOperations;
import com.github.dbadia.sqrl.server.backchannel.SqrlTif.SqrlTifBuilder;
import com.github.dbadia.sqrl.server.exception.SqrlInvalidRequestException;
import com.github.dbadia.sqrl.server.util.SqrlException;
public class SqrlClientRequestProcessor {
	private static final Logger logger = LoggerFactory.getLogger(SqrlServerOperations.class);

	// TODO: change COMMAND_ constants to enum
	private static final String	COMMAND_QUERY	= "query";
	private static final String	COMMAND_IDENT	= "ident";
	private static final String	COMMAND_DISABLE	= "disable";
	private static final String	COMMAND_ENABLE	= "enable";
	private static final String	COMMAND_REMOVE	= "remove";

	private final SqrlClientRequest			sqrlClientRequest;
	private final String			sqrlIdk;
	private final SqrlTifBuilder				tifBuilder;
	private final String					command;
	private final String					logHeader;
	private final String			correlator;
	private final SqrlPersistence	sqrlPersistence;
	private SqrlInternalUserState	sqrlInternalUserState	= NONE_EXIST;

	public SqrlClientRequestProcessor(final SqrlClientRequest sqrlClientRequest,
			final SqrlPersistence sqrlPersistence, final SqrlTifBuilder tifBuilder) {
		super();
		this.logHeader = SqrlLoggingUtil.getLogHeader();
		this.sqrlPersistence = sqrlPersistence;
		this.sqrlClientRequest = sqrlClientRequest;
		this.command = sqrlClientRequest.getClientCommand();
		this.sqrlIdk = sqrlClientRequest.getIdk();
		this.tifBuilder = tifBuilder;
		this.correlator = sqrlClientRequest.getCorrelator();
	}

	/**
	 * Processes the request sent by a SQRL client
	 *
	 * @return true if the idk exists in persistence upon return
	 */
	public SqrlInternalUserState processClientCommand() throws SqrlException {
		sqrlInternalUserState = NONE_EXIST;
		final boolean idkExistsInPersistence = sqrlPersistence.doesSqrlIdentityExistByIdk(sqrlIdk);
		// Set IDK /PIDK Tifs
		if (idkExistsInPersistence) {
			tifBuilder.addFlag(SqrlTif.TIF_CURRENT_ID_MATCH);
			sqrlInternalUserState = IDK_EXISTS;
		} else if (sqrlClientRequest.hasPidk()
				&& sqrlPersistence.doesSqrlIdentityExistByIdk(sqrlClientRequest.getPidk())) {
			sqrlInternalUserState = PIDK_EXISTS;
			tifBuilder.addFlag(SqrlTif.TIF_PREVIOUS_ID_MATCH);
		}

		processCommand();
		if (!COMMAND_REMOVE.equals(command)) {
			processNonSukOptions();
		}
		return sqrlInternalUserState;
	}

	private void updateOptValueAsNeeded(final SqrlFlag flag, final SqrlClientOpt opt) {
		if (opt != null && !opt.isNonQueryOnly() || !COMMAND_QUERY.equals(sqrlClientRequest.getClientCommand())) {
			final boolean clientValue = sqrlClientRequest.getOptList().contains(opt);
			final boolean dbValue = sqrlPersistence.fetchSqrlFlagForIdentity(sqrlIdk, flag);
			if (clientValue != dbValue) { // update it
				logger.debug("{}Updating SQRL flag {} from {} to {}", SqrlLoggingUtil.getLogHeader(), opt, dbValue,
						clientValue);
				sqrlPersistence.setSqrlFlagForIdentity(sqrlIdk, flag, clientValue);
				// TODO: AUDIT, client updated value to clientSet
			}
		}
	}

	private void processNonSukOptions() {
		// Create a copy so we can track which flags we have processed
		final List<SqrlClientOpt> optList = sqrlClientRequest.getOptList();

		// The absence of given flags means they should be disabled. So loop through all known flags and take the
		// appropriate action
		for (final SqrlFlag flag : SqrlFlag.values()) {
			if (flag.hasOptEquivalent()) {
				final SqrlClientOpt opt = flag.getSqrlClientOpt();
				updateOptValueAsNeeded(flag, opt);
				// Remove the item from the list since we have processed it
				if (!optList.remove(flag.getSqrlClientOpt())) {
					logger.warn("{}Tried to remove SqrlClientOpt {} but it wasn't in list {}",
							SqrlLoggingUtil.getLogHeader(), opt, optList);
				}
			}
		}

		// Some flags require special processing and were not handled above
		if (!optList.isEmpty()) {
			logger.warn("{}The SQRL client option(s) are not yet supported by the library: {}",
					SqrlLoggingUtil.getLogHeader(), optList);
		}

	}

	private void processCommand() throws SqrlException {
		if (COMMAND_QUERY.equals(command)) {
			// Nothing to do
		} else if (COMMAND_IDENT.equals(command)) {
			processIdentCommand();
		} else if (COMMAND_ENABLE.equals(command)) {
			final Boolean sqrlEnabledForIdentity = sqrlPersistence.fetchSqrlFlagForIdentity(sqrlIdk,
					SqrlFlag.SQRL_AUTH_ENABLED);
			if (sqrlEnabledForIdentity == null || !sqrlEnabledForIdentity.booleanValue()) {
				if (sqrlClientRequest.containsUrs()) {
					sqrlPersistence.setSqrlFlagForIdentity(sqrlIdk, SqrlFlag.SQRL_AUTH_ENABLED, true);
				} else {
					throw new SqrlInvalidRequestException(SqrlLoggingUtil.getLogHeader()
							+ "Request was to enable SQRL but didn't contain urs signature");
				}
			} else {
				logger.warn("{}Received request to ENABLE but it already is");
			}
		} else if (COMMAND_REMOVE.equals(command)) {
			if (sqrlClientRequest.containsUrs()) {
				sqrlPersistence.deleteSqrlIdentity(sqrlIdk);
			} else {
				throw new SqrlInvalidRequestException(
						SqrlLoggingUtil.getLogHeader() + "Request was to enable SQRL but didn't contain urs signature");
			}
		} else if (COMMAND_DISABLE.equals(command)) {
			// TODO: require usk or something?
			sqrlPersistence.setSqrlFlagForIdentity(sqrlIdk, SqrlFlag.SQRL_AUTH_ENABLED, false);
		} else {
			tifBuilder.addFlag(SqrlTif.TIF_FUNCTIONS_NOT_SUPPORTED);
			tifBuilder.addFlag(SqrlTif.TIF_COMMAND_FAILED);
			throw new SqrlException(SqrlLoggingUtil.getLogHeader() + "Unsupported client command " + command, null);
		}
	}

	private void processIdentCommand() throws SqrlException {
		if (!sqrlInternalUserState.idExistsInPersistence()) {
			// First time seeing this SQRL identity, store it and enable it
			// TODO: remove 2nd arg if it's always null? or pass request.getKeysToBeStored()
			sqrlPersistence.createAndEnableSqrlIdentity(sqrlIdk, Collections.emptyMap());
			sqrlPersistence.storeSqrlDataForSqrlIdentity(sqrlIdk, sqrlClientRequest.getKeysToBeStored());
		}
		final boolean sqrlEnabledForIdentity = sqrlPersistence.fetchSqrlFlagForIdentity(sqrlIdk,
				SqrlFlag.SQRL_AUTH_ENABLED);
		boolean performCpsCheck = false;
		if (!sqrlEnabledForIdentity) {
			// TODO: move this setting to catch
			tifBuilder.addFlag(SqrlTif.TIF_SQRL_DISABLED);
			tifBuilder.addFlag(SqrlTif.TIF_COMMAND_FAILED);
			sqrlInternalUserState = SqrlInternalUserState.DISABLED;
		} else if (sqrlInternalUserState == SqrlInternalUserState.PIDK_EXISTS) {
			sqrlPersistence.updateIdkForSqrlIdentity(sqrlClientRequest.getPidk(), sqrlIdk);
			logger.info("{}User SQRL authenticated, updating idk={} and to replace pidk",
					SqrlLoggingUtil.getLogHeader(), sqrlIdk);
			// TODO AUDIT
			performCpsCheck = true;
		} else if (sqrlInternalUserState == SqrlInternalUserState.IDK_EXISTS) {
			// TODO_AMBIGUOUS: do we really overwrite existing data, or only if they are new?
			sqrlPersistence.storeSqrlDataForSqrlIdentity(sqrlIdk, sqrlClientRequest.getKeysToBeStored());
			sqrlInternalUserState = SqrlInternalUserState.IDK_EXISTS;
			// TODO AUDIT
			logger.info("{}User SQRL authenticated idk={}", SqrlLoggingUtil.getLogHeader(), sqrlIdk);
			performCpsCheck = true;
		}
		if (performCpsCheck) {
			final boolean cpsRequested = sqrlClientRequest.getOptList().contains(SqrlClientOpt.cps);
			final boolean cpsEnabled = false; // TODOCPS: have SSO pass SqrlCpsGenerator instances and check for
			// non-null
			if (cpsRequested && cpsEnabled) {
				// TODO: do it
			} else { // Not requested or not enabled
				if (cpsRequested && !cpsEnabled) {
					logger.info("{} CPS requested but it is not enabled", SqrlLoggingUtil.getLogHeader());
				}
				sqrlPersistence.userAuthenticatedViaSqrl(sqrlIdk, correlator);
			}
		}
	}
}