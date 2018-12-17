/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.zoneminder.internal.handler;

import static org.openhab.binding.zoneminder.internal.ZoneMinderConstants.*;

import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.util.Map;
import java.util.Set;

import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.eclipse.smarthome.core.types.State;
import org.eclipse.smarthome.core.types.UnDefType;
import org.openhab.binding.zoneminder.internal.RefreshPriority;
import org.openhab.binding.zoneminder.internal.ZoneMinderProperties;
import org.openhab.binding.zoneminder.internal.config.ZoneMinderThingMonitorConfig;
import org.openhab.binding.zoneminder.internal.state.ChannelStateChangeSubscriber;
import org.openhab.binding.zoneminder.internal.state.MonitorThingState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;

import name.eskildsen.zoneminder.IZoneMinderConnectionHandler;
import name.eskildsen.zoneminder.IZoneMinderEventSubscriber;
import name.eskildsen.zoneminder.IZoneMinderMonitor;
import name.eskildsen.zoneminder.IZoneMinderServer;
import name.eskildsen.zoneminder.ZoneMinderFactory;
import name.eskildsen.zoneminder.api.monitor.ZoneMinderMonitorStatus;
import name.eskildsen.zoneminder.api.telnet.ZoneMinderTriggerEvent;
import name.eskildsen.zoneminder.common.ZoneMinderConfigEnum;
import name.eskildsen.zoneminder.common.ZoneMinderMonitorFunctionEnum;
import name.eskildsen.zoneminder.data.IMonitorDataGeneral;
import name.eskildsen.zoneminder.data.IMonitorDataStillImage;
import name.eskildsen.zoneminder.data.IZoneMinderDaemonStatus;
import name.eskildsen.zoneminder.data.IZoneMinderEventData;
import name.eskildsen.zoneminder.data.ZoneMinderConfig;
import name.eskildsen.zoneminder.exception.ZoneMinderAuthHashNotEnabled;
import name.eskildsen.zoneminder.exception.ZoneMinderAuthenticationException;
import name.eskildsen.zoneminder.exception.ZoneMinderException;
import name.eskildsen.zoneminder.exception.ZoneMinderGeneralException;
import name.eskildsen.zoneminder.exception.ZoneMinderInvalidData;
import name.eskildsen.zoneminder.exception.ZoneMinderResponseException;
import name.eskildsen.zoneminder.internal.ZoneMinderContentResponse;

/**
 * The {@link ZoneMinderThingMonitorHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Martin S. Eskildsen - Initial contribution
 */
