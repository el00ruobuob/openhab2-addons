/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.zoneminder.internal.discovery;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.smarthome.config.discovery.AbstractDiscoveryService;
import org.eclipse.smarthome.config.discovery.DiscoveryResult;
import org.eclipse.smarthome.config.discovery.DiscoveryResultBuilder;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.openhab.binding.zoneminder.internal.ZoneMinderConstants;
import org.openhab.binding.zoneminder.internal.handler.ZoneMinderServerBridgeHandler;
import org.openhab.binding.zoneminder.internal.handler.ZoneMinderThingMonitorHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import name.eskildsen.zoneminder.data.IMonitorDataGeneral;

/**
 *
 * @author Martin S. Eskildsen - Initial contribution
 */
public class ZoneMinderDiscoveryService extends AbstractDiscoveryService {
    private final Logger logger = LoggerFactory.getLogger(ZoneMinderDiscoveryService.class);

    private ZoneMinderServerBridgeHandler serverHandler;

    public ZoneMinderDiscoveryService(ZoneMinderServerBridgeHandler coordinatorHandler, int searchTime) {
        super(searchTime);
        this.serverHandler = coordinatorHandler;
    }

    public void activate() {
        logger.debug("[DISCOVERY]: Activating ZoneMinder discovery service for {}", serverHandler.getThing().getUID());
    }

    @Override
    public void deactivate() {
        logger.debug("[DISCOVERY]: Deactivating ZoneMinder discovery service for {}",
                serverHandler.getThing().getUID());
    }

    @Override
    public Set<ThingTypeUID> getSupportedThingTypes() {
        return ZoneMinderThingMonitorHandler.SUPPORTED_THING_TYPES;
    }

    @Override
    public void startBackgroundDiscovery() {
        logger.trace("[DISCOVERY]: Performing background discovery scan for {}", serverHandler.getThing().getUID());
        // removeOlderResults(getTimestampOfLastScan());
        discoverMonitors();
    }

    @Override
    public void startScan() {
        logger.debug("[DISCOVERY]: Starting discovery scan for {}", serverHandler.getThing().getUID());
        discoverMonitors();
    }

    @Override
    public synchronized void abortScan() {
        super.abortScan();
    }

    @Override
    protected synchronized void stopScan() {
        super.stopScan();
    }

    private String buildMonitorLabel(String id, String name) {
        return String.format("%s [%s]", ZoneMinderConstants.ZONEMINDER_MONITOR_NAME, name);
    }

    protected synchronized void discoverMonitors() {
        for (IMonitorDataGeneral monitorData : serverHandler.getMonitors()) {
            ThingUID thingUID = getMonitorThingUID(monitorData);

            logger.trace("[DISCOVERY]: Monitor with Id='{}' and Name='{}' added to Inbox with ThingUID='{}'",
                    monitorData.getId(), monitorData.getName(), thingUID);

            DiscoveryResult discoveryResult = createMonitorDiscoveryResult(thingUID, monitorData);
            thingDiscovered(discoveryResult);
        }
    }

    private ThingUID getMonitorThingUID(IMonitorDataGeneral monitor) {
        ThingUID bridgeUID = serverHandler.getThing().getUID();
        String monitorUID = String.format("%s-%s", ZoneMinderConstants.THING_ZONEMINDER_MONITOR, monitor.getId());

        return new ThingUID(ZoneMinderConstants.THING_TYPE_THING_ZONEMINDER_MONITOR, bridgeUID, monitorUID);
    }

    protected DiscoveryResult createMonitorDiscoveryResult(ThingUID monitorUID, IMonitorDataGeneral monitorData) {
        ThingUID bridgeUID = serverHandler.getThing().getUID();

        Map<String, Object> properties = new HashMap<>(0);
        properties.put(ZoneMinderConstants.PARAMETER_MONITOR_ID, Integer.valueOf(monitorData.getId()));
        // FIXME This is overwriting config changes made to a monitor thing
        // properties.put(ZoneMinderConstants.PARAMETER_MONITOR_TRIGGER_TIMEOUT,
        // ZoneMinderConstants.PARAMETER_MONITOR_TRIGGER_TIMEOUT_DEFAULTVALUE);
        // properties.put(ZoneMinderConstants.PARAMETER_MONITOR_EVENTTEXT, ZoneMinderConstants.MONITOR_EVENT_OPENHAB);

        return DiscoveryResultBuilder.create(monitorUID).withProperties(properties).withBridge(bridgeUID)
                .withLabel(buildMonitorLabel(monitorData.getId(), monitorData.getName())).build();
    }
}
