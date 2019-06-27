/*
 ***************************************************************************************
 *  Copyright (C) 2006 EsperTech, Inc. All rights reserved.                            *
 *  http://www.espertech.com/esper                                                     *
 *  http://www.espertech.com                                                           *
 *  ---------------------------------------------------------------------------------- *
 *  The software in this package is published under the terms of the GPL license       *
 *  a copy of which has been included with this distribution in the license.txt file.  *
 ***************************************************************************************
 */
package com.espertech.esper.common.internal.event.json.writer;

import com.espertech.esper.common.client.EventBean;
import com.espertech.esper.common.internal.event.core.EventBeanWriter;

/**
 * Writer method for writing to Json-type events.
 */
public class JsonEventBeanWriterPerProp implements EventBeanWriter {
    private final JsonEventBeanPropertyWriter[] writers;

    /**
     * Ctor.
     *
     * @param writers names of properties to write
     */
    public JsonEventBeanWriterPerProp(JsonEventBeanPropertyWriter[] writers) {
        this.writers = writers;
    }

    /**
     * Write values to an event.
     *
     * @param values   to write
     * @param theEvent to write to
     */
    public void write(Object[] values, EventBean theEvent) {
        Object event = theEvent.getUnderlying();
        for (int i = 0; i < writers.length; i++) {
            writers[i].write(values[i], event);
        }
    }
}
