/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package org.openhab.binding.zoneminder.internal.state;

import javax.activation.UnsupportedDataTypeException;

import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.types.State;
import org.eclipse.smarthome.core.types.UnDefType;
import org.openhab.binding.zoneminder.internal.handler.ZoneMinderThingMonitorHandler;

/**
 * The {@link ChannelOnOffType} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Martin S. Eskildsen - Initial contribution
 */

public class ChannelOnOffType extends GenericChannelState {

    protected ChannelOnOffType(ChannelUID channelUID, GenericThingState thing, ZoneMinderThingMonitorHandler handler) {
        super(channelUID, thing, handler);
    }

    @Override
    protected State convert(Object state) throws UnsupportedDataTypeException {
        State newState;
        if (state instanceof String) {
            if (((String) state).equalsIgnoreCase("ON")) {
                newState = OnOffType.ON;
            } else if (((String) state).equalsIgnoreCase("OFF")) {
                newState = OnOffType.OFF;
            } else {
                throw new UnsupportedDataTypeException();
            }
        } else if (state instanceof Boolean) {
            newState = ((Boolean) state) ? OnOffType.ON : OnOffType.OFF;
        } else if (state instanceof OnOffType) {
            newState = (OnOffType) state;
        } else if (state instanceof UnDefType) {
            newState = UnDefType.UNDEF;
        } else {
            throw new UnsupportedDataTypeException();
        }
        return newState;
    }
}
