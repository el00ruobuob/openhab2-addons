/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.zoneminder.internal.handler;

import static org.openhab.binding.zoneminder.internal.ZoneMinderConstants.CHANNEL_ONLINE;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.ThingStatusInfo;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.State;
import org.eclipse.smarthome.core.types.UnDefType;
import org.openhab.binding.zoneminder.internal.RefreshPriority;
import org.openhab.binding.zoneminder.internal.config.ZoneMinderThingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import name.eskildsen.zoneminder.IZoneMinderConnectionHandler;

/**
 * The {@link ZoneMinderBaseThingHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Martin S. Eskildsen - Initial contribution
 */
public abstract class ZoneMinderBaseThingHandler extends BaseThingHandler implements IZoneMinderHandler {
    private final Logger logger = LoggerFactory.getLogger(ZoneMinderBaseThingHandler.class);

    private final ReentrantLock lockRefresh = new ReentrantLock();
    private final ReentrantLock lockAlarm = new ReentrantLock();

    /** Bridge Handler for the Thing. */
    public ZoneMinderServerBridgeHandler zoneMinderBridgeHandler;

    /** This refresh status. */
    private AtomicInteger thingRefresh = new AtomicInteger(1);

    private long alarmTimeoutTimestamp = 0;

    /** ZoneMinder ConnectionHandler */
    private IZoneMinderConnectionHandler zoneMinderConnection;

    /** Configuration from openHAB */
    protected ZoneMinderThingConfig configuration;

    private RefreshPriority refreshPriority = RefreshPriority.PRIORITY_NORMAL;

    protected boolean isThingOnline() {
        try {
            if ((thing.getStatus() == ThingStatus.ONLINE) && getZoneMinderBridgeHandler().isOnline()) {
                return true;
            }
        } catch (Exception ex) {
            logger.error("{}: context='isThingOnline' Exception occurred", getLogIdentifier(), ex);
        }
        return false;
    }

    public RefreshPriority getThingRefreshPriority() {
        return refreshPriority;
    }

    public ZoneMinderBaseThingHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        updateStatus(ThingStatus.OFFLINE);
    }

    @Override
    public void dispose() {
    }

    protected boolean isConnected() {
        if ((getThing().getStatus() != ThingStatus.ONLINE) || (zoneMinderConnection == null)
                || (getZoneMinderBridgeHandler() == null)) {
            return false;
        }
        return getZoneMinderBridgeHandler().isConnected();
    }

    protected IZoneMinderConnectionHandler aquireSession() {
        Bridge bridge = getBridge();
        if (bridge != null) {
            ZoneMinderServerBridgeHandler handler = ((ZoneMinderServerBridgeHandler) bridge.getHandler());
            if (handler != null) {
                zoneMinderConnection = handler.getZoneMinderConnection();
                return handler.getZoneMinderConnection();
            }
        }
        return null;
    }

    protected void releaseSession() {
        // TODO Why is this commented out?
        // lockSession.unlock();
    }

    protected boolean forceStartAlarmRefresh() {
        lockAlarm.lock();
        try {
            if (refreshPriority != RefreshPriority.PRIORITY_ALARM) {
                logger.debug("{}: context='startAlarmRefresh' Starting ALARM refresh...", getLogIdentifier());
                refreshPriority = RefreshPriority.PRIORITY_ALARM;
                // If already activated and called again, it is the
                // event from ZoneMinder that was triggered from openHAB
                alarmTimeoutTimestamp = -1;
            }
        } finally {
            lockAlarm.unlock();
        }
        return true;
    }

    /**
     * Method to start a priority data refresh task.
     */
    protected boolean startAlarmRefresh(long timeout) {
        lockAlarm.lock();
        try {
            if (refreshPriority != RefreshPriority.PRIORITY_ALARM) {
                logger.debug("{}: context='startAlarmRefresh' Starting ALARM refresh...", getLogIdentifier());
                refreshPriority = RefreshPriority.PRIORITY_ALARM;
                alarmTimeoutTimestamp = System.currentTimeMillis() + timeout * 1000;
            }
        } finally {
            lockAlarm.unlock();
        }
        return true;
    }

    protected void tryStopAlarmRefresh() {
        lockAlarm.lock();
        try {
            if ((alarmTimeoutTimestamp == -1) || (refreshPriority != RefreshPriority.PRIORITY_ALARM)) {
                return;
            }
            if (alarmTimeoutTimestamp < System.currentTimeMillis()) {
                logger.debug("{}: context='tryStopAlarmRefresh' - Alarm refresh timed out - stopping alarm refresh ...",
                        getLogIdentifier());
                refreshPriority = RefreshPriority.PRIORITY_NORMAL;

                alarmTimeoutTimestamp = 0;
            }
        } finally {
            lockAlarm.unlock();
        }
    }

    /**
     * Method to stop the data Refresh task.
     */
    protected void forceStopAlarmRefresh() {
        lockAlarm.lock();
        try {
            if (refreshPriority == RefreshPriority.PRIORITY_ALARM) {
                logger.debug("{}: context='forceStopAlarmRefresh' Stopping ALARM refresh...", getLogIdentifier());
                refreshPriority = RefreshPriority.PRIORITY_NORMAL;
                alarmTimeoutTimestamp = 0;
            }
        } finally {
            lockAlarm.unlock();
        }
    }

    protected void onThingStatusChanged(ThingStatus thingStatus) {
    }

    // Get ChannelUID from ChannelId
    public ChannelUID getChannelUIDFromChannelId(@NonNull String id) {
        Channel ch = thing.getChannel(id);
        if (ch == null) {
            return null;
        }
        return ch.getUID();
    }

    protected abstract void onFetchData(RefreshPriority refreshPriority);

    // Refresh Thing Handler
    public final void refreshThing(RefreshPriority refreshPriority) {
        String context = "refreshThing";

        if (!isConnected()) {
            return;
        }
        // TODO Use RentrantLock isLocked() method
        // boolean isLocked = false;
        try {
            if (refreshPriority == RefreshPriority.PRIORITY_ALARM) {
                if (!lockRefresh.tryLock()) {
                    logger.info("{}: context='{}' Can't get refresh lock for '{}' - skipping refreshThing",
                            getLogIdentifier(), context, getThing().getUID());
                    // isLocked = false;
                    return;
                }
            } else {
                lockRefresh.lock();
            }
            // isLocked = true;

            if (getZoneMinderBridgeHandler() != null) {
                onFetchData(refreshPriority);
            } else {
                logger.info("{}: context='{}' BridgeHandler not accessible for '{}', skipping refreshThing",
                        getLogIdentifier(), context, getThing().getUID());
            }

            if (!isThingRefreshed()) {
                logger.trace("{}: context='{}' Refreshing channels for '{}'", getLogIdentifier(), context,
                        getThing().getUID());
                for (Channel channel : getThing().getChannels()) {
                    updateChannel(channel.getUID());
                }
                this.channelRefreshDone();
            }
        } finally {
            // if (isLocked) {
            if (lockRefresh.isLocked()) {
                lockRefresh.unlock();
            }
        }
    }

    @Override
    public void updateChannel(ChannelUID channel) {
        switch (channel.getId()) {
            case CHANNEL_ONLINE:
                updateState(channel, getChannelBoolAsOnOffState(isThingOnline()));
                break;
            default:
                logger.debug("{}: updateChannel() unknown channel in base class '{}', must be handled in super class.",
                        getLogIdentifier(), channel.getId());
        }
    }

    // Utility method to get the bridge handler
    // TODO Is this synchronization needed?
    // public synchronized ZoneMinderServerBridgeHandler getZoneMinderBridgeHandler() {
    public ZoneMinderServerBridgeHandler getZoneMinderBridgeHandler() {
        if (zoneMinderBridgeHandler == null) {
            Bridge bridge = getBridge();
            if (bridge == null) {
                logger.info("{}: context='getZoneMinderBridgeHandler' Unable to get bridge!", getLogIdentifier());
                return null;
            }
            ThingHandler handler = bridge.getHandler();
            if (handler != null && handler instanceof ZoneMinderServerBridgeHandler) {
                zoneMinderBridgeHandler = (ZoneMinderServerBridgeHandler) handler;
            }
        }
        return zoneMinderBridgeHandler;
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        super.bridgeStatusChanged(bridgeStatusInfo);
    }

    @Override
    public void onBridgeConnected(ZoneMinderServerBridgeHandler bridge, IZoneMinderConnectionHandler connection) {
        zoneMinderConnection = connection;
    }

    @Override
    public void onBridgeDisconnected(ZoneMinderServerBridgeHandler bridge) {
        zoneMinderConnection = null;
    }

    // Get Channel by ChannelUID
    public Channel getChannel(ChannelUID channelUID) {
        Channel channel = null;
        for (Channel ch : getThing().getChannels()) {
            if (channelUID == ch.getUID()) {
                channel = ch;
                break;
            }
        }
        return channel;
    }

    /*
     * Get Thing Handler refresh status
     */
    public boolean isThingRefreshed() {
        return (thingRefresh.get() > 0) ? false : true;
    }

    /*
     * Set Thing Handler refresh status.
     */
    public void requestChannelRefresh() {
        thingRefresh.incrementAndGet();
    }

    public void channelRefreshDone() {
        if (thingRefresh.decrementAndGet() < 0) {
            thingRefresh.set(0);
        }
    }

    protected abstract String getZoneMinderThingType();

    // Helper to get a value from configuration as a String
    protected String getConfigValueAsString(String configKey) {
        return (String) getConfigValue(configKey);
    }

    // Helper to get a value from configuration as a Integer
    protected Integer getConfigValueAsInteger(String configKey) {
        return (Integer) getConfigValue(configKey);
    }

    // Helper to get a value from configuration as a BigDecimal
    protected BigDecimal getConfigValueAsBigDecimal(String configKey) {
        return (BigDecimal) getConfigValue(configKey);
    }

    private Object getConfigValue(String configKey) {
        return getThing().getConfiguration().getProperties().get(configKey);
    }

    protected State getChannelStringAsStringState(String channelValue) {
        State state = UnDefType.UNDEF;
        if (isConnected()) {
            state = new StringType(channelValue);
        }
        return state;
    }

    protected State getChannelBoolAsOnOffState(boolean value) {
        State state = UnDefType.UNDEF;
        if (isConnected()) {
            state = value ? OnOffType.ON : OnOffType.OFF;
        }
        return state;
    }

    @Override
    public abstract String getLogIdentifier();

    protected void updateThingStatus(ThingStatus thingStatus, ThingStatusDetail statusDetail,
            String statusDescription) {
        ThingStatusInfo curStatusInfo = thing.getStatusInfo();
        String curDescription = ((curStatusInfo.getDescription() == null) ? "" : curStatusInfo.getDescription());

        // Status changed
        if (!curStatusInfo.getStatus().equals(thingStatus) || !curStatusInfo.getStatusDetail().equals(statusDetail)
                || !statusDescription.equals(curDescription)) {
            // Update Status correspondingly
            if ((thingStatus == ThingStatus.OFFLINE) && (statusDetail != ThingStatusDetail.NONE)) {
                logger.debug("{}: Thing status changed from '{}' to '{}' (DetailedStatus='{}', Description='{}')",
                        getLogIdentifier(), thing.getStatus(), thingStatus, statusDetail, statusDescription);
                updateStatus(thingStatus, statusDetail, statusDescription);
            } else {
                logger.debug("{}: Thing status changed from '{}' to '{}'", getLogIdentifier(), thing.getStatus(),
                        thingStatus);
                updateStatus(thingStatus);
            }
            onThingStatusChanged(thingStatus);
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // No commands are handled in the base class
    }
}
