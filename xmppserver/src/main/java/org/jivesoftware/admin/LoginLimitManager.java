/*
 * Copyright (C) 2004-2009 Jive Software, 2022-2023 Ignite Realtime Foundation. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jivesoftware.admin;

import org.jivesoftware.openfire.security.SecurityAuditManager;
import org.jivesoftware.util.SystemProperty;
import org.jivesoftware.util.TaskEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles recording admin console login attempts and handling temporary lockouts where necessary.
 *
 * @author Daniel Henninger
 */
public class LoginLimitManager {

    private static final Logger Log = LoggerFactory.getLogger(LoginLimitManager.class);
    private final SecurityAuditManager securityAuditManager;

    // Wrap this guy up so we can mock out the LoginLimitManager class.
    private static class LoginLimitManagerContainer {
        private static LoginLimitManager instance = new LoginLimitManager();
    }

    /**
     * Returns a singleton instance of LoginLimitManager.
     *
     * @return a LoginLimitManager instance.
     */
    public static LoginLimitManager getInstance() {
        return LoginLimitManagerContainer.instance;
    }

    // Max number of attempts per ip address that can be performed in given time frame
    public static final SystemProperty<Long> MAX_ATTEMPTS_PER_IP = SystemProperty.Builder.ofType(Long.class)
        .setKey("adminConsole.maxAttemptsPerIP")
        .setDynamic(true)
        .setDefaultValue(10L)
        .setMinValue(1L)
        .build();

    // Time frame before attempts per ip addresses are reset
    public static final SystemProperty<Duration> PER_IP_ATTEMPT_RESET_INTERVAL = SystemProperty.Builder.ofType(Duration.class)
        .setKey("adminConsole.perIPAttemptResetInterval")
        .setDynamic(false)
        .setChronoUnit(ChronoUnit.MILLIS)
        .setDefaultValue(Duration.ofMinutes(15))
        .setMinValue(Duration.ofMillis(1))
        .build();

    // Max number of attempts per username that can be performed in a given time frame
    public static final SystemProperty<Long> MAX_ATTEMPTS_PER_USERNAME = SystemProperty.Builder.ofType(Long.class)
        .setKey("adminConsole.maxAttemptsPerUsername")
        .setDynamic(true)
        .setDefaultValue(10L)
        .setMinValue(1L)
        .build();

    // Time frame before attempts per username are reset
    public static final SystemProperty<Duration> PER_USERNAME_ATTEMPT_RESET_INTERVAL = SystemProperty.Builder.ofType(Duration.class)
        .setKey("adminConsole.perUsernameAttemptResetInterval")
        .setDynamic(false)
        .setChronoUnit(ChronoUnit.MILLIS)
        .setDefaultValue(Duration.ofMinutes(15))
        .setMinValue(Duration.ofMillis(1))
        .build();

    // Record of attempts per IP address
    private Map<String,Long> attemptsPerIP;
    // Record of attempts per username
    private Map<String,Long> attemptsPerUsername;

    /**
     * Constructs a new login limit manager.
     */
    private LoginLimitManager() {
        this(SecurityAuditManager.getInstance(), TaskEngine.getInstance());
    }

    /**
     * Constructs a new login limit manager. Exposed for test use only.
     */
    LoginLimitManager(final SecurityAuditManager securityAuditManager, final TaskEngine taskEngine) {
        this.securityAuditManager = securityAuditManager;
        // Set up initial maps
        attemptsPerIP = new ConcurrentHashMap<>();
        attemptsPerUsername = new ConcurrentHashMap<>();

        // Set up per username attempt reset task
        taskEngine.scheduleAtFixedRate(new PerUsernameTask(), Duration.ZERO, PER_USERNAME_ATTEMPT_RESET_INTERVAL.getValue());
        // Set up per IP attempt reset task
        taskEngine.scheduleAtFixedRate(new PerIPAddressTask(), Duration.ZERO, PER_IP_ATTEMPT_RESET_INTERVAL.getValue());
    }

    /**
     * Returns true of the entered username or connecting IP address has hit it's attempt limit.
     *
     * @param username Username being checked.
     * @param address IP address that is connecting.
     * @return True if the login attempt limit has been hit.
     */
    public boolean hasHitConnectionLimit(String username, String address) {
        if (attemptsPerIP.get(address) != null && attemptsPerIP.get(address) > MAX_ATTEMPTS_PER_IP.getValue()) {
            return true;
        }
        if (attemptsPerUsername.get(username) != null && attemptsPerUsername.get(username) > MAX_ATTEMPTS_PER_USERNAME.getValue()) {
            return true;
        }
        // No problem then, no limit hit.
        return false;
    }

    /**
     * Records a failed connection attempt.
     *
     * @param username Username being attempted.
     * @param address IP address that is attempting.
     */
    public void recordFailedAttempt(String username, String address) {
        Log.warn("Failed admin console login attempt by "+username+" from "+address);

        Long cnt = (long)0;
        if (attemptsPerIP.get(address) != null) {
            cnt = attemptsPerIP.get(address);
        }
        cnt++;
        attemptsPerIP.put(address, cnt);
        final StringBuilder sb = new StringBuilder();
        if (cnt > MAX_ATTEMPTS_PER_IP.getValue()) {
            Log.warn("Login attempt limit breached for address "+address);
            sb.append("Future login attempts from this address will be temporarily locked out. ");
        }

        cnt = (long)0;
        if (attemptsPerUsername.get(username) != null) {
            cnt = attemptsPerUsername.get(username);
        }
        cnt++;
        attemptsPerUsername.put(username, cnt);
        if (cnt > MAX_ATTEMPTS_PER_USERNAME.getValue()) {
            Log.warn("Login attempt limit breached for username "+username);
            sb.append("Future login attempts for this user will be temporarily locked out. ");
        }

        securityAuditManager.logEvent(username, "Failed admin console login attempt", "A failed login attempt to the admin console was made from address " + address + ". " + sb);

    }

    /**
     * Clears failed login attempts if a success occurs.
     *
     * @param username Username being attempted.
     * @param address IP address that is attempting.
     */
    public void recordSuccessfulAttempt(String username, String address) {
        attemptsPerIP.remove(address);
        attemptsPerUsername.remove(username);
        securityAuditManager.logEvent(username, "Successful admin console login attempt", "The user logged in successfully to the admin console from address " + address + ". ");
    }

    /**
     * Runs at configured interval to clear out attempts per username, thereby wiping lockouts.
     */
    private class PerUsernameTask extends TimerTask {

        /**
         * Wipes failed attempt list for usernames.
         */
        @Override
        public void run() {
            attemptsPerUsername.clear();
        }
    }

    /**
     * Runs at configured interval to clear out attempts per ip address, thereby wiping lockouts.
     */
    private class PerIPAddressTask extends TimerTask {

        /**
         * Wipes failed attempt list for ip addresses.
         */
        @Override
        public void run() {
            attemptsPerIP.clear();
        }
    }

}
