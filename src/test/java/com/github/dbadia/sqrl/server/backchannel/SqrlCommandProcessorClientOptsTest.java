package com.github.dbadia.sqrl.server.backchannel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.github.dbadia.sqrl.server.SqrlConfig;
import com.github.dbadia.sqrl.server.SqrlPersistence;
import com.github.dbadia.sqrl.server.TCUtil;
import com.github.dbadia.sqrl.server.enums.SqrlIdentityFlag;
import com.github.dbadia.sqrl.server.enums.SqrlInternalUserState;
import com.github.dbadia.sqrl.server.enums.SqrlRequestCommand;
import com.github.dbadia.sqrl.server.enums.SqrlRequestOpt;

public class SqrlCommandProcessorClientOptsTest {
	final String					correlator	= "abc";
	private SqrlConfig				config;
	private SqrlPersistence			sqrlPersistence;

	@Before
	public void setUp() throws Exception {
		sqrlPersistence = TCUtil.createEmptySqrlPersistence();
		config = TCUtil.buildTestSqrlConfig();
		config.setNutValidityInSeconds(Integer.MAX_VALUE);
	}

	@After
	public void tearDown() throws Exception {
		sqrlPersistence.closeCommit();
	}

	@Test
	public void testOptCps_NotSupported() throws Throwable {
		// Setup
		final String idk = "m470Fb8O3XY8xAqlN2pCL0SokqPYNazwdc5sT6SLnUM";
		TCUtil.setupIdk(idk, correlator, "123");

		final SqrlClientRequest sqrlRequest = TCBackchannelUtil.buildMockSqrlRequest(idk, SqrlRequestCommand.IDENT,
				correlator, false,
				SqrlRequestOpt.cps);

		// Execute
		final SqrlClientRequestProcessor processor = new SqrlClientRequestProcessor(sqrlRequest, sqrlPersistence);
		final SqrlInternalUserState sqrlInternalUserState = processor.processClientCommand();

		// Validate
		assertEquals(SqrlInternalUserState.IDK_EXISTS, sqrlInternalUserState);
		// Ensure nothing got disabled
		assertTrue(sqrlPersistence.fetchSqrlFlagForIdentity(idk, SqrlIdentityFlag.SQRL_AUTH_ENABLED));
		assertTrue(sqrlPersistence.doesSqrlIdentityExistByIdk(idk));
	}

	@Test
	public void testOptHardlock_NotSupported() throws Throwable {
		// Setup
		final String idk = "m470Fb8O3XY8xAqlN2pCL0SokqPYNazwdc5sT6SLnUM";
		TCUtil.setupIdk(idk, correlator, "123");

		final SqrlClientRequest sqrlRequest = TCBackchannelUtil.buildMockSqrlRequest(idk, SqrlRequestCommand.IDENT,
				correlator, false,
				SqrlRequestOpt.hardlock);

		// Execute
		final SqrlClientRequestProcessor processor = new SqrlClientRequestProcessor(sqrlRequest, sqrlPersistence);
		final SqrlInternalUserState sqrlInternalUserState = processor.processClientCommand();

		// Validate
		assertEquals(SqrlInternalUserState.IDK_EXISTS, sqrlInternalUserState);
		// Ensure nothing got disabled
		assertTrue(sqrlPersistence.fetchSqrlFlagForIdentity(idk, SqrlIdentityFlag.SQRL_AUTH_ENABLED));
		assertTrue(sqrlPersistence.doesSqrlIdentityExistByIdk(idk));
	}

	@Test
	public void testOptSqrlOnly_NotSupported() throws Throwable {
		// Setup
		final String idk = "m470Fb8O3XY8xAqlN2pCL0SokqPYNazwdc5sT6SLnUM";
		TCUtil.setupIdk(idk, correlator, "123");

		final SqrlClientRequest sqrlRequest = TCBackchannelUtil.buildMockSqrlRequest(idk, SqrlRequestCommand.IDENT,
				correlator, false,
				SqrlRequestOpt.sqrlonly);

		// Execute
		final SqrlClientRequestProcessor processor = new SqrlClientRequestProcessor(sqrlRequest, sqrlPersistence);
		final SqrlInternalUserState sqrlInternalUserState = processor.processClientCommand();

		// Validate
		assertEquals(SqrlInternalUserState.IDK_EXISTS, sqrlInternalUserState);
		// Ensure nothing got disabled
		assertTrue(sqrlPersistence.fetchSqrlFlagForIdentity(idk, SqrlIdentityFlag.SQRL_AUTH_ENABLED));
		assertTrue(sqrlPersistence.doesSqrlIdentityExistByIdk(idk));
	}
}
