package com.github.dbadia.sqrl.server.data;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.dbadia.sqrl.server.data.SqrlUsedNutToken;

/**
 * A helper class that assists with the cleanup of used SQRL "nut" tokens
 * 
 * 
 * @see SqrlUsedNutToken
 * @author Dave Badia
 *
 */
public class SqrlTokenExpiryCleanupTask {
	private final Logger logger = LoggerFactory.getLogger(SqrlTokenExpiryCleanupTask.class);
	private static final SqrlTokenExpiryCleanupTask INSTANCE = new SqrlTokenExpiryCleanupTask();

	private final ScheduledExecutorService sqrlTokenExpiryCleanupScheduler;

	public static void runInBackground() {
		// Nothing to do, loading this class with kick off the scheduler
	}

	private SqrlTokenExpiryCleanupTask() {
		sqrlTokenExpiryCleanupScheduler = Executors.newScheduledThreadPool(1);
		logger.info("SqrlTokenExpiryCleanupTask scheduled in background");
		sqrlTokenExpiryCleanupScheduler.scheduleAtFixedRate(new Runnable() {
			@Override
			public void run() {
				// TODO:
				// final DataAccessObject datastore = DataAccessObject.getInstance();
				try {
					// datastore.cleanupUsedSqrlTokens(); TODO
				} catch (final Exception e) {
					logger.error("Error running datastore.cleanupUsedSqrlTokens", e);
				}
			}
		}, 0, 1, TimeUnit.HOURS);
	}
}