public class ZoneMinderThingMonitorHandler extends ZoneMinderBaseThingHandler
        implements ChannelStateChangeSubscriber, IZoneMinderEventSubscriber {
    private final Logger logger = LoggerFactory.getLogger(ZoneMinderThingMonitorHandler.class);

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Sets.newHashSet(THING_TYPE_THING_ZONEMINDER_MONITOR);

    private RefreshPriority forcedPriority = RefreshPriority.DISABLED;

    private String logIdentifier;

    private ZoneMinderThingMonitorConfig config;

    MonitorThingState dataConverter = new MonitorThingState(this);

    private long lastRefreshGeneralData = 0;
    private long lastRefreshStillImage = 0;
    private boolean frameDaemonActive = false;

    public ZoneMinderThingMonitorHandler(Thing thing) {
        super(thing);
        logger.debug("{}: Starting ZoneMinder Server Thing Handler (Thing='{}')", getLogIdentifier(), thing.getUID());
    }

    @Override
    public void initialize() {
        logger.info("{}: context='initialize' Initializing monitor handler", getLogIdentifier());
        this.config = getMonitorConfig();
        super.initialize();

        dataConverter.addChannel(getChannelUIDFromChannelId(CHANNEL_MONITOR_FORCE_ALARM));
        dataConverter.addChannel(getChannelUIDFromChannelId(CHANNEL_MONITOR_EVENT_CAUSE));
        dataConverter.addChannel(getChannelUIDFromChannelId(CHANNEL_MONITOR_RECORD_STATE));
        dataConverter.addChannel(getChannelUIDFromChannelId(CHANNEL_MONITOR_MOTION_EVENT));
        dataConverter.addChannel(getChannelUIDFromChannelId(CHANNEL_MONITOR_DETAILED_STATUS));
        dataConverter.addChannel(getChannelUIDFromChannelId(CHANNEL_MONITOR_ENABLED));
        dataConverter.addChannel(getChannelUIDFromChannelId(CHANNEL_MONITOR_FUNCTION));
        dataConverter.addChannel(getChannelUIDFromChannelId(CHANNEL_MONITOR_EVENT_STATE));
        logger.debug("{}: context='initialize' Monitor handler initialized", getLogIdentifier());
    }

    @Override
    public void dispose() {
        logger.info("{}: context='dispose' Disposing monitor handler", getLogIdentifier());
        ZoneMinderServerBridgeHandler bridge = getZoneMinderBridgeHandler();
        logger.debug("{}: Unsubscribing from Monitor Events: {}", getLogIdentifier(),
                bridge.getThing().getUID().getAsString());
        bridge.unsubscribeMonitorEvents(this);
        logger.debug("{}: context='dispose' Monitor handler disposed", getLogIdentifier());
    }

    @Override
    public String getZoneMinderId() {
        if (config == null) {
            logger.error("{}: Configuration for Thing '{}' is null!.", getLogIdentifier(), getThing().getUID());
            return "";
        }
        return config.getZoneMinderId().toString();
    }

    @Override
    public void onBridgeConnected(ZoneMinderServerBridgeHandler bridge, IZoneMinderConnectionHandler connection) {
        try {
            logger.debug("{}: Bridge '{}' connected", getLogIdentifier(), bridge.getThing().getUID().getAsString());
            super.onBridgeConnected(bridge, connection);

            logger.debug("{}: Add subsription for Monitor Events: {}", getLogIdentifier(),
                    bridge.getThing().getUID().getAsString());
            bridge.subscribeMonitorEvents(this);

            IZoneMinderServer serverProxy = ZoneMinderFactory.getServerProxy(connection);
            ZoneMinderConfig cfg = serverProxy.getConfig(ZoneMinderConfigEnum.ZM_OPT_FRAME_SERVER);
            frameDaemonActive = cfg.getvalueAsBoolean();
        } catch (ZoneMinderGeneralException | ZoneMinderResponseException | ZoneMinderAuthenticationException
                | ZoneMinderInvalidData ex) {
            logger.error("{}: context='onBridgeConnected' error in call to 'getServerProxy' - Message='{}'",
                    getLogIdentifier(), ex.getMessage(), ex.getCause());
        } catch (MalformedURLException e) {
            logger.error("{}: context='onBridgeConnected' error in call to 'getServerProxy' - Message='{}' (Exception)",
                    getLogIdentifier(), e.getMessage(), e.getCause());
        }
    }

    @Override
    public void onBridgeDisconnected(ZoneMinderServerBridgeHandler bridge) {
        try {
            logger.debug("{}: Bridge '{}' disconnected", getLogIdentifier(), bridge.getThing().getUID().getAsString());
            super.onBridgeDisconnected(bridge);
        } catch (Exception ex) {
            logger.error("{}: Exception occurred when calling 'onBridgeDisonencted()'.", getLogIdentifier(), ex);
        }
    }

    @Override
    public void onThingStatusChanged(ThingStatus thingStatus) {
        if (thingStatus == ThingStatus.ONLINE) {
            IZoneMinderConnectionHandler connection = null;
            try {
                connection = aquireSession();
                IZoneMinderMonitor monitor = ZoneMinderFactory.getMonitorProxy(connection, config.getZoneMinderId());
                IMonitorDataGeneral monitorData = monitor.getMonitorData();
                logger.debug("{}:    SourceType:         {}", getLogIdentifier(), monitorData.getSourceType().name());
                logger.debug("{}:    Format:             {}", getLogIdentifier(), monitorData.getFormat());
                logger.debug("{}:    AlarmFrameCount:    {}", getLogIdentifier(), monitorData.getAlarmFrameCount());
                logger.debug("{}:    AlarmMaxFPS:        {}", getLogIdentifier(), monitorData.getAlarmMaxFPS());
                logger.debug("{}:    AnalysisFPS:        {}", getLogIdentifier(), monitorData.getAnalysisFPS());
                logger.debug("{}:    Height x Width:     {} x {}", getLogIdentifier(), monitorData.getHeight(),
                        monitorData.getWidth());
            } catch (ZoneMinderInvalidData | ZoneMinderAuthenticationException | ZoneMinderGeneralException
                    | ZoneMinderResponseException ex) {
                logger.error("{}: context='onThingStatusChanged' error in call to 'getMonitorData' - Message='{}'",
                        getLogIdentifier(), ex.getMessage(), ex.getCause());
            } finally {
                if (connection != null) {
                    releaseSession();
                }
            }

            try {
                updateMonitorProperties();
            } catch (Exception ex) {
                logger.error(
                        "{}: context='onThingStatusChanged' - Exception occurred when calling 'updateMonitorPropoerties()'. Exception='{}'",
                        getLogIdentifier(), ex.getMessage());
            }
        }
    }

    @Override
    public void channelLinked(ChannelUID channelUID) {
        try {
            if (!channelUID.getId().equals(CHANNEL_ONLINE)) {
                dataConverter.subscribe(channelUID);
            }
            super.channelLinked(channelUID);
            logger.debug("{}: context='channelLinked' - Unlinking from channel '{}'", getLogIdentifier(),
                    channelUID.getAsString());
        } catch (Exception ex) {
            logger.debug("{}: context='channelUnlinked' - Exception when Unlinking from channel '{}' - EXCEPTION)'{}'",
                    getLogIdentifier(), channelUID.getAsString(), ex.getMessage());
        }
    }

    @Override
    public void channelUnlinked(ChannelUID channelUID) {
        try {
            dataConverter.unsubscribe(channelUID);
            super.channelUnlinked(channelUID);
            logger.debug("{}: context='channelUnlinked' - Unlinking from channel '{}'", getLogIdentifier(),
                    channelUID.getAsString());
        } catch (Exception ex) {
            logger.debug("{}: context='channelUnlinked' - Exception when Unlinking from channel '{}' - EXCEPTION)'{}'",
                    getLogIdentifier(), channelUID.getAsString(), ex.getMessage());
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("{}: context='handleCommand' Channel '{}' in monitor '{}' received command='{}'",
                getLogIdentifier(), channelUID, getZoneMinderId(), command);

        // Allow refresh of channels
        if (command == RefreshType.REFRESH) {
            updateChannel(channelUID);
            return;
        }

        // Communication to Monitor
        switch (channelUID.getId()) {
            // Done via Telnet connection
            case CHANNEL_MONITOR_FORCE_ALARM:
                handleForceAlarm(command);
                break;

            case CHANNEL_MONITOR_ENABLED:
                handleEnabled(command);
                break;

            case CHANNEL_MONITOR_FUNCTION:
                handleFunction(command);
                break;

            // These are read-only in the channel configuration
            case CHANNEL_MONITOR_EVENT_STATE:
            case CHANNEL_MONITOR_DETAILED_STATUS:
            case CHANNEL_MONITOR_RECORD_STATE:
            case CHANNEL_ONLINE:
            case CHANNEL_MONITOR_EVENT_CAUSE:
            case CHANNEL_MONITOR_CAPTURE_DAEMON_STATE:
            case CHANNEL_MONITOR_ANALYSIS_DAEMON_STATE:
            case CHANNEL_MONITOR_FRAME_DAEMON_STATE:
                break;

            default:
                logger.info("{}: context='handleCommand' Command received for unknown channel: {}", getLogIdentifier(),
                        channelUID.getId());
                break;
        }
    }

    private void handleForceAlarm(Command command) {
        String context = "handleForceAlarm";
        logger.debug("{}: context='{}' Command '{}' for monitor '{}'", getLogIdentifier(), context, command,
                getZoneMinderId());

        IZoneMinderMonitor monitorProxy = null;
        IZoneMinderConnectionHandler connection = null;
        try {
            // Force Alarm can only be activated when Function is either NODECT or MODECT
            if ((dataConverter.getMonitorFunction() == ZoneMinderMonitorFunctionEnum.MODECT)
                    || (dataConverter.getMonitorFunction() == ZoneMinderMonitorFunctionEnum.NODECT)) {

                if ((command == OnOffType.OFF) || (command == OnOffType.ON)) {
                    dataConverter.setMonitorForceAlarmInternal((command == OnOffType.ON) ? true : false);

                    String eventText = getConfigValueAsString(PARAMETER_MONITOR_EVENTTEXT);
                    BigDecimal eventTimeout = getConfigValueAsBigDecimal(PARAMETER_MONITOR_TRIGGER_TIMEOUT);

                    connection = aquireSession();
                    if (connection == null) {
                        logger.error("{}: context='{}' 'ForceAlarm' Command='{}' Failed to get session",
                                getLogIdentifier(), context, command);
                        return;
                    }
                    monitorProxy = ZoneMinderFactory.getMonitorProxy(connection, getZoneMinderId());

                    if (command == OnOffType.ON) {
                        logger.debug("{}: Activate 'ForceAlarm' to '{}' (Reason='{}', Timeout='{}')",
                                getLogIdentifier(), command, eventText, eventTimeout.intValue());
                        getZoneMinderBridgeHandler().activateForceAlarm(getZoneMinderId(), 255, MONITOR_EVENT_OPENHAB,
                                eventText, "", eventTimeout.intValue());
                        dataConverter.setMonitorForceAlarmInternal(true);
                        startAlarmRefresh(eventTimeout.intValue());
                    } else if (command == OnOffType.OFF) {
                        logger.debug("{}: Cancel 'ForceAlarm'", getLogIdentifier());
                        getZoneMinderBridgeHandler().deactivateForceAlarm(getZoneMinderId());
                        dataConverter.setMonitorForceAlarmInternal(false);
                        forceStopAlarmRefresh();
                    }
                    fetchMonitorGeneralData(monitorProxy);
                }
            } else {
                logger.info("{}: context='{}' 'ForceAlarm' inactive when function not MODECT or NODECT",
                        getLogIdentifier(), context);
            }
        } finally {
            if (monitorProxy != null) {
                monitorProxy = null;
                releaseSession();
            }
            requestChannelRefresh();
        }
    }

    public void handleEnabled(Command command) {
        String context = "handleEnabled";
        logger.debug("{}: context='{}' Command '{}' received for monitor '{}'", getLogIdentifier(), context, command,
                getZoneMinderId());
        try {
            if ((command == OnOffType.OFF) || (command == OnOffType.ON)) {
                boolean newState = ((command == OnOffType.ON) ? true : false);

                IZoneMinderMonitor monitorProxy = null;
                ZoneMinderContentResponse zmcr = null;
                try {
                    monitorProxy = ZoneMinderFactory.getMonitorProxy(aquireSession(), getZoneMinderId());
                    if (monitorProxy == null) {
                        logger.error(
                                "{}: Connection to ZoneMinder Server was lost when handling command '{}'. Restart openHAB",
                                getLogIdentifier(), command);
                        return;
                    }
                    zmcr = monitorProxy.SetEnabled(newState);
                    logger.trace("{}: ResponseCode='{}' ResponseMessage='{}' URL='{}'", getLogIdentifier(),
                            zmcr.getHttpStatus(), zmcr.getHttpResponseMessage(), zmcr.getHttpRequestUrl());
                } catch (ZoneMinderException ex) {
                    logger.error("{}: context='{}' ExceptionClass='{}' Message='{}'", getLogIdentifier(), context,
                            ex.getClass().getCanonicalName(), ex.getMessage(), ex.getCause());
                } catch (MalformedURLException e) {
                    logger.error("{}: context='{}' MalformedUrlException from 'SetEnabled' Exception='{}'",
                            getLogIdentifier(), context, e.getMessage());
                } finally {
                    if (monitorProxy != null) {
                        monitorProxy = null;
                        releaseSession();
                    }
                }
                dataConverter.setMonitorEnabled(newState);

                logger.debug("{}: context='handleCommand' tags='enabled' - Changed function to '{}'",
                        getLogIdentifier(), command);
            }
        } finally {
            requestChannelRefresh();
        }
    }

    public void handleFunction(Command command) {
        String context = "handleFunction";
        try {
            logger.debug("{}: context='{}' Command '{}' received for monitor '{}'", getLogIdentifier(), context,
                    command, getZoneMinderId());

            IZoneMinderMonitor monitorProxy = null;
            String commandString = "";
            if (ZoneMinderMonitorFunctionEnum.isValid(command.toString())) {
                commandString = ZoneMinderMonitorFunctionEnum.getEnum(command.toString()).toString();
                ZoneMinderContentResponse zmcr = null;
                try {
                    // Change Function for camera in ZoneMinder
                    monitorProxy = ZoneMinderFactory.getMonitorProxy(aquireSession(), getZoneMinderId());
                    if (monitorProxy == null) {
                        logger.error("{}: Connection to ZoneMinder Server lost when handling command '{}'",
                                getLogIdentifier(), command);
                        return;
                    }
                    zmcr = monitorProxy.SetFunction(commandString);
                    logger.trace("{}: URL='{}' ResponseCode='{}' ResponseMessage='{}'", getLogIdentifier(),
                            zmcr.getHttpRequestUrl(), zmcr.getHttpStatus(), zmcr.getHttpResponseMessage());
                    fetchMonitorGeneralData(monitorProxy);
                    fetchMonitorDaemonStatus(true, true, monitorProxy);
                } catch (MalformedURLException e) {
                    logger.error("{}: context='{}' Got MalformedUrlException Exception='{}'", getLogIdentifier(),
                            context, e.getMessage());
                } catch (ZoneMinderAuthenticationException | ZoneMinderGeneralException
                        | ZoneMinderResponseException ex) {
                    logger.error("{}: context='{}' ExceptionClass='{}' Message='{}'", getLogIdentifier(), context,
                            ex.getClass().getCanonicalName(), ex.getMessage(), ex.getCause());
                } finally {
                    if (monitorProxy != null) {
                        monitorProxy = null;
                        releaseSession();
                    }
                }
                dataConverter.setMonitorFunction(ZoneMinderMonitorFunctionEnum.getEnum(command.toString()));
                logger.debug("{}: context='handleCommand' tags='function' Changed function to '{}'", getLogIdentifier(),
                        commandString);
            } else {
                logger.info("{}: Value '{}' for function must be one of None/Monitor/Modect/Record/Mocord/Nodect",
                        getLogIdentifier(), commandString);
            }
        } finally {
            requestChannelRefresh();
        }
    }

    @Override
    public void onTrippedForceAlarm(ZoneMinderTriggerEvent event) {
        String context = "onTrippedForceAlarm";
        logger.debug("{}: context='{}' Received forceAlarm for monitor {}", getLogIdentifier(), context,
                event.getMonitorId());

        if (!isThingOnline()) {
            logger.info("{}: context='{}' Skipping event '{}', because Thing is 'OFFLINE'", getLogIdentifier(), context,
                    event.toString());
            return;
        }

        IZoneMinderEventData eventData = null;

        // Set Current Event to actual event
        if (event.getState()) {
            IZoneMinderConnectionHandler connection = null;
            try {
                connection = aquireSession();
                IZoneMinderMonitor monitorProxy = ZoneMinderFactory.getMonitorProxy(connection, getZoneMinderId());
                eventData = monitorProxy.getEventById(event.getEventId());

                logger.debug("{}: context='{}' URL='{}' ResponseCode='{}' ResponseMessage='{}'", getLogIdentifier(),
                        context, eventData.getHttpRequestUrl(), eventData.getHttpStatus(),
                        eventData.getHttpResponseMessage());
            } catch (ZoneMinderInvalidData | ZoneMinderAuthenticationException | ZoneMinderGeneralException
                    | ZoneMinderResponseException ex) {
                logger.error("{}: context='{}' error from 'getMonitorProxy' ExceptionClass='{}' - Message='{}'",
                        getLogIdentifier(), context, ex.getClass().getCanonicalName(), ex.getMessage(), ex.getCause());
            } finally {
                if (connection != null) {
                    releaseSession();
                }
            }
            dataConverter.disableRefresh();
            dataConverter.setMonitorForceAlarmExternal(event.getState());
            dataConverter.setMonitorEventData(eventData);
            dataConverter.enableRefresh();
            forceStartAlarmRefresh();
        } else {
            dataConverter.disableRefresh();
            dataConverter.setMonitorForceAlarmExternal(event.getState());
            dataConverter.setMonitorEventData(null);
            dataConverter.enableRefresh();
            forceStopAlarmRefresh();
        }
    }

    @Override
    protected void updateState(ChannelUID channelUID, State state) {
        super.updateState(channelUID, state);
    }

    protected ZoneMinderThingMonitorConfig getMonitorConfig() {
        return this.getConfigAs(ZoneMinderThingMonitorConfig.class);
    }

    @Override
    protected String getZoneMinderThingType() {
        return THING_ZONEMINDER_MONITOR;
    }

    @Override
    public void updateAvaliabilityStatus(IZoneMinderConnectionHandler connection) {
        // Assume success
        ThingStatus newThingStatus = ThingStatus.ONLINE;
        ThingStatusDetail thingStatusDetailed = ThingStatusDetail.NONE;
        String thingStatusDescription = "";

        if (thing.getStatus() == ThingStatus.UNINITIALIZED) {
            logger.debug("UPDATE_THING_STATUS: THING IS UNINITIALIZED");
        }

        // Is connected to ZoneMinder and thing is ONLINE
        if (isConnected() && getThing().getStatus() == ThingStatus.ONLINE) {
            return;
        }

        try {
            Bridge bridge = getBridge();

            // 1. Is there a Bridge assigned?
            if (bridge == null) {
                newThingStatus = ThingStatus.OFFLINE;
                thingStatusDetailed = ThingStatusDetail.BRIDGE_OFFLINE;
                thingStatusDescription = String.format("No Bridge assigned to monitor '%s'", thing.getUID());
                logger.error("{}: context='updateAvailabilityStatus' {}", getLogIdentifier(), thingStatusDescription);
                return;
            } else {
                logger.debug("{}: context='updateAvailabilityStatus' Thing '{}' has Bridge '{}' (PASSED)",
                        getLogIdentifier(), thing.getUID(), bridge.getThingTypeUID());
            }

            // 2. Is Bridge Online?
            if (bridge.getStatus() != ThingStatus.ONLINE) {
                newThingStatus = ThingStatus.OFFLINE;
                thingStatusDetailed = ThingStatusDetail.BRIDGE_OFFLINE;
                thingStatusDescription = String.format("Bridge '%s' is OFFLINE", bridge.getBridgeUID());
                logger.error("{}: context='updateAvailabilityStatus' {}", getLogIdentifier(), thingStatusDescription);
                return;
            } else {
                logger.debug("{}: context='updateAvailabilityStatus' Bridge '{}' is ONLINE (PASSED)",
                        getLogIdentifier(), bridge.getThingTypeUID());
            }

            // 3. Is Configuration OK?
            if (getMonitorConfig() == null) {
                newThingStatus = ThingStatus.OFFLINE;
                thingStatusDetailed = ThingStatusDetail.CONFIGURATION_ERROR;
                thingStatusDescription = String.format("No valid configuration found for '%s'", thing.getUID());
                logger.error("{}: context='updateAvailabilityStatus' {}", getLogIdentifier(), thingStatusDescription);
                return;
            } else {
                logger.debug("{}: context='updateAvailabilityStatus' Thing '{}' has valid configuration (PASSED)",
                        getLogIdentifier(), thing.getUID());
            }

            // ZoneMinder Id for Monitor not set, we are pretty much lost then
            if (getMonitorConfig().getZoneMinderId().isEmpty()) {
                newThingStatus = ThingStatus.OFFLINE;
                thingStatusDetailed = ThingStatusDetail.CONFIGURATION_ERROR;
                thingStatusDescription = String.format("No Id is specified for monitor '%s'", thing.getUID());
                logger.error("{}: {}", getLogIdentifier(), thingStatusDescription);
                return;
            } else {
                logger.debug("{}: context='updateAvailabilityStatus' ZoneMinder Id for Thing '{}' defined (PASSED)",
                        getLogIdentifier(), thing.getUID());
            }

            IZoneMinderMonitor monitorProxy = null;
            IZoneMinderDaemonStatus captureDaemon = null;
            // Consider also looking at Analysis and Frame Daemons (only if they are supposed to be running)
            IZoneMinderConnectionHandler curSession = connection;
            if (curSession != null) {
                monitorProxy = ZoneMinderFactory.getMonitorProxy(curSession, getZoneMinderId());
                captureDaemon = monitorProxy.getCaptureDaemonStatus();
                logger.debug("{}: URL='{}' ResponseCode='{}' ResponseMessage='{}'", getLogIdentifier(),
                        captureDaemon.getHttpRequestUrl(), captureDaemon.getHttpStatus(),
                        captureDaemon.getHttpResponseMessage());
            }

            if (captureDaemon == null) {
                newThingStatus = ThingStatus.OFFLINE;
                thingStatusDetailed = ThingStatusDetail.COMMUNICATION_ERROR;
                thingStatusDescription = "Capture Daemon not accssible";
                logger.error("{}: {}", getLogIdentifier(), thingStatusDescription);
                return;
            } else if (!captureDaemon.getStatus()) {
                newThingStatus = ThingStatus.OFFLINE;
                thingStatusDetailed = ThingStatusDetail.COMMUNICATION_ERROR;
                thingStatusDescription = "Capture Daemon is not running";
                logger.error("{}: {}", getLogIdentifier(), thingStatusDescription);
                return;
            }
            newThingStatus = ThingStatus.ONLINE;
            forcedPriority = RefreshPriority.PRIORITY_BATCH;
        } catch (ZoneMinderException e) {
            newThingStatus = ThingStatus.OFFLINE;
            thingStatusDetailed = ThingStatusDetail.COMMUNICATION_ERROR;
            thingStatusDescription = "Error occurred (Check log)";
            updateThingStatus(newThingStatus, thingStatusDetailed, thingStatusDescription);
            logger.error("{}: context='updateAvailabilityStatus' Exception occurred '{}'", getLogIdentifier(),
                    e.getMessage(), e);
            return;
        } finally {
            updateThingStatus(newThingStatus, thingStatusDetailed, thingStatusDescription);
        }
    }

    /*
     * From here we update states in openHAB
     *
     * @see org.openhab.binding.zoneminder.handler.ZoneMinderBaseThingHandler#updateChannel(ChannelUID)
     */
    @Override
    public void updateChannel(ChannelUID channel) {
        State state = UnDefType.UNDEF;

        switch (channel.getId()) {
            case CHANNEL_ONLINE:
                super.updateChannel(channel);
                return;

            case CHANNEL_MONITOR_ENABLED:
            case CHANNEL_MONITOR_FORCE_ALARM:
            case CHANNEL_MONITOR_EVENT_STATE:
            case CHANNEL_MONITOR_RECORD_STATE:
            case CHANNEL_MONITOR_MOTION_EVENT:
            case CHANNEL_MONITOR_DETAILED_STATUS:
            case CHANNEL_MONITOR_EVENT_CAUSE:
            case CHANNEL_MONITOR_FUNCTION:
            case CHANNEL_MONITOR_CAPTURE_DAEMON_STATE:
            case CHANNEL_MONITOR_ANALYSIS_DAEMON_STATE:
            case CHANNEL_MONITOR_FRAME_DAEMON_STATE:
            case CHANNEL_MONITOR_STILL_IMAGE:
                state = null;
                break;

            case CHANNEL_MONITOR_VIDEOURL:
                state = dataConverter.getVideoUrl();
                break;
            default:
                logger.warn("{}: updateChannel(): Monitor '{}': No handler defined for channel='{}'",
                        getLogIdentifier(), thing.getLabel(), channel.getAsString());
                // Ask super class to handle
                super.updateChannel(channel);
        }

        if (state != null) {
            updateState(channel.getId(), state);
        }
    }

    @Override
    public void updateStatus(ThingStatus status) {
        super.updateStatus(status);
        updateState(CHANNEL_ONLINE, ((status == ThingStatus.ONLINE) ? OnOffType.ON : OnOffType.OFF));

    }

    private long getLastRefreshGeneralData() {
        return lastRefreshGeneralData;
    }

    private long getLastRefreshStillImage() {
        return lastRefreshStillImage;
    }

    private boolean refreshGeneralData() {
        long now = System.currentTimeMillis();
        long lastUpdate = getLastRefreshGeneralData();

        // Normal refresh interval
        int interval = 10000;

        if (!isInitialized()) {
            return true;
        }
        if (dataConverter.isAlarmed()) {
            // Alarm refresh interval
            interval = 1000;
        }
        return ((now - lastUpdate) > interval) ? true : false;
    }

    private boolean refreshStillImage() {
        RefreshPriority priority;
        long now = System.currentTimeMillis();
        long lastUpdate = getLastRefreshStillImage();

        // Normal refresh interval
        int interval = 10000;

        if (!isInitialized()) {
            return true;
        }
        if (dataConverter.isAlarmed()) {
            priority = getMonitorConfig().getImageRefreshEvent();
        } else {
            priority = getMonitorConfig().getImageRefreshIdle();
        }
        switch (priority) {
            case DISABLED:
                return false;

            case PRIORITY_BATCH:
                interval = 1000 * 60 * 60;
                break;

            case PRIORITY_LOW:
                interval = 1000 * 60;
                break;

            case PRIORITY_NORMAL:
                interval = 1000 * 10;
                break;

            case PRIORITY_HIGH:
                interval = 1000 * 5;
                break;

            case PRIORITY_ALARM:
                interval = 1000;
                break;
            default:
                return false;
        }
        return ((now - lastUpdate) > interval) ? true : false;
    }

    @Override
    protected void onFetchData(RefreshPriority cyclePriority) {
        String context = "onFetchData";

        if (getThing().getStatus() != ThingStatus.ONLINE || forcedPriority == RefreshPriority.UNKNOWN) {
            return;
        }

        RefreshPriority curRefreshPriority = RefreshPriority.DISABLED;
        if (forcedPriority == RefreshPriority.DISABLED) {
            curRefreshPriority = cyclePriority;
        } else {
            curRefreshPriority = forcedPriority;
            forcedPriority = RefreshPriority.DISABLED;
        }

        IZoneMinderConnectionHandler session = null;
        session = aquireSession();
        if (session == null) {
            logger.info("{}: context='{}' Failed to get session, skip monitor refresh", getLogIdentifier(), context);
            return;
        }

        IZoneMinderMonitor monitorProxy = ZoneMinderFactory.getMonitorProxy(session, getZoneMinderId());
        dataConverter.disableRefresh();

        // Perform refresh of monitor data
        boolean refreshChannels = false;
        if (refreshGeneralData()) {
            refreshChannels = true;
            fetchMonitorGeneralData(monitorProxy);
            fetchMonitorDaemonStatus(true, true, monitorProxy);
        }

        if (isLinked(CHANNEL_MONITOR_STILL_IMAGE)) {
            try {
                if (refreshStillImage()) {
                    lastRefreshStillImage = System.currentTimeMillis();
                    IMonitorDataStillImage monitorImage = monitorProxy.getMonitorStillImage(config.getImageScale(),
                            1000, null);
                    logger.debug("{}: context='{}' tag='image' URL='{}' ResponseCode='{}'", getLogIdentifier(), context,
                            monitorImage.getHttpRequestUrl(), monitorImage.getHttpStatus());

                    dataConverter.setMonitorStillImage(monitorImage.getImage());
                }
            } catch (MalformedURLException e) {
                logger.error("{}: context='{}' MalformedURLException occurred calling 'getMonitorStillImage'",
                        getLogIdentifier(), context, e.getCause());
                dataConverter.setMonitorStillImage(null);
            } catch (Exception e) {
                logger.error("{}: context='{}' Error in call to 'getMonitorStillImage'", getLogIdentifier(), context,
                        e.getCause());
                dataConverter.setMonitorStillImage(null);
            } catch (ZoneMinderException e) {
                logger.error("{}: context='{}' Error calling 'getMonitorStillImage' Exception='{}'", getLogIdentifier(),
                        context, e.getMessage(), e.getCause());
            }
        } else {
            dataConverter.setMonitorStillImage(null);
        }

        if (curRefreshPriority.isPriorityActive(RefreshPriority.PRIORITY_LOW)) {
            try {
                if (dataConverter != null) {
                    String str = monitorProxy.getMonitorStreamingPath(config.getImageScale(), 1000, null);
                    dataConverter.setMonitorVideoUrl(str);
                }
            } catch (MalformedURLException e) {
                logger.error("{}: context='{}' MalformedURLException calling 'getMonitorStreamingPath': {}",
                        getLogIdentifier(), context, e.getMessage());
            } catch (ZoneMinderGeneralException e) {
                logger.error("{}: context='{}' Error calling 'getMonitorStreamingPath' Exception='{}', Msg='{}",
                        getLogIdentifier(), context, e.getMessage(), e.getCause());
            } catch (ZoneMinderResponseException e) {
                logger.error(
                        "{}: context='{}' Error calling 'getMonitorStreamingPath' Exception='{} Http: Status='{}', Message='{}'",
                        getLogIdentifier(), context, e.getMessage(), e.getHttpStatus(), e.getHttpMessage(),
                        e.getCause());
            } catch (ZoneMinderAuthHashNotEnabled e) {
                logger.error("{}: context='{}' Error calling 'getMonitorStreamingPath' Exception='{}'",
                        getLogIdentifier(), context, e.getMessage(), e.getCause());
            }
        }
        releaseSession();
        dataConverter.enableRefresh();

        if (refreshChannels) {
            logger.debug("{}: context='onFetchData' Data has changed, channels need refreshing", getLogIdentifier());
            requestChannelRefresh();
        }
        tryStopAlarmRefresh();
    }

    void fetchMonitorGeneralData(IZoneMinderMonitor proxy) {
        String context = "fetchMonitorGeneralData";
        IZoneMinderMonitor monitorProxy = proxy;
        boolean doRelase = false;
        if (monitorProxy == null) {
            doRelase = true;
            monitorProxy = ZoneMinderFactory.getMonitorProxy(aquireSession(), getZoneMinderId());
        }

        try {
            IMonitorDataGeneral generalData = monitorProxy.getMonitorData();
            dataConverter.setMonitorGeneralData(generalData);
            logger.debug("{}: context='{}' tag='monitorData' URL='{}' ResponseCode='{}' ResponseMessage='{}'",
                    getLogIdentifier(), context, generalData.getHttpRequestUrl(), generalData.getHttpStatus(),
                    generalData.getHttpResponseMessage());
        } catch (ZoneMinderInvalidData zmid) {
            logger.error("{}: context='{}' Error from 'getMonitorData' Exception='{}' Msg='{}', Rsp='{}'",
                    getLogIdentifier(), context, zmid.getClass().getCanonicalName(), zmid.getResponseString(),
                    zmid.getMessage(), zmid.getCause());
        } catch (ZoneMinderAuthenticationException | ZoneMinderGeneralException | ZoneMinderResponseException zme) {
            logger.error("{}: context='{}' Error from 'getMonitorData' Exception='{}' Msg='{}'", getLogIdentifier(),
                    context, zme.getClass().getCanonicalName(), zme.getMessage(), zme.getCause());
        }

        try {
            ZoneMinderMonitorStatus status = monitorProxy.getMonitorDetailedStatus();
            dataConverter.setMonitorDetailedStatus(status.getStatus());
            logger.debug("{}: context='{}' tag='detailedStatus' URL='{}' ResponseCode='{}' ResponseMessage='{}'",
                    getLogIdentifier(), context, status.getHttpRequestUrl(), status.getHttpStatus(),
                    status.getHttpResponseMessage());

        } catch (ZoneMinderInvalidData zmid) {
            logger.error("{}: context='{}' Error from 'getMonitorDetailedStatus' Exception='{}', Msg='{}', Rsp='{}'",
                    getLogIdentifier(), context, zmid.getClass().getCanonicalName(), zmid.getMessage(),
                    zmid.getResponseString(), zmid.getCause());
        } catch (ZoneMinderAuthenticationException | ZoneMinderGeneralException | ZoneMinderResponseException zme) {
            logger.error("{}: context='{}' Error from 'getMonitorDetailedStatus' Exception='{}' Msg='{}'",
                    getLogIdentifier(), context, zme.getClass().getCanonicalName(), zme.getMessage(), zme.getCause());
        } finally {
            if (doRelase) {
                releaseSession();
            }
        }
        lastRefreshGeneralData = System.currentTimeMillis();
    }

    void fetchMonitorDaemonStatus(boolean fetchCapture, boolean fetchAnalysisFrame, IZoneMinderMonitor proxy) {
        String context = "fetchMonitorDaemonStatus";
        IZoneMinderMonitor monitorProxy = proxy;
        boolean fetchFrame = false;

        boolean doRelease = false;
        if (monitorProxy == null) {
            doRelease = true;
            monitorProxy = ZoneMinderFactory.getMonitorProxy(aquireSession(), getZoneMinderId());
        }
        try {
            State stateCapture = UnDefType.UNDEF;
            State stateAnalysis = UnDefType.UNDEF;
            State stateFrame = UnDefType.UNDEF;

            IZoneMinderDaemonStatus captureDaemon = null;
            IZoneMinderDaemonStatus analysisDaemon = null;
            IZoneMinderDaemonStatus frameDaemon = null;

            if (isLinked(CHANNEL_MONITOR_CAPTURE_DAEMON_STATE)) {
                try {
                    if (fetchCapture) {
                        captureDaemon = monitorProxy.getCaptureDaemonStatus();
                        logger.debug(
                                "{}: context='{}' tag='captureDaemon' URL='{}' ResponseCode='{}' ResponseMessage='{}'",
                                getLogIdentifier(), context, captureDaemon.getHttpRequestUrl(),
                                captureDaemon.getHttpStatus(), captureDaemon.getHttpResponseMessage());
                        stateCapture = (captureDaemon.getStatus() ? OnOffType.ON : OnOffType.OFF);
                    }
                } catch (ZoneMinderResponseException zmre) {
                    logger.error(
                            "{}: context='{}' Error from 'getCaptureDaemonStatus' Http: Status='{}', Msg='{}', ExceptionMessage='{}', Exception='{}', Message={}'",
                            getLogIdentifier(), context, zmre.getHttpStatus(), zmre.getHttpMessage(),
                            zmre.getExceptionMessage(), zmre.getClass().getCanonicalName(), zmre.getMessage(),
                            zmre.getCause());
                } catch (ZoneMinderInvalidData zmid) {
                    logger.error(
                            "{}: context='{}' Error from 'getCaptureDaemonStatus' Rsp='{}', Exception='{}', Msg={}'",
                            getLogIdentifier(), context, zmid.getResponseString(), zmid.getClass().getCanonicalName(),
                            zmid.getMessage(), zmid.getCause());
                } catch (ZoneMinderGeneralException | ZoneMinderAuthenticationException zme) {
                    logger.error("{}: context='{}' Error from 'getCaptureDaemonStatus' Exception='{}', Msg={}' ",
                            getLogIdentifier(), context, zme.getClass().getCanonicalName(), zme.getMessage(),
                            zme.getCause());
                } finally {
                    if (captureDaemon != null) {
                        dataConverter.setMonitorCaptureDaemonStatus(stateCapture);
                    }
                }
            }

            if (isLinked(CHANNEL_MONITOR_ANALYSIS_DAEMON_STATE)) {
                try {
                    stateAnalysis = UnDefType.UNDEF;
                    if (fetchAnalysisFrame) {
                        analysisDaemon = monitorProxy.getAnalysisDaemonStatus();
                        logger.debug(
                                "{}: context='{}' tag='analysisDaemon' URL='{}' ResponseCode='{}' ResponseMsg='{}'",
                                getLogIdentifier(), context, analysisDaemon.getHttpRequestUrl(),
                                analysisDaemon.getHttpStatus(), analysisDaemon.getHttpResponseMessage());

                        stateAnalysis = (analysisDaemon.getStatus() ? OnOffType.ON : OnOffType.OFF);
                        fetchFrame = true;
                    }
                } catch (ZoneMinderResponseException zmre) {
                    logger.error(
                            "{}: context='{}' Error from 'getAnalysisDaemonStatus' Http: Status='{}', Message='{}', ExceptionMessage='{}', Exception='{}'",
                            getLogIdentifier(), context, zmre.getHttpStatus(), zmre.getHttpMessage(),
                            zmre.getExceptionMessage(), zmre.getClass().getCanonicalName(), zmre.getCause());
                } catch (ZoneMinderInvalidData zmid) {
                    logger.error("{}: context='{}' Error from 'getAnalysisDaemonStatus' Response='{}', Exception='{}'",
                            getLogIdentifier(), context, zmid.getResponseString(), zmid.getClass().getCanonicalName(),
                            zmid.getCause());
                } catch (ZoneMinderGeneralException | ZoneMinderAuthenticationException zme) {
                    logger.error("{}: context='{}' Error from 'getAnalysisDaemonStatus' Exception='{}' ",
                            getLogIdentifier(), zme.getClass().getCanonicalName(), zme.getCause());
                } finally {
                    dataConverter.setMonitorAnalysisDaemonStatus(stateAnalysis);
                }
            }

            if (isLinked(CHANNEL_MONITOR_FRAME_DAEMON_STATE)) {
                try {
                    stateFrame = UnDefType.UNDEF;
                    if ((fetchFrame) && frameDaemonActive) {
                        frameDaemon = monitorProxy.getFrameDaemonStatus();
                        logger.debug("{}: context='{}' tag='frameDaemon' URL='{}' ResponseCode='{}' ResponseMsg='{}'",
                                getLogIdentifier(), context, frameDaemon.getHttpRequestUrl(),
                                frameDaemon.getHttpStatus(), frameDaemon.getHttpResponseMessage());

                        if (frameDaemon != null) {
                            stateFrame = ((frameDaemon.getStatus() && analysisDaemon.getStatus()) ? OnOffType.ON
                                    : OnOffType.OFF);
                        }
                    }
                } catch (ZoneMinderResponseException zmre) {
                    logger.error(
                            "{}: context='{}' Error from 'getFrameDaemonStatus' Http: Status='{}', Message='{}', ExceptionMessage'{}', Exception='{}'",
                            getLogIdentifier(), context, zmre.getHttpStatus(), zmre.getHttpMessage(),
                            zmre.getExceptionMessage(), zmre.getClass().getCanonicalName(), zmre.getCause());
                } catch (ZoneMinderInvalidData zmid) {
                    logger.error("{}: context='{}' Error from 'getFrameDaemonStatus' Response='{}', Exception='{}'",
                            getLogIdentifier(), context, zmid.getResponseString(), zmid.getClass().getCanonicalName(),
                            zmid.getCause());
                } catch (ZoneMinderGeneralException | ZoneMinderAuthenticationException zme) {
                    logger.error("{}: context='{}' Error from 'getFrameDaemonStatus' Exception='{}'",
                            getLogIdentifier(), context, zme.getClass().getCanonicalName(), zme.getCause());
                } finally {
                    dataConverter.setMonitorFrameDaemonStatus(stateFrame);
                }
            }
        } finally {
            if (doRelease) {
                releaseSession();
            }
        }
    }

    /*
     * This is experimental
     * Try to add different properties
     */
    private void updateMonitorProperties() {
        logger.debug("{}: Update Monitor Properties", getLogIdentifier());
        // Update property information about this device
        Map<String, String> properties = editProperties();
        IZoneMinderMonitor monitorProxy = null;
        IMonitorDataGeneral monitorData = null;
        IZoneMinderConnectionHandler session = null;
        try {
            session = aquireSession();

            if (session == null) {
                logger.error("{}: context='updateMonitorProperties' Unable to aquire session.", getLogIdentifier());
                return;
            }
            monitorProxy = ZoneMinderFactory.getMonitorProxy(session, getZoneMinderId());
            monitorData = monitorProxy.getMonitorData();
            logger.debug("{}: URL='{}' ResponseCode='{}' ResponseMessage='{}'", getLogIdentifier(),
                    monitorData.getHttpRequestUrl(), monitorData.getHttpStatus(), monitorData.getHttpResponseMessage());

        } catch (Exception e) {
            logger.error("{}: Exception occurred when updating monitor properties - Message:{}", getLogIdentifier(),
                    e.getMessage());

        } catch (ZoneMinderException ex) {
            logger.error(
                    "{}: context='onFetchData' error in call to 'getMonitorData' ExceptionClass='{}' - Message='{}'",
                    getLogIdentifier(), ex.getClass().getCanonicalName(), ex.getMessage(), ex.getCause());
        } finally {
            if (session != null) {
                releaseSession();
            }
        }

        if (monitorData != null) {
            properties.put(ZoneMinderProperties.PROPERTY_ID, getLogIdentifier());
            properties.put(ZoneMinderProperties.PROPERTY_NAME, monitorData.getName());
            properties.put(ZoneMinderProperties.PROPERTY_MONITOR_SOURCETYPE, monitorData.getSourceType().name());
            properties.put(ZoneMinderProperties.PROPERTY_MONITOR_ANALYSIS_FPS, monitorData.getAnalysisFPS());
            properties.put(ZoneMinderProperties.PROPERTY_MONITOR_MAXIMUM_FPS, monitorData.getMaxFPS());
            properties.put(ZoneMinderProperties.PROPERTY_MONITOR_ALARM_MAXIMUM, monitorData.getAlarmMaxFPS());
            properties.put(ZoneMinderProperties.PROPERTY_MONITOR_IMAGE_WIDTH, monitorData.getWidth());
            properties.put(ZoneMinderProperties.PROPERTY_MONITOR_IMAGE_HEIGHT, monitorData.getHeight());
        }

        // Must loop over the new properties since we might have added data
        boolean update = false;
        Map<String, String> originalProperties = editProperties();
        for (String property : properties.keySet()) {
            if ((originalProperties.get(property) == null
                    || !originalProperties.get(property).equals(properties.get(property)))) {
                update = true;
                break;
            }
        }
        if (update) {
            logger.debug("{}: context='updateMonitorProperties' Properties synchronised", getLogIdentifier());
            updateProperties(properties);
        }
    }

    @Override
    public String getLogIdentifier() {
        String result = "[MONITOR]";
        if (logIdentifier != null) {
            result = logIdentifier;
        } else if (config != null && config.getZoneMinderId() != null) {
            result = String.format("[MONITOR-%s]", config.getZoneMinderId().toString());
            logIdentifier = result;
        }
        return result;
    }

    @Override
    public void onStateChanged(ChannelUID channelUID, State state) {
        logger.debug("{}: context='onStateChanged' channel='{}' - State changed to '{}'", getLogIdentifier(),
                channelUID.getAsString(), state.toString());
        updateState(channelUID.getId(), state);
    }

    @Override
    public void onRefreshDisabled() {
    }

    @Override
    public void onRefreshEnabled() {
    }
}
